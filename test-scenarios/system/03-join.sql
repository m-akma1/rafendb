-- System test 03: joins.
-- Run:    scripts/run.sh client --echo --file=test-scenarios/system/03-join.sql
-- Needs:  a server started with --seed. Read-only.
-- Exit:   0
--
-- Sample data: order i belongs to customer i; order item i belongs to order i.
-- After a join, a column name found in more than one table is shown qualified.

-- Comma join with the join condition in WHERE.
-- expect: 2 rows: (Customer 1, 1), (Customer 2, 2)
SELECT customers.name, orders.order_id FROM customers, orders
  WHERE customers.customer_id = orders.customer_id AND customers.customer_id <= 2;

-- JOIN ... ON with aliases; customer_id appears as o.customer_id and c.customer_id.
-- expect: 1 row: order 5, amount 15.0, Order-5, customer 5, Customer 5, 57.5, I
SELECT * FROM orders AS o JOIN customers AS c ON o.customer_id = c.customer_id WHERE o.order_id = 5;

-- NATURAL JOIN on the shared customer_id column, which appears once.
-- expect: columns customer_id, name, credit, status, order_id, amount, note; 2 rows: orders 59, 60
SELECT * FROM customers NATURAL JOIN orders WHERE amount > 68;

-- Three tables.
-- expect: 2 rows: (Customer 1, 1, SKU-1001), (Customer 2, 2, SKU-1002)
SELECT customers.name, orders.order_id, order_items.sku FROM customers, orders, order_items
  WHERE customers.customer_id = orders.customer_id AND orders.order_id = order_items.order_id
    AND customers.customer_id <= 2;

-- Three tables with JOIN ... ON, filtered on the last one, ordered.
-- expect: 3 rows, gift items only, highest price first: (Customer 60, 8.5), (Customer 57, 8.200001),
--         (Customer 54, 7.9); FLOAT is single precision, and the seed computes 2.5 + 57 * 0.1 in it
SELECT customers.name, order_items.price FROM customers
  JOIN orders ON customers.customer_id = orders.customer_id
  JOIN order_items ON orders.order_id = order_items.order_id
  WHERE order_items.gift = 'Y' ORDER BY order_items.price DESC LIMIT 3;

-- Self-join through two aliases of the same table.
-- expect: 2 rows: (2, 1), (3, 2)
SELECT a.customer_id, b.customer_id FROM customers AS a, customers AS b
  WHERE a.customer_id = b.customer_id + 1 AND a.customer_id < 4;

-- Cartesian product restricted on each side separately.
-- expect: 2 rows: customer 1 paired with orders 1 and 2
SELECT * FROM customers, orders WHERE customers.customer_id = 1 AND orders.order_id <= 2;

-- A join no row satisfies.
-- expect: 0 rows
SELECT * FROM customers JOIN orders ON customers.customer_id = orders.order_id WHERE orders.amount > 1000;

-- The plan pushes the filter on orders below the join.
-- expect: BLOCK_NESTED_LOOP over TABLE SCAN customers and SELECTION (amount > 60) over TABLE SCAN orders
EXPLAIN SELECT * FROM customers NATURAL JOIN orders WHERE amount > 60;
