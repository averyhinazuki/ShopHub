-- Seed catalog: demo categories, products, and stock, so every fresh
-- environment (a rebuilt EC2 box, a teammate's laptop, CI) is born with a
-- browsable shop instead of an empty one.
--
-- Written to be safe on databases that ALREADY have data (e.g. the local dev
-- DB, where Flyway will also run this once):
--   * categories are keyed by their unique name       -> INSERT IGNORE
--   * products have no natural unique key             -> guarded by NOT EXISTS on name
--   * inventory is unique per product                 -> INSERT IGNORE
-- No fixed IDs anywhere: AUTO_INCREMENT assigns them, lookups go by name.
--
-- Flyway rule worth remembering: once this file has been applied anywhere,
-- it is FROZEN (Flyway records its checksum and fails if it changes).
-- Catalog additions later go in V3__, not by editing this file.

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

-- Stock rows: flash-sale checkout decrements available_stock, so a product
-- without an inventory row can be browsed but never bought.
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
