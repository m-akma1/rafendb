# RafenDB

<p align="center" style="margin: 1.5rem 0;">
	<img src="./docs/RafenDB.png" alt="RafenDB Logo" width="50%">
</p>

Yes, you're right! **A mini Database Management System (mDBMS) from scratch.**

## Overview

RafenDB is a mini relational DBMS written in Java. A server owns the database and serves SQL to command-line clients over a socket, one session per client.

Under the hood:

- **Storage.** Rows live in slotted pages behind a shared buffer pool, with B+ tree and hash indexes, a persistent catalog, and table statistics.
- **Optimization.** SQL is parsed, checked against the catalog, and planned by cost: selections and projections are pushed down, cartesian products become joins, joins are reordered, and an index scan is used when it is cheaper than a table scan. `EXPLAIN` shows the chosen plan.
- **Transactions.** Concurrent transactions are isolated by strict two-phase locking with deadlock detection, or by timestamp ordering. Primary and foreign keys are enforced.
- **Recovery.** Every change goes through a write-ahead log (WAL). An aborted transaction is rolled back from the log, after a crash the server redoes committed work and undoes unfinished work, and a clean shutdown takes a checkpoint. The recovery manager also supports point-in-time recovery, shown in its driver.

## Components

| Component                   | Package                   | Group | Responsibility                                                                               |
|-----------------------------|---------------------------|-------|----------------------------------------------------------------------------------------------|
| Query Processor             | `org.rafendb.processor`   | G1    | Sessions and transactions. Turns plans into operators, runs them, and returns results.       |
| Concurrency Control Manager | `org.rafendb.concurrency` | G2    | Locking or timestamp ordering, deadlock detection, and transaction ids.                      |
| Query Optimizer             | `org.rafendb.optimizer`   | G3    | Parses and checks SQL, builds the query tree, and picks join order and access paths by cost. |
| Storage Manager             | `org.rafendb.storage`     | G4    | Pages, buffer pool, records, catalog, indexes, and table statistics.                         |
| Failure Recovery Manager    | `org.rafendb.recovery`    | G5    | WAL, rollback, checkpoints, crash recovery, and point-in-time recovery.                      |

The supergroup owns the parts that hold the components together:

| Part              | Package                                     | Responsibility                                                                               |
|-------------------|---------------------------------------------|----------------------------------------------------------------------------------------------|
| Contracts         | `org.rafendb.api`, `org.rafendb.common`     | The interfaces between components, and the data types and exceptions they exchange           |
| Engine            | `org.rafendb.engine`                        | Builds the components (or their mocks), wires them, runs startup recovery, and answers the server meta-commands |
| Server and client | `org.rafendb.server`, `client`, `net`       | Socket server with one session per client, the command-line client, and their wire protocol  |
| Driver kit        | `org.rafendb.driver`                        | Shared base for the component drivers: flags, prompt, scenario files, and pass/fail counting |
| Mocks             | `org.rafendb.mock`                          | A stand-in for every `api` interface, so a component can run without the real ones          |

Components talk to each other only through `api/` and `common/`. Class diagrams of every package, generated from the code, are in [docs/generated/](docs/generated/index.md).

## Requirements and build

You need **JDK 25 or newer**. Maven is optional: `./mvnw` downloads the pinned version on its first run.

```bash
./mvnw verify                      # format, compile, test, and package target/rafendb.jar
./mvnw package -DskipTests         # only package, skipping the tests
./mvnw package -Pdiagrams          # also regenerate the class diagrams in docs/generated/ (needs Graphviz)
```

