package com.example.flashsale.controller;

import com.example.flashsale.dto.ApiResponse;
import com.example.flashsale.dto.SaleRequest;
import com.example.flashsale.service.InventoryService;
import com.example.flashsale.service.OrderQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sale")
public class FlashSaleController {

    private static final Logger logger = LoggerFactory.getLogger(FlashSaleController.class);

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OrderQueueService orderQueueService;

    @PostMapping("/buy")
    public ResponseEntity<ApiResponse<Void>> buy(@RequestBody SaleRequest request) {
        if (request.getUserId() == null || request.getItemId() == null || request.getQuantity() == null || request.getQuantity() <= 0) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid request data."));
        }

        // 1. Atomic check and decrement in Redis
        boolean stockAvailable = inventoryService.checkAndDecrementStock(request.getItemId(), request.getQuantity());

        if (!stockAvailable) {
            // Using 429 Too Many Requests to indicate a temporary high-traffic condition (out of stock)
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                 .body(ApiResponse.error("Stock depleted for item " + request.getItemId()));
        }

        // 2. Push to asynchronous queue
        boolean accepted = orderQueueService.addOrderRequest(request);
        if (!accepted) {
            // This is a critical failure state. The queue is full, meaning the backend cannot keep up.
            // We should ideally refund the stock in Redis here, but that adds complexity.
            // For now, we return a service unavailable error.
            logger.error("Order queue is full. Failed to accept order for user {} and item {}.", request.getUserId(), request.getItemId());
            // TODO: Implement a compensating transaction to refund the stock in Redis.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                 .body(ApiResponse.error("System is overloaded. Please try again later."));
        }

        // 3. Return "Accepted" immediately
        logger.info("Request accepted for user {} and item {}. Order will be processed asynchronously.", request.getUserId(), request.getItemId());
        return ResponseEntity.accepted().body(ApiResponse.success("Request accepted. Order is being processed.", null));
    }
}
