-- This script is executed on startup by spring.sql.init.mode=always

-- Drop existing tables to start fresh
DROP TABLE IF EXISTS flash_sale_orders;
DROP TABLE IF EXISTS flash_sale_items;
DROP TABLE IF EXISTS users;

-- Users table
CREATE TABLE users (
    user_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Flash sale items table
CREATE TABLE flash_sale_items (
    item_id BIGSERIAL PRIMARY KEY,
    item_name VARCHAR(255) NOT NULL,
    total_stock INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Flash sale orders table
CREATE TABLE flash_sale_orders (
    order_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    order_time TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_item FOREIGN KEY (item_id) REFERENCES flash_sale_items(item_id),
    CONSTRAINT unique_user_item UNIQUE (user_id, item_id)
);

-- Indexes for performance
CREATE INDEX idx_orders_user_id ON flash_sale_orders(user_id);
CREATE INDEX idx_orders_item_id ON flash_sale_orders(item_id);
CREATE INDEX idx_orders_user_item ON flash_sale_orders(user_id, item_id);

-- Insert 10,000 test users for simulation
INSERT INTO users (user_id, username)
SELECT g, 'user_' || g
FROM generate_series(1, 10000) g;

-- Update the sequence
SELECT setval('users_user_id_seq', 10000);

-- Insert sample items
INSERT INTO flash_sale_items (item_id, item_name, total_stock)
VALUES 
(1, 'SuperWidget', 100), 
(2, 'MegaGadget', 50);

-- Update item sequence
SELECT setval('flash_sale_items_item_id_seq', 2);

