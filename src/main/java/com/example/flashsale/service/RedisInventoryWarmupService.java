package com.example.flashsale.service;

import com.example.flashsale.entity.FlashSaleItem;
import com.example.flashsale.repository.FlashSaleItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RedisInventoryWarmupService implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(RedisInventoryWarmupService.class);
    private static final String INVENTORY_KEY_PREFIX = "inventory:";
    private static final String PURCHASED_KEY_PREFIX = "item:purchased:";

    @Autowired
    private FlashSaleItemRepository itemRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public void run(String... args) throws Exception {
        warmupAllItems();
    }

    /**
     * Warms up the inventory of all items from the database into Redis.
     */
    public void warmupAllItems() {
        logger.info("Starting Redis inventory cache pre-heating...");
        try {
            List<FlashSaleItem> items = itemRepository.findAll();
            for (FlashSaleItem item : items) {
                warmupItem(item.getItemId(), item.getTotalStock());
            }
            logger.info("Successfully pre-heated {} items in Redis.", items.size());
        } catch (Exception e) {
            logger.error("Error warming up Redis inventory cache from database", e);
        }
    }

    /**
     * Warms up a specific item's stock in Redis and clears its purchase history.
     */
    public void warmupItem(Long itemId, Integer stock) {
        String key = INVENTORY_KEY_PREFIX + itemId;
        redisTemplate.opsForValue().set(key, String.valueOf(stock));
        
        // Also clear the purchased users set so they can participate in the fresh sale
        clearPurchasedUsers(itemId);
        
        logger.info("Pre-heated item {} with stock {} in Redis.", itemId, stock);
    }

    /**
     * Clears the purchased users set for the item in Redis.
     */
    public void clearPurchasedUsers(Long itemId) {
        String key = PURCHASED_KEY_PREFIX + itemId;
        redisTemplate.delete(key);
        logger.info("Cleared purchased users set for item {}.", itemId);
    }
}
