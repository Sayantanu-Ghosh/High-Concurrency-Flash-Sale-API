package com.example.flashsale.service;

import com.example.flashsale.entity.FlashSaleOrder;
import com.example.flashsale.repository.FlashSaleOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
public class OrderConsumer {

    private static final Logger logger = LoggerFactory.getLogger(OrderConsumer.class);
    private static final String QUEUE_KEY = "flash_sale:order_queue";
    private static final int BATCH_SIZE = 100; // Number of orders to pull from the queue at once
    private static final long POLL_TIMEOUT_MS = 1000; // Wait up to 1 second for new orders

    @Autowired
    private FlashSaleOrderRepository orderRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("orderProcessingExecutor")
    private Executor executor;

    @Autowired
    @Lazy
    private OrderConsumer self;

    private volatile boolean running = true;

    public void start() {
        executor.execute(this::run);
    }

    private void run() {
        logger.info("OrderConsumer worker started.");
        while (running) {
            try {
                processBatch();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("OrderConsumer was interrupted. Shutting down...");
                running = false;
            } catch (Exception e) {
                logger.error("Unexpected error in OrderConsumer loop.", e);
                // Avoid tight loop on continuous errors
                try {
                    Thread.sleep(POLL_TIMEOUT_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }

        // Final drain on shutdown to process any leftover items in the queue
        logger.info("OrderConsumer worker stopping. Draining remaining queue items...");
        try {
            drainQueueOnShutdown();
        } catch (Exception e) {
            logger.error("Error during queue draining on shutdown.", e);
        }

        logger.info("OrderConsumer worker stopped.");
    }

    private void processBatch() throws InterruptedException {
        List<FlashSaleOrder> batch = new ArrayList<>(BATCH_SIZE);

        try {
            // Block pop the first order
            Object firstItemObj = redisTemplate.opsForList().leftPop(QUEUE_KEY, POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (firstItemObj != null) {
                String firstItemJson = (String) firstItemObj;
                FlashSaleOrder firstOrder = objectMapper.readValue(firstItemJson, FlashSaleOrder.class);
                batch.add(firstOrder);

                // Drain up to BATCH_SIZE - 1 more items without blocking
                for (int i = 0; i < BATCH_SIZE - 1; i++) {
                    Object nextItemObj = redisTemplate.opsForList().leftPop(QUEUE_KEY);
                    if (nextItemObj == null) {
                        break;
                    }
                    String nextItemJson = (String) nextItemObj;
                    FlashSaleOrder nextOrder = objectMapper.readValue(nextItemJson, FlashSaleOrder.class);
                    batch.add(nextOrder);
                }
            }
        } catch (Exception e) {
            logger.error("Error popping or deserializing order from Redis queue.", e);
        }

        if (!batch.isEmpty()) {
            logger.info("Processing a batch of {} orders from Redis queue.", batch.size());
            saveOrdersToDatabase(batch);
        }
    }

    private void drainQueueOnShutdown() {
        List<FlashSaleOrder> batch = new ArrayList<>();
        try {
            while (true) {
                Object itemObj = redisTemplate.opsForList().leftPop(QUEUE_KEY);
                if (itemObj == null) {
                    break;
                }
                String json = (String) itemObj;
                FlashSaleOrder order = objectMapper.readValue(json, FlashSaleOrder.class);
                batch.add(order);
                if (batch.size() >= BATCH_SIZE) {
                    saveOrdersToDatabase(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                saveOrdersToDatabase(batch);
            }
        } catch (Exception e) {
            logger.error("Error draining remaining queue items during shutdown.", e);
        }
    }

    public void saveOrdersToDatabase(List<FlashSaleOrder> orders) {
        try {
            // Try saving the entire batch in one transaction first
            self.saveBatchTransactional(orders);
            logger.info("Successfully saved a batch of {} orders to the database.", orders.size());
        } catch (Exception e) {
            logger.warn("Batch save failed, falling back to one-by-one order persistence: {}", e.getMessage());
            // Slow path: process one-by-one to isolate the failures
            for (FlashSaleOrder order : orders) {
                try {
                    self.saveSingleOrderTransactional(order);
                } catch (org.springframework.dao.DataIntegrityViolationException dive) {
                    logger.error("Data integrity violation (likely duplicate user/item order). Refunding Redis stock for user {} and item {}.", 
                        order.getUserId(), order.getItemId(), dive);
                    inventoryService.refundStock(order.getItemId(), order.getUserId(), order.getQuantity());
                } catch (Exception ex) {
                    logger.error("Transient or unrecoverable error saving order for user {} and item {}. Refunding stock to prevent inventory leak.", 
                        order.getUserId(), order.getItemId(), ex);
                    inventoryService.refundStock(order.getItemId(), order.getUserId(), order.getQuantity());
                }
            }
        }
    }

    @Transactional
    public void saveBatchTransactional(List<FlashSaleOrder> orders) {
        orderRepository.saveAll(orders);
        orderRepository.flush(); // Force database to write
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSingleOrderTransactional(FlashSaleOrder order) {
        orderRepository.save(order);
        orderRepository.flush();
    }

    @PreDestroy
    public void stop() {
        logger.info("Stopping OrderConsumer background worker gracefully...");
        running = false;
    }
}
