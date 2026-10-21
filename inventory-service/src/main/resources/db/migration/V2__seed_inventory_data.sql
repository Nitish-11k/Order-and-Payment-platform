-- V2__seed_inventory_data.sql
-- Initial catalog and inventory seed data

INSERT INTO inventory (sku, product_name, available_quantity, reserved_quantity, version)
VALUES 
('SKU-MACBOOK-AIR', 'Apple MacBook Air M3 13"', 50, 0, 0),
('SKU-USB-C-ADAPTER', 'Multiport USB-C Adapter 4K', 150, 0, 0),
('SKU-MECH-KEYBOARD', 'Mechanical Gaming Keyboard RGB', 30, 0, 0),
('SKU-WIRELESS-MOUSE', 'Ergonomic Wireless Mouse', 75, 0, 0),
('SKU-LAST-ITEM-TEST', 'Ultra Rare Collector Mug (Concurrency Test)', 1, 0, 0);
