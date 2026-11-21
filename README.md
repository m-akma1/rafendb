# RafenDB

<p align="center" style="margin: 1.5rem 0;">
	<img src="./docs/RafenDB.png" alt="RafenDB Logo" width="50%">
</p>

Yes, you're right! **A mini Database Management System (mDBMS) from scratch.**

## Overview

<!-- [TODO: Perbaiki dikit biar lebih asik (Akmal).] RafenDB is a small relational database written in Java by five groups of the IF3140 Database System course. A server owns the database and serves SQL to command-line clients over a socket, one session per client. Data is stored in pages with hash and B+ tree indexes, every change goes through a write-ahead log (WAL) so the server recovers after a crash, and concurrent transactions are isolated by either strict two-phase locking or timestamp ordering. -->

## Components

| Component                   | Package                   | Group | Responsibility                                                                              |
|-----------------------------|---------------------------|-------|---------------------------------------------------------------------------------------------|
| Query Processor             | `org.rafendb.processor`   | G1    | Sessions and transactions; turns plans into operators, runs them, and returns results       |
| Concurrency Control Manager | `org.rafendb.concurrency` | G2    | Locking or timestamp ordering, deadlock detection, and transaction ids                      |
| Query Optimizer             | `org.rafendb.optimizer`   | G3    | Parses and checks SQL, builds the query tree, and picks join order and access paths by cost |
| Storage Manager             | `org.rafendb.storage`     | G4    | Pages, buffer pool, records, catalog, indexes, and table statistics                         |
| Failure Recovery Manager    | `org.rafendb.recovery`    | G5    | WAL, rollback, checkpoints, crash recovery, and point-in-time recovery                      |

<!-- [TODO: Perbaiki dikit untuk informasi komponen lain juga.] Components only talk to each other through the interfaces in `api/` and the shared types in `common/`; `engine/` builds the real implementations and wires them together. The [system context](docs/diagrams/01-system-context.svg) and [package architecture](docs/diagrams/02-full-package-architecture.svg) diagrams show the big picture, and [docs/diagrams/](docs/diagrams/) has sequence diagrams for SELECT, INSERT, commit, abort, deadlock, checkpoint, recovery, and more. -->

## Requirements and build

You need **JDK 25 or newer**. Maven is optional: `./mvnw` downloads the pinned version on its first run.

```bash
./mvnw verify      # format, compile, test, and package target/rafendb.jar
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
| `rafendb=>` | Idle mode, no transaction open. Each statement commits on its own (auto-commit)                      |
| `rafendb*>` | Transaction mode, inside/after a `BEGIN;`. All changes are kept until `COMMIT;` or `ABORT;`.         |
| `rafendb!>` | Failed mode. The transaction failed and was rolled back. Enter `ABORT;` to back to idle mode.        |
| `rafendb->` | Multi-line mode. The statement continues on the next line (no `;` yet). No statement executed yet.   |
| `rafendb'>` | Inside a multi-line quote.                                                                           |
| `rafendb/*>`| Inside a multi-line comment.                                                                         |
| `?>`        | Disconnected with the server. Enter `\connect` to reconnect again                                    |

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

Ctrl+C on the server, or `\shutdown` from any client, stops it gracefully: new clients are refused, running statements finish, open transactions are aborted, a checkpoint is taken, and the data is flushed. A killed server (`kill -9`) loses nothing that was committed since the next server startup will recovers it.

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

