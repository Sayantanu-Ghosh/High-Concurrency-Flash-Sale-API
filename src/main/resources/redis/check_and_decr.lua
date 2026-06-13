-- check_and_decr.lua
-- Atomically checks user idempotency, verifies stock, and decrements if available.
--
-- Keys:
-- KEYS[1] - The key for the inventory item (e.g., "inventory:1")
-- KEYS[2] - The key for the set of users who purchased this item (e.g., "item:purchased:1")
--
-- Args:
-- ARGV[1] - The requested quantity to purchase
-- ARGV[2] - The user ID placing the purchase
--
-- Returns:
-- 1: Success, stock was sufficient, user registered in purchased set, and stock decremented.
-- 0: Failure, insufficient stock.
-- -1: Failure, item does not exist or stock is negative (invalid state).
-- -2: Failure, user has already purchased this item (idempotency violation).

-- 1. Check if user already purchased the item
if redis.call('sismember', KEYS[2], ARGV[2]) == 1 then
    return -2
end

-- 2. Check stock
local stock = redis.call('get', KEYS[1])
if not stock then
    return -1 -- Item does not exist
end

local stock_val = tonumber(stock)
local requested_qty = tonumber(ARGV[1])

if stock_val < 0 then
    return -1 -- Invalid stock state
end

if stock_val >= requested_qty then
    redis.call('decrby', KEYS[1], requested_qty)
    redis.call('sadd', KEYS[2], ARGV[2]) -- Mark user as purchased
    return 1 -- Success
else
    return 0 -- Insufficient stock
end
