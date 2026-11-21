-- Manual system test m4: crash recovery after kill -9.
-- MANUAL: do not run with --file. Follow the steps in the server terminal and two client
-- terminals, A and B.
--
-- Setup:
--   [server] scripts/run.sh reset-data && scripts/run.sh server --seed
--   [A]      scripts/run.sh client
--   [B]      scripts/run.sh client
--
-- On restart the server replays the WAL: committed work is redone, unfinished work is undone.

-- [A] committed work: one auto-commit statement and one explicit transaction.
-- expect: CREATE TABLE, 1 row affected, BEGIN, 1 row affected (twice), COMMIT
CREATE TABLE st_crash (id INTEGER PRIMARY KEY, v VARCHAR(10));
INSERT INTO st_crash VALUES (1, 'auto');
BEGIN;
INSERT INTO st_crash VALUES (2, 'commit');
UPDATE customers SET credit = 111 WHERE customer_id = 10;
COMMIT;

-- [B] unfinished work: an INSERT, an UPDATE and a DELETE that are never committed.
-- expect: BEGIN, 1 row affected (three times), then rows 2 and 3
BEGIN;
INSERT INTO st_crash VALUES (3, 'open');
UPDATE customers SET credit = 222 WHERE customer_id = 11;
DELETE FROM st_crash WHERE id = 1;
SELECT * FROM st_crash;

-- [another terminal] kill the server without letting it shut down:
--   pkill -9 -f org.rafendb.server.ServerMain
-- (on Windows, end the java.exe process in Task Manager)
-- expect: A and B print "Connection to server lost."; B also prints
--         "Your open transaction was aborted."; both prompts turn to ?>

-- [server] restart on the same data, without --seed:
--   scripts/run.sh server
-- expect: "Data directory in data, not seeded." and "Listening on port 3140."

-- [A] reconnect: \connect
-- expect: 2 rows: (1, auto), (2, commit); row 3 never existed and row 1 is back
SELECT * FROM st_crash ORDER BY id;

-- expect: 2 rows: (10, 111.0) committed; (11, 66.5) as seeded, B's 222 undone
SELECT customer_id, credit FROM customers WHERE customer_id = 10 OR customer_id = 11;

-- [A] clean up. expect: DROP TABLE, 1 row affected
DROP TABLE st_crash;
UPDATE customers SET credit = 65 WHERE customer_id = 10;
