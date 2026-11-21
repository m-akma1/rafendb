-- Manual system test m5: a dropped client and a graceful shutdown both abort open transactions.
-- MANUAL: do not run with --file. Follow the steps in the server terminal and two client
-- terminals, A and B.
--
-- Setup:
--   [server] scripts/run.sh reset-data && scripts/run.sh server --seed
--   [A]      scripts/run.sh client
--   [B]      scripts/run.sh client
--
-- Changes nothing that survives.

-- Part 1: a client that leaves inside a transaction has it aborted, and its locks released.

-- [A] expect: BEGIN, then 1 row affected (three times)
BEGIN;
INSERT INTO customers VALUES (61, 'Customer 61', 1.5, 'A');
UPDATE customers SET credit = 0 WHERE customer_id = 1;
DELETE FROM order_items WHERE item_id = 1;

-- [B] expect: no result yet; B waits for A's lock on customers
SELECT customer_id, credit FROM customers WHERE customer_id = 1 OR customer_id = 61;

-- [A] quit with \q (or Ctrl+D, or close the terminal)
-- expect: [server] prints "[Session #n] disconnected (open transaction aborted)", and at once
--         B prints 1 row: (1, 51.5); customer 61 was never committed
\q

-- [B] expect: 1 row: 1, 1, SKU-1001, N, 2.6 (A's DELETE was undone)
SELECT * FROM order_items WHERE item_id = 1;

-- Part 2: \shutdown aborts open transactions, takes a checkpoint, and stops the server.

-- [A] reconnect: scripts/run.sh client
-- expect: BEGIN, then 1 row affected
BEGIN;
UPDATE customers SET credit = 333 WHERE customer_id = 12;

-- [B] answer y to "Shut down the server for all sessions? [y/N]"
-- expect: B prints "Server is shutting down."; A prints "Server is shutting down." and
--         "Your open transaction was aborted."; the server prints, in order,
--         "Shutting down (requested by session #n).", "[Session #n] disconnected
--         (open transaction aborted)", "Server stopped.", "Checkpoint taken.", "Engine closed."
\shutdown

-- [server] restart on the same data, without --seed:
--   scripts/run.sh server
-- expect: "Data directory in data, not seeded."

-- [A] reconnect: \connect
-- expect: 1 row: (12, 68.0); A's 333 was never committed
SELECT customer_id, credit FROM customers WHERE customer_id = 12;

-- expect: n_r = 60 for every table
\stats
