-- System test 07: transactions in one session.
-- Run:    scripts/run.sh client --echo --file=test-scenarios/system/07-transaction.sql
-- Needs:  a server started with --seed. Restores every row it changes.
-- Exit:   0
--
-- The failed state after an error inside BEGIN is in 09-errors.sql; concurrent sessions,
-- crashes and shutdown are in manual/.

-- ABORT undoes an INSERT, an UPDATE and a DELETE on three tables, and the transaction sees its
-- own writes before that.
-- expect: BEGIN
BEGIN;

-- expect: 1 row affected (three times)
INSERT INTO customers VALUES (61, 'Customer 61', 1.5, 'A');
UPDATE orders SET amount = 0 WHERE order_id = 1;
DELETE FROM order_items WHERE item_id = 60;

-- expect: 1 row: Customer 61
SELECT name FROM customers WHERE customer_id = 61;

-- expect: 1 row: 0.0
SELECT amount FROM orders WHERE order_id = 1;

-- expect: 0 rows
SELECT * FROM order_items WHERE item_id = 60;

-- expect: ABORT
ABORT;

-- expect: 0 rows
SELECT * FROM customers WHERE customer_id = 61;

-- expect: 1 row: 11.0
SELECT amount FROM orders WHERE order_id = 1;

-- expect: 1 row: 60, 60, SKU-1060, Y, 8.5
SELECT * FROM order_items WHERE item_id = 60;

-- ABORT also restores an indexed column; the hash index on customers.name must follow.
BEGIN;
UPDATE customers SET name = 'Renamed' WHERE customer_id = 3;

-- expect: 1 row: 3
SELECT customer_id FROM customers WHERE name = 'Renamed';

ABORT;

-- expect: 0 rows
SELECT customer_id FROM customers WHERE name = 'Renamed';

-- expect: 1 row: 3
SELECT customer_id FROM customers WHERE name = 'Customer 3';

-- COMMIT keeps every change; BEGIN TRANSACTION is the same as BEGIN.
-- expect: BEGIN
BEGIN TRANSACTION;

-- expect: 1 row affected (twice)
UPDATE customers SET credit = 0 WHERE customer_id = 2;
UPDATE orders SET note = 'changed' WHERE order_id = 2;

-- expect: COMMIT
COMMIT;

-- expect: 1 row: 0.0
SELECT credit FROM customers WHERE customer_id = 2;

-- expect: 1 row: changed
SELECT note FROM orders WHERE order_id = 2;

-- A committed change is undone only by a new change. Restore in one transaction.
BEGIN;
UPDATE customers SET credit = 53.0 WHERE customer_id = 2;
UPDATE orders SET note = 'Order-2' WHERE order_id = 2;
COMMIT;

-- expect: 1 row: (Customer 2, 53.0, Order-2)
SELECT customers.name, customers.credit, orders.note FROM customers, orders
  WHERE customers.customer_id = orders.customer_id AND customers.customer_id = 2;

-- A transaction that only reads commits normally.
BEGIN;

-- expect: 60 rows
SELECT customer_id FROM customers;

-- expect: COMMIT
COMMIT;
