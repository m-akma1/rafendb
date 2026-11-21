-- Manual system test m3: timestamp ordering.
-- MANUAL: do not run with --file. Follow the steps in two client terminals, A and B.
--
-- Setup:
--   [server] scripts/run.sh reset-data && scripts/run.sh server --seed --protocol=timestamp
--   [A]      scripts/run.sh client
--   [B]      scripts/run.sh client
--
-- A transaction's timestamp is taken at BEGIN. Nothing waits: an operation that arrives too late
-- for its timestamp aborts its transaction. In each part A begins first, so A is the older one.
-- Changes nothing that survives.

-- Part 1: an older transaction may not write what a younger one has already read.

-- [A] expect: BEGIN
BEGIN;

-- [B] expect: BEGIN, then 51.5
BEGIN;
SELECT credit FROM customers WHERE customer_id = 1;

-- [A] expect: "Runtime error at Query Processor: T<n> has been aborted"; prompt rafendb!>
UPDATE customers SET credit = 99 WHERE customer_id = 1;

-- [A] expect: ABORT
ABORT;

-- [B] expect: COMMIT
COMMIT;

-- Part 2: an older transaction may not read what a younger one has already written.

-- [A] expect: BEGIN
BEGIN;

-- [B] expect: BEGIN, then 1 row affected
BEGIN;
UPDATE customers SET credit = 77 WHERE customer_id = 1;

-- [A] expect: "Runtime error at Query Processor: T<n> has been aborted"; prompt rafendb!>
SELECT credit FROM customers WHERE customer_id = 1;

-- [A] expect: ABORT
ABORT;

-- [B] expect: ABORT
ABORT;

-- Part 3: reads in timestamp order never conflict, and nobody waits.

-- [A] expect: BEGIN, then 51.5
BEGIN;
SELECT credit FROM customers WHERE customer_id = 1;

-- [B] expect: BEGIN, then 51.5, at once
BEGIN;
SELECT credit FROM customers WHERE customer_id = 1;

-- [A] expect: COMMIT
COMMIT;

-- [B] expect: COMMIT
COMMIT;

-- [A] expect: 51.5 (neither aborted write survived)
SELECT credit FROM customers WHERE customer_id = 1;
