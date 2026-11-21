-- Manual system test m2: deadlock detection under locking.
-- MANUAL: do not run with --file. Follow the steps in two client terminals, A and B.
--
-- Setup:
--   [server] scripts/run.sh reset-data && scripts/run.sh server --seed     (default --protocol=lock)
--   [A]      scripts/run.sh client
--   [B]      scripts/run.sh client
--
-- Locks are per table, so the cycle needs two tables: A holds customers and wants orders,
-- B holds orders and wants customers. Changes nothing that survives.

-- [A] expect: BEGIN, then 1 row affected
BEGIN;
UPDATE customers SET credit = 1 WHERE customer_id = 2;

-- [B] expect: BEGIN, then 1 row affected
BEGIN;
UPDATE orders SET amount = 1 WHERE order_id = 2;

-- [A] expect: no result yet; A waits for B's lock on orders
UPDATE orders SET amount = 3 WHERE order_id = 3;

-- [B] closes the cycle. The deadlock is detected at once and one of the two is the victim.
-- expect, either:
--   (a) B is the victim: B prints "Runtime error at Query Processor: T<n> has been aborted",
--       its prompt turns to rafendb!>, and A at once prints 1 row affected; or
--   (b) A is the victim: A prints "Runtime error at Query Processor: T<n> cannot continue;
--       rollback is incomplete, retry abort" (its rollback is still finishing when it wakes up),
--       its prompt turns to rafendb!>, and B at once prints 1 row affected.
UPDATE customers SET credit = 2 WHERE customer_id = 2;

-- [the victim] expect: error (the transaction has failed), then ABORT
SELECT credit FROM customers WHERE customer_id = 2;
ABORT;

-- [the survivor] the victim's changes are already undone.
-- expect: (a) A survived: credit 1.0 (A's write); (b) B survived: credit 2.0 (B's write)
SELECT credit FROM customers WHERE customer_id = 2;

-- [the survivor] expect: ABORT
ABORT;

-- [A] expect: credit 53.0; orders 2 and 3 have amounts 12.0 and 13.0
SELECT credit FROM customers WHERE customer_id = 2;
SELECT order_id, amount FROM orders WHERE order_id = 2 OR order_id = 3;
