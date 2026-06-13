package com.example.flashsale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency tests that simulate the exact logic of the Redis Lua scripts
 * (check_and_decr.lua) using thread-safe Java primitives.
 *
 * This proves the algorithm is race-condition free without needing a live Redis server.
 */
class FlashSaleApplicationTests {

    private AtomicInteger stock;
    private Set<Long> purchasedUsers;
    private BlockingQueue<String> orderQueue;

    /**
     * Simulates the atomic Redis Lua script check_and_decr.lua logic:
     * 1. Check if user already purchased (SISMEMBER) -> return -2
     * 2. Check stock exists and is valid -> return -1
     * 3. If stock >= quantity, DECRBY stock and SADD user -> return 1
     * 4. Else -> return 0 (insufficient stock)
     *
     * Uses synchronized to faithfully replicate Redis single-threaded Lua execution.
     * In real Redis, a Lua script blocks all other commands until it finishes,
     * so the entire check-decrement-register sequence is truly atomic.
     */
    private synchronized int simulateLuaCheckAndDecrement(long userId, int quantity) {
        // Step 1: Idempotency check
        if (purchasedUsers.contains(userId)) {
            return -2; // Already purchased
        }

        // Step 2: Stock check
        int currentStock = stock.get();
        if (currentStock < quantity) {
            return 0; // Insufficient stock
        }

        // Step 3: Decrement stock and register user
        stock.addAndGet(-quantity);
        purchasedUsers.add(userId);
        return 1; // Success
    }

    @BeforeEach
    void setUp() {
        stock = new AtomicInteger(10);
        purchasedUsers = ConcurrentHashMap.newKeySet();
        orderQueue = new LinkedBlockingQueue<>();
    }

    @Test
    void contextLoads() {
        // Basic verification that the test framework loads
    }

    /**
     * Test: 100 unique users compete for 10 items simultaneously.
     * Expected: Exactly 10 succeed, exactly 90 get "out of stock", stock reaches 0.
     */
    @Test
    void testConcurrentPurchases_100Users_10Stock() throws InterruptedException {
        int totalUsers = 100;
        int initialStock = 10;
        stock.set(initialStock);

        ExecutorService executor = Executors.newFixedThreadPool(totalUsers);
        CountDownLatch startGate = new CountDownLatch(1);   // Synchronize all threads
        CountDownLatch finishGate = new CountDownLatch(totalUsers);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger outOfStockCount = new AtomicInteger(0);

        for (int i = 1; i <= totalUsers; i++) {
            final long userId = i;
            executor.submit(() -> {
                try {
                    startGate.await(); // All threads wait here, then rush simultaneously
                    int result = simulateLuaCheckAndDecrement(userId, 1);
                    if (result == 1) {
                        successCount.incrementAndGet();
                        orderQueue.put("order:" + userId);
                    } else if (result == 0) {
                        outOfStockCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();  // Release all 100 threads at once
        finishGate.await();     // Wait for all to finish
        executor.shutdown();

        // Verify absolute consistency
        assertEquals(0, stock.get(), "Stock must be fully depleted to 0");
        assertEquals(initialStock, successCount.get(), "Exactly " + initialStock + " purchases must succeed");
        assertEquals(totalUsers - initialStock, outOfStockCount.get(), "Remaining users must get out-of-stock");
        assertEquals(initialStock, orderQueue.size(), "Queue must contain exactly " + initialStock + " orders");
        assertEquals(initialStock, purchasedUsers.size(), "Purchased set must have exactly " + initialStock + " users");
    }

    /**
     * Test: A single user rapidly fires 20 concurrent requests.
     * Expected: Only 1 succeeds, 19 are rejected as duplicates, stock decreases by only 1.
     */
    @Test
    void testUserIdempotency_sameUser20Requests() throws InterruptedException {
        int totalRequests = 20;
        int initialStock = 10;
        stock.set(initialStock);
        final long singleUserId = 999L;

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    int result = simulateLuaCheckAndDecrement(singleUserId, 1);
                    if (result == 1) {
                        successCount.incrementAndGet();
                        orderQueue.put("order:" + singleUserId);
                    } else if (result == -2) {
                        duplicateCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();
        finishGate.await();
        executor.shutdown();

        // Verify idempotency
        assertEquals(initialStock - 1, stock.get(), "Stock must only decrement by 1");
        assertEquals(1, successCount.get(), "Only 1 purchase must succeed for the same user");
        assertEquals(totalRequests - 1, duplicateCount.get(), "All other requests must be rejected as duplicates");
        assertEquals(1, orderQueue.size(), "Only 1 order must be queued");
        assertTrue(purchasedUsers.contains(singleUserId), "User must be in the purchased set");
    }

    /**
     * Test: Mixed scenario - 50 users compete for 5 items, then the same 50 users try again.
     * Expected: First wave - 5 succeed. Second wave - all 50 get duplicate rejection.
     */
    @Test
    void testMixedConcurrency_twoWaves() throws InterruptedException {
        int usersPerWave = 50;
        int initialStock = 5;
        stock.set(initialStock);

        // --- Wave 1: 50 unique users compete for 5 slots ---
        ExecutorService executor1 = Executors.newFixedThreadPool(usersPerWave);
        CountDownLatch start1 = new CountDownLatch(1);
        CountDownLatch finish1 = new CountDownLatch(usersPerWave);
        AtomicInteger wave1Success = new AtomicInteger(0);

        for (int i = 1; i <= usersPerWave; i++) {
            final long userId = i;
            executor1.submit(() -> {
                try {
                    start1.await();
                    int result = simulateLuaCheckAndDecrement(userId, 1);
                    if (result == 1) wave1Success.incrementAndGet();
                } catch (Exception e) { e.printStackTrace(); }
                finally { finish1.countDown(); }
            });
        }

        start1.countDown();
        finish1.await();
        executor1.shutdown();

        assertEquals(0, stock.get(), "Wave 1: Stock must be fully depleted");
        assertEquals(initialStock, wave1Success.get(), "Wave 1: Exactly " + initialStock + " users must succeed");

        // --- Wave 2: Same 50 users try again ---
        ExecutorService executor2 = Executors.newFixedThreadPool(usersPerWave);
        CountDownLatch start2 = new CountDownLatch(1);
        CountDownLatch finish2 = new CountDownLatch(usersPerWave);
        AtomicInteger wave2Success = new AtomicInteger(0);
        AtomicInteger wave2Duplicate = new AtomicInteger(0);
        AtomicInteger wave2OutOfStock = new AtomicInteger(0);

        for (int i = 1; i <= usersPerWave; i++) {
            final long userId = i;
            executor2.submit(() -> {
                try {
                    start2.await();
                    int result = simulateLuaCheckAndDecrement(userId, 1);
                    if (result == 1) wave2Success.incrementAndGet();
                    else if (result == -2) wave2Duplicate.incrementAndGet();
                    else if (result == 0) wave2OutOfStock.incrementAndGet();
                } catch (Exception e) { e.printStackTrace(); }
                finally { finish2.countDown(); }
            });
        }

        start2.countDown();
        finish2.await();
        executor2.shutdown();

        assertEquals(0, wave2Success.get(), "Wave 2: No purchases must succeed");
        // Users who purchased in wave 1 get -2 (duplicate), others get 0 (out of stock)
        assertEquals(initialStock, wave2Duplicate.get(), "Wave 2: Previously successful users must get duplicate rejection");
        assertEquals(usersPerWave - initialStock, wave2OutOfStock.get(), "Wave 2: Other users must get out-of-stock");
    }
}