Every entry point runs through one launcher, `scripts/run.sh`. It needs the packaged jar, so build first. On Windows use `mvnw.cmd` and `scripts\run.cmd` with the same arguments. IDE setup is in [CONTRIBUTING.md §1](./CONTRIBUTING.md#1-setup).

## Quick start

Start a server on a fresh database with the sample tables loaded:

```bash
scripts/run.sh reset-data      # delete data/ to start from an empty database
scripts/run.sh server --seed   # load the sample database, then listen on port 3140
```

In another terminal, connect a client and try a few statements:

```bash
scripts/run.sh client
```

The prompt shows where your session is:

| Prompt      | Meaning                                                                                              |
|-------------|------------------------------------------------------------------------------------------------------|
| `rafendb=>` | Idle: no transaction is open, so each statement commits on its own (auto-commit)                     |
| `rafendb*>` | In a transaction, after `BEGIN;`. Changes are kept until `COMMIT;` or `ABORT;`                       |
| `rafendb!>` | Failed: the transaction failed and was rolled back. Enter `ABORT;` to return to idle                 |
| `rafendb->` | The statement continues on the next line (no `;` yet), so nothing has run yet                        |
| `rafendb'>` | Inside a quoted string that spans lines                                                              |
| `rafendb/*>`| Inside a comment that spans lines                                                                    |
| `?>`        | Disconnected from the server. Enter `\connect` to reconnect                                          |

## Running the server

```bash
scripts/run.sh server [options]
```

| Flag                         | Default | Meaning                                                                |
|------------------------------|---------|------------------------------------------------------------------------|
| `--port=<n>`                 | `3140`  | Port to listen on                                                      |
| `--dir=<path>`               | `data`  | Data directory: table files, indexes, catalog, and `wal.log`           |
| `--seed`                     | off     | Load the sample database, only if the database is empty                |
| `--protocol=lock\|timestamp` | `lock`  | Concurrency protocol                                                   |
| `--frames=<n>`               | `256`   | Buffer pool size in pages                                              |
| `--timeout=<s>`              | `5`     | How long a shutdown waits for running statements and open transactions |
| `--mock=<list>`              | none    | Replace components with mocks, any of `qp,sm,ccm,frm`                  |
| `--help`, `--version`        |         | Show the help or the version                                           |

On start, the server locks its data directory, runs crash recovery from the WAL (redoing committed work and undoing unfinished transactions), loads the sample data if `--seed` asks for it, and starts listening. Only one process may open a data directory at a time, so a second server or a driver on the same directory is refused.

Ctrl+C on the server, or `\shutdown` from any client, stops it gracefully: new clients are refused, running statements finish, open transactions are aborted, a checkpoint is taken, and the data is flushed. A killed server (`kill -9`) loses nothing that was committed: the next start recovers it from the WAL. Interactive clients that lose the server wait at the `?>` prompt, and their open transaction is aborted.

`scripts/run.sh reset-data` deletes `data/` so the next start begins empty.

**Mocks.** `--mock=qp` serves canned rows from a mock Query Processor and opens no data at all. Mocking `sm`, `ccm`, or `frm` runs the real Query Processor against the mocks and keeps the data in a temporary directory that is deleted on exit, so a mock run never touches `data/`. The Query Optimizer can't be mocked in the server.

## Using the client

```bash
scripts/run.sh client [options]
```

| Flag                  | Default     | Meaning                                              |
|-----------------------|-------------|------------------------------------------------------|
| `--host=<host>`       | `127.0.0.1` | Server host                                          |
| `--port=<n>`          | `3140`      | Server port                                          |
| `--file=<file>`       |             | Run the statements in a file, then exit              |
| `--command=<sql>`     |             | Run the given statements, then exit                  |
| `--echo`              | off         | Print each statement before its result               |
| `--home=<dir>`        |             | Client directory for history and `client.properties` |
| `--help`, `--version` |             | Show the help or the version                         |

SQL statements end with `;` and may span several lines. Several statements may share a line; they run one after another. Lines starting with `\` are meta-commands and need no `;`. Comments start with `--` or sit between `/*` and `*/`.

With `--file`, `--command`, or piped input the client runs non-interactively and exits with `0` if everything succeeded, `1` if a statement or command failed, and `2` if the connection to the server was lost. `--home` only selects where history and client settings live; it has nothing to do with the database.

**Meta-commands answered by the client:**

| Command                                       | Meaning                                             |
|-----------------------------------------------|-----------------------------------------------------|
| `\?`, `\h`, `\help`                           | Show the help                                       |
| `\q`, `\quit`, `\exit` (or Ctrl+D)            | Quit                                                |
| `\r`                                          | Clear the statement typed so far                    |
| `\f <file>`                                   | Run the statements in a file                        |
| `\timing [on\|off]`                           | Show how long each statement takes                  |
| `\info`                                       | Show the connection                                 |
| `\clear` (or Ctrl+L)                          | Clear the screen                                    |
| `\history`                                    | Show the statements typed before                    |
| `\connect [--host=<host>] [--port=<n>]`, `\c` | Connect to the server when disconnected             |
| `\shutdown`                                   | Shut the server down for everyone (asks to confirm) |

**Meta-commands answered by the server.** They read the catalog and statistics without locks and don't affect your transaction, so they also work inside `BEGIN`:

| Command          | Meaning                                                                                  |
|------------------|------------------------------------------------------------------------------------------|
| `\dt`, `\d`      | List the tables                                                                          |
| `\d <table>`     | A table's columns and types, then its indexes and foreign keys                           |
| `\di [table]`    | The indexes of one table, or of all                                                      |
| `\stats [table]` | Statistics the optimizer uses: n_r, b_r, l_r, f_r, and, for one table, V(A,r) per column |

## Supported SQL

| Statement     | Example                                                                                                                   |
|---------------|---------------------------------------------------------------------------------------------------------------------------|
| SELECT        | `SELECT name, credit FROM customers WHERE credit >= 100 AND status = 'A';`                                                |
| Conditions    | `SELECT * FROM customers WHERE (customer_id < 3 OR customer_id > 58) AND NOT status = 'I';`                               |
| Expressions   | `SELECT * FROM orders WHERE (amount - 10) * 2 > order_id + 50;`                                                           |
| Joins         | `SELECT * FROM orders, customers WHERE orders.customer_id = customers.customer_id;`                                       |
|               | `SELECT * FROM orders JOIN order_items ON orders.order_id = order_items.order_id;`                                        |
|               | `SELECT * FROM customers NATURAL JOIN orders;`                                                                            |
| ORDER BY      | `SELECT * FROM customers ORDER BY status, customer_id DESC;` (`ASC` is the default)                                       |
| LIMIT         | `SELECT * FROM orders ORDER BY amount DESC LIMIT 5;`                                                                      |
| Table aliases | `SELECT c.name FROM customers AS c WHERE c.customer_id = 3;`                                                              |
| Subqueries    | `SELECT name FROM customers WHERE customer_id IN (SELECT customer_id FROM orders WHERE amount > 68);`                     |
|               | `SELECT * FROM orders AS o WHERE EXISTS (SELECT * FROM order_items AS i WHERE i.order_id = o.order_id);`                  |
|               | `SELECT order_id FROM orders WHERE amount = (SELECT amount FROM orders WHERE order_id = 3);`                              |
| INSERT        | `INSERT INTO customers VALUES (61, 'Rafen', 99.5, 'A');`                                                                  |
| UPDATE        | `UPDATE orders SET amount = amount * 1.1 WHERE customer_id = 3;`                                                          |
| DELETE        | `DELETE FROM order_items WHERE price < 5;`                                                                                |
| CREATE TABLE  | `CREATE TABLE dept (dept_id INTEGER PRIMARY KEY, dname VARCHAR(20), budget FLOAT);`                                       |
|               | `CREATE TABLE emp (emp_id INTEGER, dept_id INTEGER, PRIMARY KEY (emp_id), FOREIGN KEY (dept_id) REFERENCES dept (dept_id));` |
| DROP TABLE    | `DROP TABLE dept CASCADE;` (`RESTRICT` is the default)                                                                    |
| CREATE INDEX  | `CREATE INDEX idx_orders_amount ON orders(amount) USING HASH;` (`USING BTREE` is the default)                             |
| Transactions  | `BEGIN;` or `BEGIN TRANSACTION;`, then `COMMIT;` or `ABORT;`                                                              |
| EXPLAIN       | `EXPLAIN SELECT name FROM customers WHERE customer_id = 7;`                                                               |

Details:
- WHERE supports `=`, `<>`, `<`, `<=`, `>`, `>=`, arithmetic (`+ - * /`) with parentheses, `AND`, `OR`, and `NOT`. A column can be compared with a value or with another column. Text literals use single quotes, and `NULL` is a value.
- A comparison with `NULL` is unknown, so `WHERE credit = NULL` matches no row, and `NOT` of an unknown condition is still unknown.
- Subqueries work in WHERE: `IN`, `NOT IN`, `EXISTS`, and `NOT EXISTS`, which may refer to the outer query's columns, and scalar subqueries, which must return at most one row.
- Columns can be qualified as `table.column`, or `alias.column` after `FROM table AS alias`. An unqualified column that is in more than one table is an error. After a join, column names that appear in more than one table are shown qualified.
- The SELECT list is `*` or a list of columns. `ORDER BY` takes one or more columns, each with its own direction.
- INSERT takes one row with a value for every column, in table order. UPDATE sets one column.
- Column types are `INTEGER`, `FLOAT`, `CHAR(n)`, and `VARCHAR(n)`. In `CREATE TABLE`, primary key columns are NOT NULL and the other columns accept NULL.
- `EXPLAIN` shows the optimized plan of a SELECT and its estimated cost.

**Keys.** `CREATE TABLE` declares a primary key on one column (`PRIMARY KEY` after the type) or on several (`PRIMARY KEY (a, b)`), and foreign keys with `FOREIGN KEY (cols) REFERENCES table (cols)`; without the referenced columns, the foreign key points to that table's primary key. Every statement keeps the keys valid: a duplicate primary key, a reference to a missing row, or deleting or changing a row that is still referenced fails the statement and changes nothing. `DROP TABLE` refuses a table that other tables reference, unless `CASCADE` is given; then those tables lose their foreign keys to it but keep their rows.

**Transactions.** Outside `BEGIN`, every statement is its own transaction and commits when it succeeds. Inside `BEGIN`, any error (a syntax error, an unknown column, a key violation, or an abort by concurrency control) rolls the whole transaction back and moves the session to the failed state (`rafendb!>`). From there, only `ABORT` is accepted, and it returns the session to idle. When a client disconnects inside a transaction, that transaction is aborted and its locks are released. `CREATE TABLE`, `DROP TABLE`, and `CREATE INDEX` run only outside `BEGIN`.

## Sample database

`--seed` creates three related tables with 60 rows each, using all four types:

| Table         | Columns                                                                                   | Keys and indexes                                                                      |
|---------------|-------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| `customers`   | `customer_id INTEGER`, `name VARCHAR(100)`, `credit FLOAT` (nullable), `status CHAR(1)`   | PK `customer_id`; hash index `idx_customers_name` on `name`                           |
| `orders`      | `order_id INTEGER`, `customer_id INTEGER`, `amount FLOAT`, `note VARCHAR(200)` (nullable) | PK `order_id`; FK `customer_id` → `customers`; B+ tree index `idx_orders_customer_id` |
| `order_items` | `item_id INTEGER`, `order_id INTEGER`, `sku VARCHAR(32)`, `gift CHAR(1)`, `price FLOAT`   | PK `item_id`; FK `order_id` → `orders`; B+ tree index `idx_order_items_order_id`      |

## Component drivers

Each component has a driver that tests it in isolation, with the other components real or mocked. Drivers check one component at a time; the server above is the demo of the whole engine.

```bash
scripts/run.sh driver <processor|optimizer|storage|concurrency|recovery> [options]
# short names work too: qp, qo, sm, ccm, frm
```

All drivers share the same options: `--run=<file>` runs a scenario file and exits, `--mock=<list>` swaps other components for mocks (`qp,qo,sm,ccm,frm` or `all`), and `--help` lists the driver's own options and commands. Without `--run` the driver is interactive: type `help` for its commands and `run <file>` to run a scenario. Each check prints `PASS` or `FAIL`, each file ends with `RESULT: X passed, Y failed`, and the driver exits with `1` if anything failed.

| Driver      | Own options                                                                                                  | Scenarios                                                                                                                                                               |
|-------------|--------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Processor   | `--dir=<path>` (default: a fresh sample database for each file), `--protocol=lock\|timestamp`, `--frames=<n>` | `test-scenarios/processor/all.txt` runs every `.test` file on the real components; `mock-sm.txt` runs with `--mock=sm`                                                  |
| Optimizer   | Runs on the mock catalog, so pass `--mock=sm`                                                                | `test-scenarios/optimizer/driver-valid.txt` and `driver-error.txt`: one `<outcome> \| <sql>` per line, where the outcome is `VALID`, `SYNTAX_ERROR`, or `VALIDATION_ERROR` |
| Storage     | `--dir=<path>` (default `data`), `--frames=<n>` (default 64)                                                 | `test-scenarios/storage/basic.txt` and `operations.txt` on a fresh directory, then `restart.txt` on the same directory; the `sample` command loads the sample tables      |
| Concurrency | `--protocol=lock\|timestamp` (default `lock`), `--mock=sm,frm` for in-memory storage                         | `test-scenarios/concurrency/*.txt`; `verify cyclic <file>` and `verify serializable <file>` check a handwritten schedule (see the folder's README)                     |
| Recovery    | `--dir=<path>` (default: temporary), `--wal=<path>`, `--frames=<n>` (default 8)                              | `test-scenarios/recovery/`: `crash-recovery.txt`, `rollback.txt`, `pitr.txt`                                                                                            |

For example:

```bash
scripts/run.sh driver processor --run=test-scenarios/processor/all.txt
scripts/run.sh driver optimizer --mock=sm --run=test-scenarios/optimizer/driver-valid.txt
scripts/run.sh driver storage --dir=data-sm --run=test-scenarios/storage/operations.txt
scripts/run.sh driver concurrency --protocol=timestamp --run=test-scenarios/concurrency/timestamp-violation.txt
scripts/run.sh driver recovery --run=test-scenarios/recovery/crash-recovery.txt
```

The storage driver keeps its data across runs like the server does: every write command commits its own WAL transaction, and a restart recovers it. Stop the server before pointing a driver at its directory, since only one process may open it.

## Testing

- `./mvnw verify` runs the unit and integration tests, including sessions on the real Storage Manager, Failure Recovery Manager, and Concurrency Control Manager.
- The component drivers above test one component at a time.
- [test-scenarios/system/](test-scenarios/system/README.md) tests the whole engine through the client and server: ten SQL files that check themselves (catalog, SELECT, joins, subqueries, DML, DDL, transactions, indexes, errors, and keys), and five manual scenarios for lock blocking, deadlock, timestamp ordering, crash recovery, and disconnect and shutdown.

## Known limitations

- The SELECT list holds only `*` or columns: no expressions, aggregates, `GROUP BY`, or column aliases. Table aliases need `AS`.
- INSERT takes exactly one full row, with no column list. UPDATE sets one column per statement.
- There is no `IS NULL`, and no `%` operator.
- `ROLLBACK` and `START TRANSACTION` aren't keywords; use `ABORT` and `BEGIN`.
- Foreign keys are always `RESTRICT`: there is no `ON DELETE CASCADE` or `SET NULL`. (`DROP TABLE ... CASCADE` only drops the foreign keys that point to the table.)
- `CREATE TABLE`, `DROP TABLE`, and `CREATE INDEX` aren't logged, so they run only outside `BEGIN`.
- `EXPLAIN` takes only a SELECT and shows the plan after optimization; the optimizer driver shows the plan before and after.
- Locks cover whole tables, so two writers of different rows of one table still wait for each other. A transaction waiting for a lock waits until the holder commits or aborts, or until it is picked as a deadlock victim; there is no lock timeout.
- `\stats` scans each table it reports on.

## Project layout

```
src/main/java/org/rafendb/
|                       # common codes
├── common/                 # shared data types
├── api/                    # interfaces between components
├── mock/                   # dummy implementations of api/
├── engine/                 # wiring, startup, and server-side meta-commands
├── driver/                 # shared driver kit
|
|                       # dbms client-server
├── net/                    # network request/response framing
├── server/                 # socket listener and sessions
├── client/                 # cli for end users
|
|                       # per group component
├── processor/              # query processor
├── optimizer/              # query optimizer
├── storage/                # storage manager
├── concurrency/            # concurrency control manager
└── recovery/               # failure recovery
docs/
└── generated/          # class diagrams of every package, generated by -Pdiagrams
scripts/                # launchers (run.sh, run.cmd)
test-scenarios/
├── <component>/            # scenario files for each component driver
└── system/                 # end-to-end SQL scenarios through the client and server
```

Each package with code has a `package-info.java` that describes it and names its owner.

## Credits and Contributing

> [!IMPORTANT]
> Semua **collaborators** wajib baca file [CONTRIBUTING.md](./CONTRIBUTING.md) supaya sepemikiran dan sepemahaman.

**Supergroup RafenDB**  
PIC: 13524099 Muhammad Akmal ([@m-akma1](https://github.com/m-akma1))

**Group 1 Haskell · Query Processor**  
PIC: 13524063 Marcel Luther Sitorus ([@Celtors](https://github.com/Celtors))

| Role | NIM      | Name                  | Handle                                              |
|------|----------|-----------------------|-----------------------------------------------------|
| QP1  | 13524099 | Muhammad Akmal        | [@m-akma1](https://github.com/m-akma1)              |
| QP2  | 13524066 | Nathanael Gunawan     | [@NathanaelGun](https://github.com/NathanaelGun)    |
| QP3  | 13524015 | Mahatma Brahmana      | [@llennmaha](https://github.com/llennmaha)          |
| QP4  | 13524042 | Suryani Mulia Utami   | [@suryaniutaami](https://github.com/suryaniutaami)  |
| QP5  | 13524063 | Marcel Luther Sitorus | [@Celtors](https://github.com/Celtors)              |

**Group 2 Golang · Concurrency Control Manager**  
PIC: 13524105 Nicholas Luis Chandra ([@staplesmaster](https://github.com/staplesmaster))

| Role     | NIM      | Name                        | Handle                                             |
|----------|----------|-----------------------------|----------------------------------------------------|
| CC1      | 13524039 | Rhenaldy Cahyadi Putra      | [@rhenprojects](https://github.com/rhenprojects)   |
| CC2, CC5 | 13524105 | Nicholas Luis Chandra       | [@staplesmaster](https://github.com/staplesmaster) |
| CC3      | 13524069 | Miguel Rangga Deardo Sinaga | [@miguelsinaga](https://github.com/miguelsinaga)   |
| CC4      | 13524147 | Muh. Hartawan Haidir        | [@Awan-Haidir](https://github.com/Awan-Haidir)     |

**Group 3 Fortran · Query Optimizer**  
PIC: 13524048 Josh Reinhart Zidik ([@achideon](https://github.com/achideon))

| Role | NIM      | Name                       | Handle                                                |
|------|----------|----------------------------|-------------------------------------------------------|
| QO1  | 13524048 | Josh Reinhart Zidik        | [@achideon](https://github.com/achideon)              |
| QO2  | 13524111 | Reynard Anderson Wijaya    | [@RND815](https://github.com/RND815)                  |
| QO3  | 13524081 | Alya Nur Rahmah            | [@alyanrrhma](https://github.com/alyanrrhma)          |
| QO4  | 13524009 | Mikhael Benrael Tampubolon | [@MikhaelBenrael](https://github.com/MikhaelBenrael)  |
| QO5  | 13524060 | Steven Tan                 | [@StevenT-1](https://github.com/StevenT-1)            |

**Group 4 Rust · Storage Manager**  
PIC: 13524090 Nashiruddin Akram ([@Akram17t](https://github.com/Akram17t))

| Role | NIM      | Name                    | Handle                                                   |
|------|----------|-------------------------|----------------------------------------------------------|
| SM1  | 13524078 | Fahd Muhammad Zahid     | [@fahdmz](https://github.com/fahdmz)                     |
| SM2  | 13524090 | Nashiruddin Akram       | [@Akram17t](https://github.com/Akram17t)                 |
| SM3  | 13524003 | Faiq Azzam Nafidz       | [@Shui7](https://github.com/Shui7)                       |
| SM4  | 13524138 | Ahmad Rinofaros Muchtar | [@Spicycoincracker](https://github.com/Spicycoincracker) |
| SM5  | 13524036 | Edward David Rumahorbo  | [@bangedaw](https://github.com/bangedaw)                 |

**Group 5 Python · Failure Recovery Manager**  
PIC: 13524033 Ray Owen Martin ([@Tensai-033](https://github.com/Tensai-033))

| Role     | NIM      | Name                         | Handle                                           |
|----------|----------|------------------------------|--------------------------------------------------|
| FR1, FR2 | 13524033 | Ray Owen Martin              | [@Tensai-033](https://github.com/Tensai-033)     |
| FR3, FR5 | 13524012 | Tengku Naufal Saqib          | [@tengkuNaufal](https://github.com/tengkuNaufal) |
| FR4      | 13524072 | I Gusti Ngurah Alit Dharma Y | [@SukaBotol](https://github.com/SukaBotol)       |

**IF3140 Database System**  
*Semester 1 2026/2027*

**Program Studi Teknik Informatika**  
**Sekolah Teknik Elektro dan Informatika**  
**Institut Teknologi Bandung**
