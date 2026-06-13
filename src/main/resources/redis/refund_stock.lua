-- refund_stock.lua
-- Atomically refunds stock and removes the user from the purchased set in Redis.
--
-- Keys:
-- KEYS[1] - The key for the inventory item (e.g., "inventory:1")
-- KEYS[2] - The key for the set of users who purchased this item (e.g., "item:purchased:1")
--
-- Args:
-- ARGV[1] - The quantity to refund
-- ARGV[2] - The user ID
--
-- Returns:
-- 1: Success, stock refunded and user removed from the set.
-- 0: Stock refunded, but user was not found in the set.
-- -1: Stock key does not exist.

local stock = redis.call('get', KEYS[1])
if not stock then
    return -1
end

-- Revert stock decrement
redis.call('incrby', KEYS[1], tonumber(ARGV[1]))

-- Revert purchase flag (remove user from the set)
local removed = redis.call('srem', KEYS[2], ARGV[2])

return removed
