-- Seed catalog (demo categories, products, stock) so fresh environments start
-- browsable. Idempotent on databases that already have data: categories and
-- inventory via INSERT IGNORE, products guarded by NOT EXISTS on name. No fixed
-- IDs — AUTO_INCREMENT assigns them, lookups go by name.
--
-- This file is frozen once applied (Flyway checksum); later catalog changes go
-- in a new migration, not here.

INSERT IGNORE INTO categories (name, description) VALUES
  ('Electronics', 'Gadgets and devices'),
  ('Fashion',     'Apparel and accessories'),
  ('Home',        'Household and kitchen');

INSERT INTO products (name, description, price, status, category_id, created_at)
SELECT * FROM (
  SELECT 'Wireless Earbuds Pro'      AS name, 'Noise-cancelling, 24h battery'          AS description,  89.90 AS price, 'ACTIVE' AS status, (SELECT id FROM categories WHERE name = 'Electronics') AS category_id, NOW(6) AS created_at UNION ALL
  SELECT 'Mechanical Keyboard TKL',         'Hot-swappable switches, RGB',              129.00,          'ACTIVE',      (SELECT id FROM categories WHERE name = 'Electronics'),                    NOW(6) UNION ALL
  SELECT '4K Action Camera',                'Waterproof to 10m, image stabilization',   249.50,          'ACTIVE',      (SELECT id FROM categories WHERE name = 'Electronics'),                    NOW(6) UNION ALL
  SELECT 'Canvas Sneakers',                 'Classic low-top, unisex sizing',            49.90,          'ACTIVE',      (SELECT id FROM categories WHERE name = 'Fashion'),                        NOW(6) UNION ALL
  SELECT 'Insulated Tumbler 600ml',         'Keeps drinks cold 12h / hot 6h',            24.90,          'ACTIVE',      (SELECT id FROM categories WHERE name = 'Home'),                           NOW(6) UNION ALL
  SELECT 'Air Fryer Compact',               '3.5L, dishwasher-safe basket',              79.00,          'ACTIVE',      (SELECT id FROM categories WHERE name = 'Home'),                           NOW(6)
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.name = seed.name);

-- Stock rows — a product without inventory can be browsed but never bought.
INSERT IGNORE INTO product_inventory (product_id, available_stock, total_stock)
SELECT p.id, s.stock, s.stock
FROM products p
JOIN (
  SELECT 'Wireless Earbuds Pro'      AS name, 200 AS stock UNION ALL
  SELECT 'Mechanical Keyboard TKL',         150 UNION ALL
  SELECT '4K Action Camera',                 80 UNION ALL
  SELECT 'Canvas Sneakers',                 300 UNION ALL
  SELECT 'Insulated Tumbler 600ml',         500 UNION ALL
  SELECT 'Air Fryer Compact',               120
) AS s ON s.name = p.name;