SQL statements end with `;` and may span several lines. Several statements may share a line and will executed as if written in multiple lines. Lines starting with `\` are meta-commands and need no `;`. 

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

| Statement    | Example                                                                                       |
|--------------|-----------------------------------------------------------------------------------------------|
| SELECT       | `SELECT name, credit FROM customers WHERE credit >= 100 AND status = 'A';`                    |
| Expressions  | `SELECT * FROM orders WHERE amount * 2 > (customer_id + 10) * 3;`                             |
| Joins        | `SELECT * FROM orders, customers WHERE orders.customer_id = customers.customer_id;`           |
|              | `SELECT * FROM orders JOIN order_items ON orders.order_id = order_items.order_id;`            |
|              | `SELECT * FROM customers NATURAL JOIN orders;`                                                |
| ORDER BY     | `SELECT * FROM orders ORDER BY amount DESC;` (`ASC` is the default)                           |
| INSERT       | `INSERT INTO customers VALUES (61, 'Rafen', 99.5, 'A');`                                      |
| UPDATE       | `UPDATE orders SET amount = amount * 1.1 WHERE customer_id = 3;`                              |
| DELETE       | `DELETE FROM order_items WHERE price < 5;`                                                    |
| CREATE INDEX | `CREATE INDEX idx_orders_amount ON orders(amount) USING HASH;` (`USING BTREE` is the default) |
| Transactions | `BEGIN;` or `BEGIN TRANSACTION;`, then `COMMIT;` or `ABORT;`                                  |
| EXPLAIN      | `EXPLAIN SELECT name FROM customers WHERE customer_id = 7;`                                   |

Details:
- WHERE supports `=`, `<>`, `<`, `<=`, `>`, `>=`, arithmetic (`+ - * /`) with parentheses, and `AND`. 
- A column can be compared with a value or with another column. Text literals use single quotes.
- Columns can be qualified as `table.column`. After a join, column names that appear in more than one table are shown qualified.
- INSERT takes one row with a value for every column, in table order. UPDATE sets one column.
- Column types are `INTEGER`, `FLOAT`, `CHAR(n)`, and `VARCHAR(n)`.

**Transactions.** Outside `BEGIN`, every statement is its own transaction and commits when it succeeds. Inside `BEGIN`, any error (a syntax error, an unknown column, or an abort by concurrency control) rolls the whole transaction back and moves the session to the failed state (`rafendb!>`). From there, only `ABORT` is accepted, and it returns the session to idle. When a client disconnects inside a transaction, that transaction is aborted and its locks are released.

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

Most drivers share the same options: `--run=<file>` runs a scenario file and exits, `--mock=<list>` swaps other components for mocks (`qp,qo,sm,ccm,frm` or `all`), and `--help` lists the driver's own commands. Without `--run` the driver is interactive: type `help` for its commands and `run <file>` to run a scenario.

| Driver      | Own options                                                             | Scenarios                                                                                                   |
|-------------|-------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| Storage     | `--dir=<path>` (default `data`), `--frames=<n>` (default 64)            | `test-scenarios/storage/basic.txt` (expects a fresh database); the `sample` command loads the sample tables |
| Optimizer   |                                                                         | `test-scenarios/optimizer/*.txt`, one `VALID \| <sql>` or `INVALID \| <sql>` per line                        |
| Recovery    | `--dir=<path>` (default: temporary), `--wal=<path>`, `--frames=<n>`     | `test-scenarios/recovery/`: `crash-recovery.txt`, `rollback.txt`, `pitr.txt`                                |
| Concurrency | `--protocol=LOCK_BASED\|TIMESTAMP_BASED`, its only option (no `--help`) | Built in: wait and rollback, two commits, and (with locking) a deadlock                                     |
| Processor   |                                                                         | Not implemented yet                                                                                         |

For example:

```bash
scripts/run.sh driver storage --dir=data-sm --run=test-scenarios/storage/basic.txt
scripts/run.sh driver recovery --run=test-scenarios/recovery/crash-recovery.txt
scripts/run.sh driver concurrency --protocol=TIMESTAMP_BASED
```

The storage driver keeps its data across runs like the server does: every INSERT, DELETE, or clear it runs commits its own WAL transaction, and a restart recovers it. Stop the server before pointing a driver at its directory, since only one process may open it.

## Known limitations

- WHERE has no `OR`, and there are no subqueries.
- `ROLLBACK` and `START TRANSACTION` aren't keywords yet; use `ABORT` and `BEGIN`.
- `CREATE TABLE`, `DROP TABLE`, `LIMIT`, and `AS` aliases are bonus features still in progress.
- A transaction waiting for a lock waits until the holder commits or aborts, or until it is picked as a deadlock victim; there is no lock timeout.
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
├── diagrams/           # architecture and sequence diagrams
└── generated/          # class diagrams of the api/ and common/ contracts
scripts/                # launchers (run.sh, run.cmd)
test-scenarios/         # scenario files for the component drivers
```

Each package has a `package-info.java` naming its owner and the spec requirements it covers.

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
