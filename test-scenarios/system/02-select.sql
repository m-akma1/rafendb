-- System test 02: single-table SELECT.
-- Run:    scripts/run.sh client --echo --file=test-scenarios/system/02-select.sql
-- Needs:  a server started with --seed. Read-only.
-- Exit:   0
--
-- Sample data: customer i has name 'Customer i', credit 50 + 1.5i, status 'A' when i is even
-- and 'I' when odd. Order i belongs to customer i, amount 10 + i, note 'Order-i'.

-- Projection and *.
-- expect: 3 rows: (1, Customer 1, 51.5, I), (2, Customer 2, 53.0, A), (3, Customer 3, 54.5, I)
SELECT * FROM customers WHERE customer_id <= 3;

-- expect: columns name, credit; one row: Customer 7, 60.5
SELECT name, credit FROM customers WHERE customer_id = 7;

-- Comparison operators on each type.
-- expect: 1 row: Customer 1 (VARCHAR equality)
SELECT name FROM customers WHERE name = 'Customer 1';

-- expect: 2 rows: 59 (8.4), 60 (8.5) (FLOAT >=)
SELECT item_id, price FROM order_items WHERE price >= 8.4;

-- expect: 3 rows: 58, 59, 60 (INTEGER >)
SELECT order_id FROM orders WHERE order_id > 57;

-- expect: 1 row: 2, the lowest id with status 'A' (CHAR <>, ORDER BY, LIMIT)
SELECT customer_id FROM customers WHERE status <> 'I' ORDER BY customer_id LIMIT 1;

-- Arithmetic in WHERE, with column on both sides.
-- expect: 2 rows: 1, 2 (amount - 10 = order_id holds for every order)
SELECT order_id FROM orders WHERE amount - 10 = order_id AND order_id < 3;

-- expect: 2 rows: 59 (69.0), 60 (70.0)
SELECT order_id, amount FROM orders WHERE amount / 2 >= 34.5;

-- expect: 1 row: 1 (customer_id * 3 + 2 = 5)
SELECT customer_id FROM customers WHERE customer_id * 3 + 2 = 5;

-- AND, OR, NOT, and parentheses.
-- expect: 2 rows: (2, A), (60, A)
SELECT customer_id, status FROM customers WHERE (customer_id < 3 OR customer_id > 58) AND status = 'A';

-- expect: 2 rows: 60, 59 (NOT amount < 69, highest first)
SELECT order_id, amount FROM orders WHERE NOT amount < 69 ORDER BY amount DESC;

-- expect: 2 rows: 1, 2 (OR of two equalities on different columns)
SELECT order_id FROM orders WHERE order_id = 1 OR note = 'Order-2';

-- ORDER BY on several keys, ASC is the default.
-- expect: 4 rows in this order: (4, A), (2, A), (3, I), (1, I)
SELECT customer_id, status FROM customers WHERE customer_id <= 4 ORDER BY status, customer_id DESC;

-- LIMIT, with and without ORDER BY.
-- expect: 3 rows: 59 (138.5), 57 (135.5), 55 (132.5)
SELECT customer_id, credit FROM customers WHERE status = 'I' AND credit > 130 ORDER BY credit DESC LIMIT 3;

-- expect: 0 rows, header only
SELECT * FROM orders LIMIT 0;

-- Table alias with AS.
-- expect: 1 row: Customer 3
SELECT c.name FROM customers AS c WHERE c.customer_id = 3;

-- A condition no row meets.
-- expect: 0 rows
SELECT * FROM customers WHERE credit > 1000;
