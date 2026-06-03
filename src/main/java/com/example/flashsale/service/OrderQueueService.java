package com.example.flashsale.service;

import com.example.flashsale.dto.SaleRequest;
import com.example.flashsale.entity.FlashSaleOrder;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class OrderQueueService {

    private static final Logger logger = LoggerFactory.getLogger(OrderQueueService.class);
    private final BlockingQueue<FlashSaleOrder> orderQueue = new LinkedBlockingQueue<>();

    @Autowired
    private OrderConsumer orderConsumer;

    public boolean addOrderRequest(SaleRequest request) {
        FlashSaleOrder order = new FlashSaleOrder(request.getUserId(), request.getItemId(), request.getQuantity());
        boolean offered = orderQueue.offer(order);
        if (!offered) {
            logger.warn("Order queue is full. Could not accept order for user {} and item {}.", request.getUserId(), request.getItemId());
        }
        return offered;
    }

    public BlockingQueue<FlashSaleOrder> getOrderQueue() {
        return orderQueue;
    }

    @PostConstruct
    private void startConsumer() {
        logger.info("Starting OrderConsumer background worker...");
        orderConsumer.start();
    }
}
