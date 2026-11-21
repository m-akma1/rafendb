# System test scenarios

End-to-end SQL scenarios run through the whole engine: client → server → Query Processor → Query Optimizer → Concurrency Control → Storage → Failure Recovery. The component drivers test one component at a time; these test the components together.

## Automated scenarios

Each file runs in one client session and checks itself through `-- expect:` comments next to each statement. Start from a freshly seeded database:

```bash
./mvnw -q package -DskipTests
scripts/run.sh reset-data
scripts/run.sh server --seed                 # leave it running; use another terminal below
scripts/run.sh client --echo --file=test-scenarios/system/02-select.sql
```

Or run all of them in order:

```bash
for f in test-scenarios/system/*.sql; do
  echo "=== $f"; scripts/run.sh client --echo --file="$f"; echo "exit: $?"
done
```

Every file leaves the sample database exactly as it found it (scratch tables are named `st_*` and are dropped at the end), so the files run in any order and can be re-run. If a file is stopped halfway, `scripts/run.sh reset-data` and restart the server with `--seed`.

The client keeps running after a failed statement and exits with `1` if any statement failed, so the files with deliberate errors are expected to exit `1`.

| File                    | Covers                                                                                      | Exit |
|-------------------------|---------------------------------------------------------------------------------------------|------|
| `01-catalog.sql`        | `\dt`, `\d`, `\di`, `\stats` on the sample database                                         | 0    |
| `02-select.sql`         | Projection, WHERE (comparisons, arithmetic, AND/OR/NOT, parentheses), ORDER BY, LIMIT, AS   | 0    |
| `03-join.sql`           | Comma join, JOIN ON, NATURAL JOIN, three-way join, self-join, qualified column names        | 0    |
| `04-subquery.sql`       | IN, NOT IN, EXISTS, NOT EXISTS (correlated), scalar subqueries                              | 0    |
| `05-dml.sql`            | INSERT, UPDATE, DELETE, NULL values and NULL comparison semantics                           | 0    |
| `06-ddl.sql`            | CREATE TABLE with all four types and FKs, CREATE INDEX, DROP TABLE CASCADE                  | 0    |
| `07-transaction.sql`    | Auto-commit, BEGIN/COMMIT, BEGIN/ABORT across tables, reading own writes, index rollback    | 0    |
| `08-index.sql`          | Index scans chosen by cost on a 60-block table, B+ tree and hash, index upkeep under DML    | 0    |
| `09-errors.sql`         | Parse, binding and type errors, failed-transaction state, refused DDL; data stays unchanged | 1    |
| `10-constraints.sql`    | Primary key uniqueness and foreign key enforcement on INSERT, UPDATE and DELETE             | 1    |

`08-index.sql` is large (about 230 KB) on purpose: with 4 ms per seek and 0.1 ms per transfer, a table scan is cheaper than any index lookup until the table spans more than about 42 blocks, so the table needs that much data before the optimizer picks an index.

## Manual scenarios

`manual/` holds scenarios that need two sessions at once, a server restart, or a killed server. Each file lists its steps as `-- [A]` and `-- [B]` (two client terminals) and `-- [server]` (the server terminal). Type or paste each step into the named terminal, in order, and compare with the `-- expect:` comments. Do not run these with `--file`: one session cannot interleave with itself.

| File                                     | Covers                                                                             |
|------------------------------------------|------------------------------------------------------------------------------------|
| `manual/m1-lock-blocking.sql`            | Strict 2PL: a writer blocks readers and writers of the same table until it ends    |
| `manual/m2-deadlock.sql`                 | Deadlock across two tables is detected; one victim is rolled back, the other runs  |
| `manual/m3-timestamp.sql`                | Timestamp ordering (`--protocol=timestamp`): late reads and writes are aborted     |
| `manual/m4-crash-recovery.sql`           | `kill -9` mid-transaction: committed work is redone, uncommitted work is undone    |
| `manual/m5-disconnect-and-shutdown.sql`  | A dropped client and `\shutdown` both abort open transactions; checkpoint on exit  |
