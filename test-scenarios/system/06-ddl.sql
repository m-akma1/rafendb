-- System test 06: CREATE TABLE, CREATE INDEX, DROP TABLE.
-- Run:    scripts/run.sh client --echo --file=test-scenarios/system/06-ddl.sql
-- Needs:  a running server; the sample tables are not touched. Drops what it creates.
-- Exit:   0

-- A parent with an inline primary key, and a child with a table-level primary key and a
-- foreign key, between them using all four column types.
-- expect: CREATE TABLE (twice)
CREATE TABLE st_dept (dept_id INTEGER PRIMARY KEY, dname VARCHAR(20), budget FLOAT);
CREATE TABLE st_emp (emp_id INTEGER, dept_id INTEGER, grade CHAR(2), salary FLOAT,
  PRIMARY KEY (emp_id),
  FOREIGN KEY (dept_id) REFERENCES st_dept (dept_id));

-- expect: customers, order_items, orders, st_dept, st_emp
\dt

-- expect: emp_id integer not nullable, primary key; dept_id integer, grade char(2), salary float
--         nullable; foreign key fk_st_emp_1: dept_id -> st_dept(dept_id), on delete restrict
\d st_emp

-- The new tables take rows, joins and indexes like the sample ones.
-- expect: 1 row affected (five times)
INSERT INTO st_dept VALUES (1, 'Engineering', 1000.0);
INSERT INTO st_dept VALUES (2, 'Operations', 500);
INSERT INTO st_emp VALUES (10, 1, 'A1', 100.0);
INSERT INTO st_emp VALUES (11, 1, 'B2', 200.0);
INSERT INTO st_emp VALUES (12, 2, 'A1', NULL);

-- expect: 3 rows, by emp_id: (10, Engineering), (11, Engineering), (12, Operations)
SELECT st_emp.emp_id, st_dept.dname FROM st_emp NATURAL JOIN st_dept ORDER BY emp_id;

-- expect: CREATE INDEX (twice)
CREATE INDEX st_emp_dept ON st_emp(dept_id);
CREATE INDEX st_emp_grade ON st_emp(grade) USING HASH;

-- expect: st_emp_dept on dept_id (b+tree), st_emp_grade on grade (hash)
\di st_emp

-- expect: 2 rows: 10, 12
SELECT emp_id FROM st_emp WHERE grade = 'A1' ORDER BY emp_id;

-- CASCADE drops the parent together with the foreign keys that point at it; the child stays.
-- expect: DROP TABLE
DROP TABLE st_dept CASCADE;

-- expect: st_emp is still listed, st_dept is gone
\dt

-- expect: columns and indexes as before, and no foreign keys section
\d st_emp

-- expect: 3 rows; the child keeps its rows
SELECT emp_id FROM st_emp;

-- Dropping a table drops its indexes too.
-- expect: DROP TABLE
DROP TABLE st_emp;

-- expect: customers, order_items, orders
\dt

-- expect: only the 3 sample indexes
\di
