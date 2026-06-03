package com.example.flashsale.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class InventoryService {

    private static final Logger logger = LoggerFactory.getLogger(InventoryService.class);
    private static final String INVENTORY_KEY_PREFIX = "inventory:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedisScript<Long> checkAndDecrementScript;

    /**
     * Atomically checks stock and decrements it using a Lua script.
     *
     * @param itemId The ID of the item.
     * @param quantity The quantity requested.
     * @return true if stock was sufficient and decremented, false otherwise.
     */
    public boolean checkAndDecrementStock(Long itemId, Integer quantity) {
        try {
            String key = INVENTORY_KEY_PREFIX + itemId;
            // Execute the Lua script
            Long result = redisTemplate.execute(
                checkAndDecrementScript,
                Collections.singletonList(key),
                quantity.toString()
            );

            if (result == null) {
                logger.error("Lua script execution returned null for item ID: {}", itemId);
                return false;
            }

            // 1: Success, 0: Insufficient stock, -1: Item does not exist or invalid state
            return result == 1L;

        } catch (Exception e) {
            logger.error("Error executing Redis Lua script for item ID: {}", itemId, e);
            // Fail-safe: if Redis is down or script fails, do not confirm the sale.
            return false;
        }
    }
}
