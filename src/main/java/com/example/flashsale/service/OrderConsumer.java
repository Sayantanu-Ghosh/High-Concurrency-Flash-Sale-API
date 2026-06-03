package com.example.flashsale.service;

import com.example.flashsale.entity.FlashSaleOrder;
import com.example.flashsale.repository.FlashSaleOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
public class OrderConsumer {

    private static final Logger logger = LoggerFactory.getLogger(OrderConsumer.class);
    private static final int BATCH_SIZE = 100; // Number of orders to pull from the queue at once
    private static final long POLL_TIMEOUT_MS = 1000; // Wait up to 1 second for new orders

    @Autowired
    private OrderQueueService orderQueueService;

    @Autowired
    private FlashSaleOrderRepository orderRepository;

    @Autowired
    @Qualifier("orderProcessingExecutor")
    private Executor executor;

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
        logger.info("OrderConsumer worker stopped.");
    }

    private void processBatch() throws InterruptedException {
        BlockingQueue<FlashSaleOrder> queue = orderQueueService.getOrderQueue();
        List<FlashSaleOrder> batch = new ArrayList<>(BATCH_SIZE);

        // Block until the first item is available
        FlashSaleOrder firstOrder = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (firstOrder != null) {
            batch.add(firstOrder);
            // Drain up to BATCH_SIZE - 1 more items without blocking
            queue.drainTo(batch, BATCH_SIZE - 1);
        }

        if (!batch.isEmpty()) {
            logger.info("Processing a batch of {} orders.", batch.size());
            saveOrdersToDatabase(batch);
        }
    }

    @Transactional
    public void saveOrdersToDatabase(List<FlashSaleOrder> orders) {
        try {
            orderRepository.saveAll(orders);
            orderRepository.flush(); // Ensure writes are sent to the DB
            logger.info("Successfully saved a batch of {} orders to the database.", orders.size());
        } catch (Exception e) {
            // In a real-world scenario, you would need a dead-letter queue (DLQ)
            // or another retry mechanism for failed batches.
            logger.error("Failed to save order batch to database. Orders in this batch are lost. Count: {}", orders.size(), e);
        }
    }

    public void stop() {
        running = false;
    }
}
