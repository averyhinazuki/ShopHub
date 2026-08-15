-- Indexes for the two ways `orders` is read. Every other relationship column in
-- the schema got an index from Hibernate because it saw a mapped association;
-- `orders` declares only PRIMARY KEY (id) because Order.userId is a plain Long
-- rather than a @ManyToOne User, so there was no association to see.

-- Order history: OrderService.getMyOrders -> findByUserId, on every page load of
-- /orders. Without this it is a full table scan of every order ever placed —
-- invisible at 200 rows, half a million rows read to return six at 500k.
ALTER TABLE `orders` ADD KEY `idx_orders_user` (`user_id`);

-- The expiry job: OrderExpiryScheduler runs every 60 seconds forever and issues
--   SELECT o.id FROM orders WHERE status = 'PENDING' AND created_at < ? LIMIT 100
-- Equality on status, range on created_at, so (status, created_at) in that order
-- turns the scan into a range seek. LIMIT 100 does not rescue the unindexed case:
-- in steady state there are usually *zero* expired orders, so it scans every row,
-- finds nothing, and repeats a minute later — growing linearly with total orders
-- ever placed, on a timer, regardless of traffic.
ALTER TABLE `orders` ADD KEY `idx_orders_status_created` (`status`, `created_at`);

-- Deliberately NOT added here: FOREIGN KEY (user_id) REFERENCES users(id).
-- It needs existing rows proven clean first and it constrains user deletion, so
-- it is a separate judgement call. The two indexes above are uncontroversial and
-- are what matter at scale.
