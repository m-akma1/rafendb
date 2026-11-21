-- System test 10: primary key and foreign key enforcement.
-- Run:    scripts/run.sh client --echo --file=test-scenarios/system/10-constraints.sql
-- Needs:  a running server; the sample tables are not touched. Drops what it creates.
-- Exit:   1 (the violations are deliberate; every "expect: error" line prints one error)
--
-- Uses its own tables, so a violation that slips through cannot leak into other scenarios.

-- expect: CREATE TABLE (twice)
CREATE TABLE st_parent (pid INTEGER PRIMARY KEY, label VARCHAR(10));
CREATE TABLE st_child (cid INTEGER PRIMARY KEY, pid INTEGER,
  FOREIGN KEY (pid) REFERENCES st_parent (pid));

-- expect: 1 row affected (four times)
INSERT INTO st_parent VALUES (1, 'one');
INSERT INTO st_parent VALUES (2, 'two');
INSERT INTO st_child VALUES (10, 1);
INSERT INTO st_child VALUES (11, NULL);

-- Primary key: no two rows may share a key.
-- expect: error (duplicate key 1)
INSERT INTO st_parent VALUES (1, 'again');

-- expect: error (would give two rows key 2)
UPDATE st_parent SET pid = 2 WHERE pid = 1;

-- expect: 2 rows: (1, one), (2, two)
SELECT * FROM st_parent ORDER BY pid;

-- Foreign key on the child side: the referenced row must exist. NULL references nothing.
-- expect: error (no parent 9)
INSERT INTO st_child VALUES (12, 9);

-- expect: error (no parent 9)
UPDATE st_child SET pid = 9 WHERE cid = 10;

-- expect: 2 rows: (10, 1), (11, NULL)
SELECT * FROM st_child ORDER BY cid;

-- Foreign key on the parent side: ON DELETE RESTRICT (the default).
-- expect: error (child 10 references parent 1)
DELETE FROM st_parent WHERE pid = 1;

-- expect: error (child 10 references parent 1)
UPDATE st_parent SET pid = 5 WHERE pid = 1;

-- An unreferenced parent can go.
-- expect: 1 row affected
DELETE FROM st_parent WHERE pid = 2;

-- expect: 1 row: (1, one)
SELECT * FROM st_parent ORDER BY pid;

-- A violation inside BEGIN fails the whole transaction.
BEGIN;

-- expect: 1 row affected
INSERT INTO st_parent VALUES (3, 'three');

-- expect: error (duplicate key 1)
INSERT INTO st_parent VALUES (1, 'dup');

-- expect: ABORT
ABORT;

-- expect: 1 row: (1, one); parent 3 was rolled back
SELECT * FROM st_parent ORDER BY pid;

-- Clean up, child first.
-- expect: DROP TABLE (twice)
DROP TABLE st_child;
DROP TABLE st_parent;
