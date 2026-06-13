package com.example.flashsale.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InventoryService {

    private static final Logger logger = LoggerFactory.getLogger(InventoryService.class);
    private static final String INVENTORY_KEY_PREFIX = "inventory:";
    private static final String PURCHASED_KEY_PREFIX = "item:purchased:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedisScript<Long> checkAndDecrementScript;

    @Autowired
    private RedisScript<Long> refundStockScript;

    /**
     * Atomically checks stock and decrements it, and registers the user purchase using a Lua script.
     *
     * @param itemId The ID of the item.
     * @param userId The ID of the user.
     * @param quantity The quantity requested.
     * @return Result code from Lua script:
     *         1: Success, stock decremented, user purchase registered.
     *         0: Insufficient stock.
     *         -1: Item inventory not initialized/invalid.
     *         -2: User has already purchased this item (idempotency check).
     */
    public int checkAndDecrementStock(Long itemId, Long userId, Integer quantity) {
        try {
            String inventoryKey = INVENTORY_KEY_PREFIX + itemId;
            String purchasedKey = PURCHASED_KEY_PREFIX + itemId;

            // Execute the Lua script
            Long result = redisTemplate.execute(
                checkAndDecrementScript,
                List.of(inventoryKey, purchasedKey),
                quantity.toString(),
                userId.toString()
            );

            if (result == null) {
                logger.error("Lua script execution returned null for item ID: {} and user ID: {}", itemId, userId);
                return -1;
            }

            return result.intValue();

        } catch (Exception e) {
            logger.error("Error executing Redis Lua script checkAndDecrement for item ID: {} and user ID: {}", itemId, userId, e);
            // Fail-safe: if Redis is down or script fails, return invalid/error state.
            return -1;
        }
    }

    /**
     * Atomically refunds stock and removes the user from the purchased set in Redis.
     * Used for compensating transactions when downstream database writes fail.
     */
    public void refundStock(Long itemId, Long userId, Integer quantity) {
        try {
            String inventoryKey = INVENTORY_KEY_PREFIX + itemId;
            String purchasedKey = PURCHASED_KEY_PREFIX + itemId;

            Long result = redisTemplate.execute(
                refundStockScript,
                List.of(inventoryKey, purchasedKey),
                quantity.toString(),
                userId.toString()
            );

            if (result != null && result >= 0) {
                logger.info("Successfully refunded stock for item {} and user {}. Quantity: {}, Lua result: {}", 
                    itemId, userId, quantity, result);
            } else {
                logger.warn("Refund execution completed but stock key was missing or user was not registered. Item: {}, User: {}", itemId, userId);
            }
        } catch (Exception e) {
            logger.error("Error executing stock refund for item ID: {} and user ID: {}", itemId, userId, e);
        }
    }
}
