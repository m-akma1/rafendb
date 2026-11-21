-- System test 09: user errors. Each one is reported, changes nothing, and the session goes on.
-- Run:    scripts/run.sh client --echo --file=test-scenarios/system/09-errors.sql
-- Needs:  a server started with --seed. Changes nothing that survives.
-- Exit:   1 (the errors are deliberate; every "expect: error" line prints one error)

-- Syntax.
-- expect: error (unknown statement)
SELEC * FROM customers;

-- expect: error (WHERE without a condition)
SELECT * FROM customers WHERE;

-- expect: error (ROLLBACK is not a keyword; use ABORT)
ROLLBACK;

-- Names.
-- expect: error (no table nope)
SELECT * FROM nope;

-- expect: error (no column nope)
SELECT nope FROM customers;

-- expect: error (customer_id is in both tables, so it must be qualified)
SELECT customer_id FROM customers JOIN orders ON customers.customer_id = orders.customer_id;

-- Types.
-- expect: error (VARCHAR compared with an integer)
SELECT customer_id FROM customers WHERE name = 3;

-- expect: error (FLOAT compared with text)
SELECT * FROM customers WHERE credit > 'abc';

-- expect: error (a column alone is not a condition)
SELECT customer_id FROM customers WHERE customer_id;

-- expect: error (UPDATE puts an integer into a VARCHAR column)
UPDATE customers SET name = 5 WHERE customer_id = 1;

-- INSERT shape and values.
-- expect: error (2 values for 4 columns)
INSERT INTO customers VALUES (63, 'Short');

-- expect: error (text for an INTEGER column)
INSERT INTO customers VALUES ('x', 'y', 1.0, 'A');

-- expect: error (NULL for the NOT NULL column name)
INSERT INTO customers VALUES (64, NULL, 1.0, 'A');

-- expect: error (NULL for the primary key)
INSERT INTO customers VALUES (NULL, 'No key', 1.0, 'A');

-- expect: error ('AB' does not fit CHAR(1))
INSERT INTO customers VALUES (65, 'Too long', 1.0, 'AB');

-- Subqueries.
-- expect: error (scalar subquery returns more than one row)
SELECT order_id FROM orders WHERE amount = (SELECT amount FROM orders WHERE order_id > 3);

-- DDL.
-- expect: error (table already exists)
CREATE TABLE customers (id INTEGER PRIMARY KEY);

-- expect: error (index name already used)
CREATE INDEX idx_customers_name ON customers(name);

-- expect: error (orders references customers; DROP is RESTRICT unless CASCADE is given)
DROP TABLE customers;

-- expect: error (same, with RESTRICT spelled out)
DROP TABLE customers RESTRICT;

-- expect: error (no table nope)
DROP TABLE nope;

-- Transaction commands in the wrong state.
-- expect: error (COMMIT outside a transaction)
COMMIT;

-- expect: error (ABORT outside a transaction)
ABORT;

-- DDL cannot be rolled back, so it is refused inside a transaction. The refusal fails the
-- transaction like any other error.
-- expect: BEGIN
BEGIN;

-- expect: error
CREATE TABLE st_in_tx (id INTEGER PRIMARY KEY);

-- expect: ABORT
ABORT;

-- expect: customers, order_items, orders (st_in_tx was not created)
\dt

-- An error inside BEGIN fails the transaction: everything it did is rolled back, and only ABORT
-- is accepted until then.
-- expect: BEGIN
BEGIN;

-- expect: 1 row affected
UPDATE customers SET credit = 99 WHERE customer_id = 2;

-- expect: error (syntax); the transaction is now failed
SELEC * FROM customers;

-- expect: error (the transaction has failed)
SELECT * FROM customers;

-- expect: error (the transaction has failed)
BEGIN;

-- expect: error (a failed transaction cannot commit)
COMMIT;

-- expect: ABORT
ABORT;

-- expect: 1 row: 53.0 (the UPDATE was rolled back)
SELECT credit FROM customers WHERE customer_id = 2;

-- BEGIN twice is itself an error, and fails the transaction.
-- expect: BEGIN
BEGIN;

-- expect: error (a transaction is already in progress)
BEGIN;

-- expect: ABORT
ABORT;

-- Nothing above changed the data.
-- expect: n_r = 60 for every table
\stats

-- expect: 1 row: Customer 1, 51.5
SELECT name, credit FROM customers WHERE customer_id = 1;
