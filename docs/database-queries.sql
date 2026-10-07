-- MySQL 8.4: single restaurant, after schema.sql and optional seed.sql.
-- Read-only examples apart from session variables. Repository implementations
-- must use prepared parameters and obtain customer_id from authenticated context.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_as_ci;
SET time_zone = '+00:00';
SET @customer_id = 4;
SET @category_id = NULL;
SET @order_type = NULL; -- NULL for both types, or 'DINE_IN' / 'DELIVERY'.
SET @min_price = 0.00;
SET @max_price = 1000000.00;
SET @keyword = 'phở';
SET @order_id = 1001;

-- UC03 / UC05: case-insensitive substring search, filters, stable pagination.
-- For the unfiltered listing, omit the name/description LIKE predicate.
-- Here '%' and '_' inside keyword are LIKE wildcards; escape them for literal
-- keyword matching in the Repository and use an explicit LIKE ESCAPE clause.
SELECT f.id, f.name, f.description, f.price, f.image_url, f.stock_quantity,
       f.status, c.id AS category_id, c.name AS category_name
FROM foods f
JOIN categories c ON c.id = f.category_id
WHERE f.status = 'ACTIVE'
  AND (@category_id IS NULL OR f.category_id = @category_id)
  AND f.price BETWEEN @min_price AND @max_price
  AND (f.name LIKE CONCAT('%', @keyword, '%')
       OR f.description LIKE CONCAT('%', @keyword, '%'))
ORDER BY f.price, f.id
LIMIT 20 OFFSET 0;

-- UC09: include empty carts via LEFT JOIN. Cart totals use current prices.
SELECT c.id AS cart_id, ci.id AS cart_item_id, ci.food_id,
       f.name, f.status, f.stock_quantity, ci.quantity, f.price,
       ci.quantity * f.price AS current_line_total
FROM carts c
LEFT JOIN cart_items ci ON ci.cart_id = c.id
LEFT JOIN foods f ON f.id = ci.food_id
WHERE c.user_id = @customer_id
ORDER BY ci.id;

-- UC14: customer scope is always required, including requests with status filters.
-- Match the case-sensitive order_type column explicitly; session variables use
-- the connection collation and otherwise cause MySQL error 1267 when non-NULL.
SELECT o.id, o.order_type, o.table_number, o.status, ot.total_amount, o.payment_method, o.created_at
FROM orders o
JOIN order_totals ot ON ot.order_id = o.id
WHERE o.user_id = @customer_id
  AND (@order_type IS NULL OR o.order_type = CONVERT(@order_type USING utf8mb4) COLLATE utf8mb4_0900_as_cs)
ORDER BY o.created_at DESC, o.id DESC
LIMIT 20 OFFSET 0;

-- UC14: read stored snapshots, never recalculate historical price from foods.
SELECT o.id, o.order_type, o.table_number, o.status,
       o.recipient_name, o.shipping_phone, o.shipping_address,
       o.payment_method, ot.total_amount, oi.food_id, oi.food_name,
       oi.unit_price, oi.quantity, oi.line_total
FROM orders o
JOIN order_totals ot ON ot.order_id = o.id
JOIN order_items oi ON oi.order_id = o.id
WHERE o.id = @order_id AND o.user_id = @customer_id
ORDER BY oi.id;

-- UC18: only an EMPLOYEE-authorized Repository operation may use this scope.
SELECT o.id, o.user_id, o.order_type, o.table_number, o.recipient_name, o.shipping_phone,
       o.shipping_address, o.payment_method, o.status, ot.total_amount, o.created_at
FROM orders o
JOIN order_totals ot ON ot.order_id = o.id
WHERE o.status IN ('PENDING', 'CONFIRMED', 'PROCESSING', 'SHIPPING')
  AND (@order_type IS NULL OR o.order_type = CONVERT(@order_type USING utf8mb4) COLLATE utf8mb4_0900_as_cs)
ORDER BY o.created_at, o.id
LIMIT 20 OFFSET 0;

-- Fixture verification: no committed order should be empty. Expected: zero rows.
-- Totals have one source, order_items; there is no separately stored header total.
SELECT o.id
FROM orders o
LEFT JOIN order_items oi ON oi.order_id = o.id
GROUP BY o.id
HAVING COUNT(oi.id) = 0;

-- Demonstrate the historical snapshot price differing from the current menu.
SELECT oi.order_id, oi.food_id, oi.food_name,
       oi.unit_price AS ordered_price, f.price AS current_price, f.status
FROM order_items oi
JOIN foods f ON f.id = oi.food_id
WHERE oi.unit_price <> f.price OR f.status = 'INACTIVE'
ORDER BY oi.order_id, oi.id;

-- Inspect both modes. DELIVERY has no table, DINE_IN has no shipping snapshot.
SELECT o.id, o.order_type, o.table_number, o.recipient_name, o.shipping_phone,
       o.shipping_address, o.payment_method, o.status, ot.total_amount
FROM orders o
JOIN order_totals ot ON ot.order_id = o.id
ORDER BY o.order_type, o.id;

-- Expected sample counts: 8, 6, 18, 5, 6, 8, 16 (seven business tables).
SELECT 'users' AS table_name, COUNT(*) AS row_count FROM users
UNION ALL SELECT 'categories', COUNT(*) FROM categories
UNION ALL SELECT 'foods', COUNT(*) FROM foods
UNION ALL SELECT 'carts', COUNT(*) FROM carts
UNION ALL SELECT 'cart_items', COUNT(*) FROM cart_items
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'order_items', COUNT(*) FROM order_items;
