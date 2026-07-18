-- Real catalog imported from the local dev DB (dump of 2026-07-18),
-- superseding the V2 demo data. V2 stays frozen (Flyway checksums applied
-- migrations); this migration removes V2's rows and inserts the true catalog.
--
-- Safety properties (same philosophy as V2, one refinement):
--   * demo deletes match on (name, price) pairs — not name alone — so the
--     local DB's real 'Wireless Earbuds Pro' (59.99) is untouched while
--     V2's demo one (89.90) is removed
--   * inserts are guarded by NOT EXISTS on name -> no-ops on the local DB
--     where these products already exist (preserving their IDs, which local
--     orders/carts reference)
--   * no fixed IDs anywhere; categories resolved by name
--
-- Deliberately NOT imported: 'Low Stock Widget' (a local compensation-test
-- fixture, not catalog) and all runtime state (users, orders, carts,
-- partially-consumed stock counts — inventory here starts at full stock).

-- 1) Retire the V2 demo catalog (inventory first: FK points at products).
DELETE pi FROM product_inventory pi
JOIN products p ON pi.product_id = p.id
WHERE (p.name, p.price) IN (
  ('Wireless Earbuds Pro',    89.90),
  ('Mechanical Keyboard TKL', 129.00),
  ('4K Action Camera',        249.50),
  ('Canvas Sneakers',          49.90),
  ('Insulated Tumbler 600ml',  24.90),
  ('Air Fryer Compact',        79.00)
);

DELETE FROM products
WHERE (name, price) IN (
  ('Wireless Earbuds Pro',    89.90),
  ('Mechanical Keyboard TKL', 129.00),
  ('4K Action Camera',        249.50),
  ('Canvas Sneakers',          49.90),
  ('Insulated Tumbler 600ml',  24.90),
  ('Air Fryer Compact',        79.00)
);

-- 2) Categories ('Electronics'/'Fashion' already exist from V2 -> IGNORE
--    keeps the existing rows; 'Home & Living'/'Sports'/'Beauty' are new).
INSERT IGNORE INTO categories (name, description) VALUES
  ('Electronics',   'Updated: phones, laptops, and accessories'),
  ('Fashion',       'Clothing, shoes, and accessories'),
  ('Home & Living', 'Furniture, decor, and kitchen'),
  ('Sports',        'Equipment and activewear'),
  ('Beauty',        'Skincare, makeup, and personal care');

-- 3) Products from the local catalog.
INSERT INTO products (name, description, image_url, price, status, category_id, created_at)
SELECT * FROM (
  SELECT 'AirPods Max'           AS name, 'High-quality wireless headphones' AS description,
         'https://res.cloudinary.com/dlxamalb1/image/upload/v1779779789/airpodsmax_azb5bs.png' AS image_url,
         119.99 AS price, 'ACTIVE' AS status,
         (SELECT id FROM categories WHERE name = 'Electronics') AS category_id, NOW(6) AS created_at UNION ALL
  SELECT 'Wireless Earbuds Pro',  'Noise-cancelling, 30hr battery',   NULL,  59.99, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Electronics'),   NOW(6) UNION ALL
  SELECT 'Mechanical Keyboard',   'TKL, RGB, blue switches',          NULL,  89.99, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Electronics'),   NOW(6) UNION ALL
  SELECT 'USB-C Hub 7-in-1',      'HDMI, SD card, 3x USB-A',          NULL,  34.99, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Electronics'),   NOW(6) UNION ALL
  SELECT 'iPhone 17 Pro',         'Latest iPhone 17 Pro',
         'https://res.cloudinary.com/dlxamalb1/image/upload/v1779782343/flash-sale/products/vkt9sikxzxmkdt9tdo0u.png',
         999.99, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Electronics'),   NOW(6) UNION ALL
  SELECT 'Running Shoes',         'Lightweight mesh, size 39-45',     NULL,  75.00, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Fashion'),       NOW(6) UNION ALL
  SELECT 'Oversized Hoodie',      'Cotton blend, unisex',             NULL,  29.99, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Fashion'),       NOW(6) UNION ALL
  SELECT 'Minimalist Watch',      'Stainless steel, water resistant', NULL,  49.99, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Fashion'),       NOW(6) UNION ALL
  SELECT 'Air Fryer 4L',          '1500W, digital display',           NULL,  65.00, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Home & Living'), NOW(6) UNION ALL
  SELECT 'Bamboo Desk Organizer', '5 compartments, eco-friendly',     NULL,  18.99, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Home & Living'), NOW(6) UNION ALL
  SELECT 'Yoga Mat',              '6mm thick, non-slip',              NULL,  22.50, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Sports'),        NOW(6) UNION ALL
  SELECT 'Resistance Bands Set',  '5 levels, with carry bag',         NULL,  15.99, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Sports'),        NOW(6) UNION ALL
  SELECT 'Vitamin C Serum',       '20%, with hyaluronic acid',        NULL,  24.99, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Beauty'),        NOW(6) UNION ALL
  SELECT 'Lip Tint Set',          '6 shades, long lasting',           NULL,  12.99, 'ACTIVE', (SELECT id FROM categories WHERE name = 'Beauty'),        NOW(6)
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.name = seed.name);

-- 4) Full starting stock for every imported product. Local totals kept where
--    they existed (AirPods Max 50, iPhone 21); the rest default to 100.
--    INSERT IGNORE: on the local DB, existing inventory rows (with their
--    real consumed counts) win and are left alone.
INSERT IGNORE INTO product_inventory (product_id, available_stock, total_stock)
SELECT p.id, s.stock, s.stock
FROM products p
JOIN (
  SELECT 'AirPods Max'           AS name,  50 AS stock UNION ALL
  SELECT 'Wireless Earbuds Pro',           100 UNION ALL
  SELECT 'Mechanical Keyboard',            100 UNION ALL
  SELECT 'USB-C Hub 7-in-1',               100 UNION ALL
  SELECT 'iPhone 17 Pro',                   21 UNION ALL
  SELECT 'Running Shoes',                  100 UNION ALL
  SELECT 'Oversized Hoodie',               100 UNION ALL
  SELECT 'Minimalist Watch',               100 UNION ALL
  SELECT 'Air Fryer 4L',                   100 UNION ALL
  SELECT 'Bamboo Desk Organizer',          100 UNION ALL
  SELECT 'Yoga Mat',                       100 UNION ALL
  SELECT 'Resistance Bands Set',           100 UNION ALL
  SELECT 'Vitamin C Serum',                100 UNION ALL
  SELECT 'Lip Tint Set',                   100
) AS s ON s.name = p.name;
