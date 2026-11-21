-- System test 04: subqueries in WHERE.
-- Run:    scripts/run.sh client --echo --file=test-scenarios/system/04-subquery.sql
-- Needs:  a server started with --seed. Read-only.
-- Exit:   0
--
-- Sample data: order i belongs to customer i with amount 10 + i; order item i belongs to
-- order i and is a gift ('Y') when i is a multiple of 3.

-- IN.
-- expect: 2 rows: (59, Customer 59), (60, Customer 60)
SELECT customer_id, name FROM customers
  WHERE customer_id IN (SELECT customer_id FROM orders WHERE amount > 68) ORDER BY customer_id;

-- NOT IN.
-- expect: 2 rows: 1, 2
SELECT customer_id FROM customers
  WHERE customer_id NOT IN (SELECT customer_id FROM orders WHERE order_id > 2);

-- Two IN subqueries combined with AND.
-- expect: 1 row: 60 (amount > 68 gives 59 and 60; only 60 is a multiple of 3)
SELECT customer_id FROM customers
  WHERE customer_id IN (SELECT customer_id FROM orders WHERE amount > 68)
    AND customer_id IN (SELECT order_id FROM order_items WHERE gift = 'Y');

-- Correlated EXISTS.
-- expect: 3 rows: 3, 6, 9
SELECT o.order_id FROM orders AS o
  WHERE EXISTS (SELECT * FROM order_items AS i WHERE i.order_id = o.order_id AND i.gift = 'Y')
    AND o.order_id < 10 ORDER BY o.order_id;

-- Correlated NOT EXISTS.
-- expect: 2 rows: 1, 2 (only their orders have amount <= 12)
SELECT customer_id FROM customers AS c
  WHERE NOT EXISTS (SELECT * FROM orders AS o WHERE o.customer_id = c.customer_id AND o.amount > 12);

-- Scalar subquery returning one row.
-- expect: 1 row: 3
SELECT order_id FROM orders WHERE amount = (SELECT amount FROM orders WHERE order_id = 3);

-- Scalar subquery returning no row compares as unknown.
-- expect: 0 rows
SELECT order_id FROM orders WHERE amount = (SELECT amount FROM orders WHERE order_id = 999);
