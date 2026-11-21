-- System test 05: INSERT, UPDATE, DELETE, and NULL.
-- Run:    scripts/run.sh client --echo --file=test-scenarios/system/05-dml.sql
-- Needs:  a server started with --seed. Restores every row it changes.
-- Exit:   0
--
-- Each statement runs in auto-commit mode and commits on its own.

-- INSERT a row into each table, parent first; credit is NULL.
-- expect: 1 row affected (three times)
INSERT INTO customers VALUES (61, 'Customer 61', NULL, 'A');
INSERT INTO orders VALUES (61, 61, 71.0, NULL);
INSERT INTO order_items VALUES (61, 61, 'SKU-1061', 'N', 8.6);

-- expect: 1 row: 61, Customer 61, NULL, A
SELECT * FROM customers WHERE customer_id = 61;

-- expect: 1 row: Customer 61, 61, SKU-1061 (the new rows join like any other)
SELECT customers.name, orders.order_id, order_items.sku FROM customers, orders, order_items
  WHERE customers.customer_id = orders.customer_id AND orders.order_id = order_items.order_id
    AND customers.customer_id = 61;

-- NULL never compares equal, and NOT of an unknown is still unknown.
-- expect: 0 rows
SELECT customer_id FROM customers WHERE credit = NULL;

-- expect: 1 row: 60 (customer 61 has a NULL credit, so NOT credit > 1000 is unknown for it)
SELECT customer_id FROM customers WHERE NOT credit > 1000 AND customer_id > 59;

-- UPDATE with arithmetic on the old value.
-- expect: 2 rows affected
UPDATE orders SET amount = amount * 2 WHERE order_id = 59 OR order_id = 60;

-- expect: 2 rows: (59, 138.0), (60, 140.0)
SELECT order_id, amount FROM orders WHERE order_id >= 59 AND order_id <= 60;

-- Put the old values back.
-- expect: 2 rows affected
UPDATE orders SET amount = amount / 2 WHERE order_id = 59 OR order_id = 60;

-- expect: 2 rows: (59, 69.0), (60, 70.0)
SELECT order_id, amount FROM orders WHERE order_id >= 59 AND order_id <= 60;

-- UPDATE a NULL to a value.
-- expect: 1 row affected (twice)
UPDATE customers SET credit = 141.5 WHERE customer_id = 61;
UPDATE orders SET note = 'Order-61' WHERE order_id = 61;

-- expect: 1 row: 141.5
SELECT credit FROM customers WHERE customer_id = 61;

-- expect: 1 row: Order-61
SELECT note FROM orders WHERE order_id = 61;

-- UPDATE a value back to NULL.
-- expect: 1 row affected
UPDATE orders SET note = NULL WHERE order_id = 61;

-- expect: 1 row: NULL
SELECT note FROM orders WHERE order_id = 61;

-- UPDATE a text column; the hash index on customers.name must follow it.
-- expect: 1 row affected
UPDATE customers SET name = 'Renamed 61' WHERE customer_id = 61;

-- expect: 1 row: 61
SELECT customer_id FROM customers WHERE name = 'Renamed 61';

-- expect: 0 rows
SELECT customer_id FROM customers WHERE name = 'Customer 61';

-- Statements that match nothing.
-- expect: 0 rows affected (twice)
UPDATE customers SET credit = 0 WHERE customer_id = 999;
DELETE FROM orders WHERE order_id = 999;

-- DELETE the new rows, child first.
-- expect: 1 row affected (three times)
DELETE FROM order_items WHERE item_id = 61;
DELETE FROM orders WHERE order_id = 61;
DELETE FROM customers WHERE customer_id = 61;

-- expect: 0 rows, in all three tables
SELECT * FROM customers WHERE customer_id = 61;
SELECT * FROM orders WHERE order_id = 61;
SELECT * FROM order_items WHERE item_id = 61;

-- expect: n_r = 60 for every table again
\stats
