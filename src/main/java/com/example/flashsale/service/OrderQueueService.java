package com.example.flashsale.service;

import com.example.flashsale.dto.SaleRequest;
import com.example.flashsale.entity.FlashSaleOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderQueueService {

    private static final Logger logger = LoggerFactory.getLogger(OrderQueueService.class);
    private static final String QUEUE_KEY = "flash_sale:order_queue";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderConsumer orderConsumer;

    public boolean addOrderRequest(SaleRequest request) {
        try {
            FlashSaleOrder order = new FlashSaleOrder(request.getUserId(), request.getItemId(), request.getQuantity());
            String orderJson = objectMapper.writeValueAsString(order);

            // Push order to Redis List queue
            redisTemplate.opsForList().rightPush(QUEUE_KEY, orderJson);
            return true;
        } catch (Exception e) {
            logger.error("Failed to push order request to Redis queue for user {} and item {}.", 
                request.getUserId(), request.getItemId(), e);
            return false;
        }
    }

    @PostConstruct
    private void startConsumer() {
        logger.info("Starting OrderConsumer background worker...");
        orderConsumer.start();
    }
}
