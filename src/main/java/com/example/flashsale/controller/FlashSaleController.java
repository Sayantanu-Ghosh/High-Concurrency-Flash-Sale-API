package com.example.flashsale.controller;

import com.example.flashsale.dto.ApiResponse;
import com.example.flashsale.dto.SaleRequest;
import com.example.flashsale.service.InventoryService;
import com.example.flashsale.service.OrderQueueService;
import com.example.flashsale.service.RedisInventoryWarmupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/api/sale")
public class FlashSaleController {

    private static final Logger logger = LoggerFactory.getLogger(FlashSaleController.class);

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OrderQueueService orderQueueService;

    @Autowired
    private RedisInventoryWarmupService warmupService;

    @Autowired
    private com.example.flashsale.repository.FlashSaleItemRepository itemRepository;

    @Autowired
    private com.example.flashsale.repository.FlashSaleOrderRepository orderRepository;

    @Autowired
    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/buy")
    public ResponseEntity<ApiResponse<Void>> buy(@RequestBody SaleRequest request) {
        if (request.getUserId() == null || request.getItemId() == null || request.getQuantity() == null || request.getQuantity() <= 0) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid request data."));
        }

        // 1. Atomic check and decrement in Redis, including idempotency check
        int result = inventoryService.checkAndDecrementStock(request.getItemId(), request.getUserId(), request.getQuantity());

        if (result == -2) {
            // Idempotency constraint violated
            return ResponseEntity.status(HttpStatus.CONFLICT)
                                 .body(ApiResponse.error("Purchase failed. You have already ordered this item. Limit: 1 per user."));
        }

        if (result == 0) {
            // Out of stock
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                 .body(ApiResponse.error("Stock depleted for item " + request.getItemId()));
        }

        if (result == -1) {
            // Item does not exist or invalid stock
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                 .body(ApiResponse.error("Item does not exist or sale is not active. Item ID: " + request.getItemId()));
        }

        // 2. Push to asynchronous queue
        boolean accepted = orderQueueService.addOrderRequest(request);
        if (!accepted) {
            // If the queue is full, we must trigger a compensating transaction (refund stock/purchased user)
            logger.error("Order queue is full. Initiating compensating rollback transaction for user {} and item {}.", request.getUserId(), request.getItemId());
            inventoryService.refundStock(request.getItemId(), request.getUserId(), request.getQuantity());
            
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                 .body(ApiResponse.error("System is overloaded. Please try again later."));
        }

        // 3. Return "Accepted" immediately
        logger.info("Request accepted for user {} and item {}. Order will be processed asynchronously.", request.getUserId(), request.getItemId());
        return ResponseEntity.accepted().body(ApiResponse.success("Request accepted. Order is being processed.", null));
    }

    @PostMapping("/admin/warmup")
    public ResponseEntity<ApiResponse<Void>> warmup() {
        warmupService.warmupAllItems();
        return ResponseEntity.ok(ApiResponse.success("Successfully pre-heated Redis inventory cache from database.", null));
    }

    @PostMapping("/admin/reset")
    public ResponseEntity<ApiResponse<Void>> resetAll() {
        try {
            // Delete all orders
            orderRepository.deleteAll();
            // Warm up cache
            warmupService.warmupAllItems();
            logger.info("Admin reset triggered: all orders deleted and inventory cache refreshed.");
            return ResponseEntity.ok(ApiResponse.success("Database orders cleared and Redis cache warmed up successfully.", null));
        } catch (Exception e) {
            logger.error("Error resetting system", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body(ApiResponse.error("Failed to reset system: " + e.getMessage()));
        }
    }

    @GetMapping("/admin/status")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getStatus() {
        try {
            java.util.List<com.example.flashsale.entity.FlashSaleItem> items = itemRepository.findAll();
            java.util.List<java.util.Map<String, Object>> itemStatusList = new java.util.ArrayList<>();
            
            for (com.example.flashsale.entity.FlashSaleItem item : items) {
                String redisKey = "inventory:" + item.getItemId();
                Object redisStockVal = redisTemplate.opsForValue().get(redisKey);
                
                java.util.Map<String, Object> itemMap = new java.util.HashMap<>();
                itemMap.put("itemId", item.getItemId());
                itemMap.put("itemName", item.getItemName());
                itemMap.put("dbStock", item.getTotalStock());
                itemMap.put("redisStock", redisStockVal != null ? redisStockVal.toString() : "N/A");
                itemStatusList.add(itemMap);
            }
            
            Long queueSize = redisTemplate.opsForList().size("flash_sale:order_queue");
            long totalOrders = orderRepository.count();
            
            java.util.Map<String, Object> statusData = new java.util.HashMap<>();
            statusData.put("items", itemStatusList);
            statusData.put("queueSize", queueSize != null ? queueSize : 0);
            statusData.put("totalOrdersInDb", totalOrders);
            
            return ResponseEntity.ok(ApiResponse.success("System status retrieved successfully.", statusData));
        } catch (Exception e) {
            logger.error("Error retrieving system status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body(ApiResponse.error("Failed to retrieve system status: " + e.getMessage()));
        }
    }
}
