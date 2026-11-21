-- Manual system test m1: strict two-phase locking between two sessions.
-- MANUAL: do not run with --file. Follow the steps in two client terminals, A and B.
--
-- Setup:
--   [server] scripts/run.sh reset-data && scripts/run.sh server --seed     (default --protocol=lock)
--   [A]      scripts/run.sh client
--   [B]      scripts/run.sh client
--
-- Locks are taken per table: shared to read, exclusive to write, and held until COMMIT or ABORT.
-- A statement that waits for a lock prints nothing until the lock is granted.
-- Restores every row it changes.

-- Part 1: a writer blocks readers of the same table, even of other rows.

-- [A] expect: BEGIN, then 1 row affected
BEGIN;
UPDATE customers SET credit = 1000 WHERE customer_id = 1;

-- [B] expect: no result yet; B waits for A's lock on customers (customer 5 is another row)
SELECT credit FROM customers WHERE customer_id = 5;

-- [A] expect: 1000.0 (A sees its own write; B is still waiting)
SELECT credit FROM customers WHERE customer_id = 1;

-- [A] expect: COMMIT, and at once B prints 57.5
COMMIT;

-- [B] expect: 1000.0 (the committed value)
SELECT credit FROM customers WHERE customer_id = 1;

-- [B] restore. expect: 1 row affected
UPDATE customers SET credit = 51.5 WHERE customer_id = 1;

-- Part 2: a writer blocks writers, and ABORT releases the lock and undoes the change.

-- [A] expect: BEGIN, then 1 row affected
BEGIN;
DELETE FROM order_items WHERE item_id = 1;

-- [B] expect: no result yet; B waits for A's lock on order_items
UPDATE order_items SET price = 0 WHERE item_id = 2;

-- [A] expect: ABORT, and at once B prints 1 row affected
ABORT;

-- [B] expect: 2 rows: item 1 is back (price 2.6), item 2 has price 0.0
SELECT * FROM order_items WHERE item_id <= 2;

-- [B] restore. expect: 1 row affected
UPDATE order_items SET price = 2.7 WHERE item_id = 2;

-- Part 3: readers share a table; a reader that wants to write waits for the other readers.

-- [A] expect: BEGIN, then 1 row: 1
BEGIN;
SELECT order_id FROM orders WHERE order_id = 1;

-- [B] expect: BEGIN, then 1 row: 2, at once (both hold shared locks)
BEGIN;
SELECT order_id FROM orders WHERE order_id = 2;

-- [B] expect: no result yet; B needs an exclusive lock and A still holds a shared one
UPDATE orders SET note = 'x' WHERE order_id = 2;

-- [A] expect: COMMIT, and at once B prints 1 row affected
COMMIT;

-- [B] expect: ABORT; order 2 keeps its note Order-2
ABORT;
SELECT note FROM orders WHERE order_id = 2;
