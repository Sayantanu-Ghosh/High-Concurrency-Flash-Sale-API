-- check_and_decr.lua
-- Atomically checks stock and decrements if available.
--
-- Keys:
-- KEYS[1] - The key for the inventory item (e.g., "inventory:1")
--
-- Args:
-- ARGV[1] - The requested quantity to purchase
--
-- Returns:
-- 1: Success, stock was sufficient and has been decremented.
-- 0: Failure, insufficient stock.
-- -1: Failure, item does not exist or stock is negative (invalid state).

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
    return 1 -- Success
else
    return 0 -- Insufficient stock
end
