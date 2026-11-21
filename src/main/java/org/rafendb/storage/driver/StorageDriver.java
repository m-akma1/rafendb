package org.rafendb.storage.driver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.Component;
import org.rafendb.common.error.RecoveryInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.log.LogType;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.driver.Driver;
import org.rafendb.driver.DriverException;
import org.rafendb.engine.Engine;

/**
 * Standalone storage driver using the shared command runner. Data and WAL live in data/ by
 * default. Each write command is one transaction; startup recovers before accepting commands.
 */
public final class StorageDriver extends Driver {
    private final Map<String, Command> actions = new HashMap<>();
    private Engine.DriverStorage resources;
    private StorageManager storage;
    private RecoveryManager recovery;
    private long lastTransactionId;
    private TxId transaction;
    private boolean rollbackFailed;
    private Rid lastRid;

    /** Creates a driver; resources are opened by the shared runner during setup. */
    public StorageDriver() {
        super(Component.SM);
    }

    /** Entry point; flags use --name=value, as in the shared driver kit. */
    public static void main(String[] args) {
        new StorageDriver().launch(args);
    }

    @Override
    protected void setup() throws Exception {
        if (mocked(Component.FRM)) {
            throw new DriverException("the persistent storage driver requires a real FRM");
        }
        Path directory = Path.of(option("dir", "data")).toAbsolutePath().normalize();
        int frames = Integer.parseInt(option("frames", "64"));
        if (frames <= 0) throw new DriverException("--frames must be positive");
        resources = Engine.storageDriver(directory, frames);
        storage = resources.storage();
        recovery = resources.recovery();
        lastTransactionId = resources.maxTransactionId();
        lastRid = null;
        rollbackFailed = false;
        actions.clear();
        out().println("Database directory: " + directory);
        command("tables", (args, rest) -> out().println(storage.listTables()));
        command("schema TABLE", (args, rest) -> schema(args.get(0)));
        command(
                "indexes TABLE",
                (args, rest) -> storage.listIndexes(args.get(0)).forEach(out()::println));
        command(
                "scan TABLE [limit]",
                (args, rest) ->
                        scan(
                                args.get(0),
                                args.size() > 1
                                        ? Integer.parseInt(args.get(1))
                                        : Integer.MAX_VALUE));
        command(
                "fetch TABLE RID",
                (args, rest) ->
                        out().println(
                                        storage.fetch(args.get(0), rid(args.get(1)))
                                                .map(this::format)
                                                .orElse("absent")));
        command(
                "insert TABLE v1 v2 ...",
                (args, rest) -> {
                    lastRid = write(() -> insert(args.get(0), args.subList(1, args.size())));
                    out().println("inserted at " + lastRid.block() + ":" + lastRid.slot());
                });
        command(
                "update TABLE RID v1 v2 ...",
                (args, rest) -> {
                    if (args.size() < 3)
                        throw new DriverException("usage: update TABLE RID v1 v2 ...");
                    write(
                            () -> {
                                update(args.get(0), rid(args.get(1)), args.subList(2, args.size()));
                                return null;
                            });
                    out().println("updated");
                });
        command(
                "delete TABLE RID",
                (args, rest) -> out().println(write(() -> delete(args.get(0), rid(args.get(1))))));
        command(
                "clear TABLE",
                (args, rest) -> {
                    String table = args.get(0);
                    out().println(
                                    write(
                                            () ->
                                                    storage.clearTable(
                                                            table, row -> logDelete(table, row))));
                });
        command("stats TABLE", (args, rest) -> out().println(storage.getStats(args.get(0))));
        command(
                "drop TABLE",
                (args, rest) -> {
                    if (rollbackFailed)
                        throw new StorageInternalException("restart after failed rollback");
                    storage.dropTable(args.get(0), false);
                });
        command(
                "sample",
                (args, rest) ->
                        write(
                                () -> {
                                    storage.loadSampleDatabase(
                                            change -> log(LogType.INSERT, change));
                                    return null;
                                }));
        command("flush", (args, rest) -> storage.flush());
        command("createindex INDEX TABLE COLUMN btree|hash", (args, rest) -> createIndex(args));
        command(
                "lookup INDEX TYPE VALUE",
                (args, rest) ->
                        out().println(
                                        storage.lookupIndex(
                                                args.get(0), value(args.get(1), args.get(2)))));
        command(
                "range INDEX TYPE LOWER INCLUDE_LOWER UPPER INCLUDE_UPPER",
                (args, rest) -> out().println(range(args)));
        command("assert count|fetch|row|lookup|range|fail ...", this::check);
    }

    /** Retains actions locally for assert-fail; the shared Driver owns normal dispatch. */
    private void command(String usage, Command action) {
        String name = usage.split("\\s+", 2)[0];
        actions.put(name, action);
        super.command(name, usage, "", action);
    }

    @Override
    protected void help() {
        super.help();
        out().println(
                        "  --dir=PATH     database directory (default data/ in the working directory)");
        out().println("  --frames=N     buffer frames (default 64)");
        out().println("RID: block:slot or $last; values: NULL or quoted text with spaces.");
        out().println(
                        "update replaces the full row at the same RID; a larger row may be rejected.");
        out().println("createindex INDEX TABLE COLUMN btree|hash is unlogged DDL.");
        out().println("range INDEX TYPE LOWER INCLUDE_LOWER UPPER INCLUDE_UPPER");
        out().println("Range bounds: * is unbounded; inclusion flags must be true or false.");
        out().println("assert count T N | assert fetch T RID present|absent");
        out().println("assert row TABLE RID v1 v2 ... | assert lookup INDEX TYPE VALUE N");
        out().println("assert range INDEX TYPE LOWER INCLUDE_LOWER UPPER INCLUDE_UPPER N");
        out().println("assert fail COMMAND...");
    }

    @Override
    protected void close() throws Exception {
        if (resources != null) {
            try {
                resources.close();
            } finally {
                resources = null;
            }
        }
    }

    private void schema(String table) {
        for (Column c : storage.getSchema(table).columns()) {
            out().printf(
                            "%s %s(%d)%s%s%n",
                            c.name(),
                            c.type(),
                            c.length(),
                            c.nullable() ? " nullable" : "",
                            c.primaryKey() ? " pk" : "");
        }
    }

    /** Prints up to limit rows of a table. */
    private void scan(String table, int limit) throws Exception {
        int shown = 0;
        try (RecordCursor rows = storage.scan(table)) {
            StoredRow row;
            while (shown < limit && (row = rows.next()) != null) {
                out().println(format(row));
                shown++;
            }
        }
        out().println(shown + " row(s)");
    }

    /** Reserves a RID, logs the image, then inserts; a failed reservation is cancelled. */
    private Rid insert(String table, List<String> tokens) throws Exception {
        Row row = row(table, tokens);
        Rid rid = storage.reserveRid(table, row);
        try {
            long lsn =
                    log(
                            LogType.INSERT,
                            new ChangeRecord(
                                    ResourceId.ofRow(table, rid),
                                    Optional.empty(),
                                    Optional.of(storage.encodeRow(table, row))));
            storage.insert(table, rid, row, lsn);
        } catch (Exception e) {
            try {
                storage.cancelReservation(table, rid);
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return rid;
    }

    private Row row(String table, List<String> tokens) {
        List<Column> columns = storage.getSchema(table).columns();
        if (tokens.size() != columns.size()) {
            throw new IllegalArgumentException(
                    "expected " + columns.size() + " values, got " + tokens.size());
        }
        List<Value> values = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            values.add(value(columns.get(i).type(), tokens.get(i)));
        }
        return new Row(values);
    }

    /** Logs both images before replacing a row; write() rolls back a rejected update. */
    private void update(String table, Rid rid, List<String> tokens) throws Exception {
        StoredRow before =
                storage.fetch(table, rid)
                        .orElseThrow(() -> new DriverException("no live record at " + rid));
        Row after = row(table, tokens);
        long lsn =
                log(
                        LogType.UPDATE,
                        new ChangeRecord(
                                ResourceId.ofRow(table, rid),
                                Optional.of(storage.encodeRow(table, before.row())),
                                Optional.of(storage.encodeRow(table, after))));
        storage.update(table, rid, after, lsn);
    }

    private void createIndex(List<String> args) throws StorageInternalException {
        if (args.size() != 4)
            throw new DriverException("usage: createindex INDEX TABLE COLUMN btree|hash");
        if (rollbackFailed) throw new StorageInternalException("restart after failed rollback");
        IndexType type =
                switch (args.get(3).toLowerCase(Locale.ROOT)) {
                    case "btree" -> IndexType.B_PLUS_TREE;
                    case "hash" -> IndexType.HASH;
                    default -> throw new DriverException("index type must be btree or hash");
                };
        storage.createIndex(new IndexInfo(args.get(0), args.get(1), args.get(2), type));
        out().println("index created: " + args.get(0));
    }

    private List<Rid> range(List<String> args) throws StorageInternalException {
        if (args.size() != 6)
            throw new DriverException(
                    "usage: range INDEX TYPE LOWER INCLUDE_LOWER UPPER INCLUDE_UPPER");
        String type = args.get(1);
        return storage.rangeIndex(
                args.get(0),
                args.get(2).equals("*") ? null : value(type, args.get(2)),
                inclusive(args.get(3)),
                args.get(4).equals("*") ? null : value(type, args.get(4)),
                inclusive(args.get(5)));
    }

    private boolean inclusive(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new DriverException("range inclusion must be true or false");
        };
    }

    /** Logs the before-image before deleting an existing row. */
    private boolean delete(String table, Rid rid) throws Exception {
        Optional<StoredRow> row = storage.fetch(table, rid);
        return row.isPresent() && storage.delete(table, rid, logDelete(table, row.get()));
    }

    private long logDelete(String table, StoredRow row) {
        return log(
                LogType.DELETE,
                new ChangeRecord(
                        ResourceId.ofRow(table, row.rid()),
                        Optional.of(storage.encodeRow(table, row.row())),
                        Optional.empty()));
    }

    private long log(LogType type, ChangeRecord change) {
        try {
            return recovery.writeLog(LogPayload.ofChange(transaction, type, change));
        } catch (RecoveryInternalException e) {
            throw new IllegalStateException("cannot write storage driver's WAL", e);
        }
    }

    /** Each write is committed independently; a failed rollback blocks subsequent mutations. */
    private <T> T write(Callable<T> action) throws Exception {
        if (rollbackFailed) throw new StorageInternalException("restart after failed rollback");
        if (lastTransactionId >= Integer.MAX_VALUE) {
            throw new StorageInternalException("transaction identifiers are exhausted");
        }
        transaction = new TxId(++lastTransactionId);
        if (!recovery.beginTransaction(transaction)) {
            throw new StorageInternalException("cannot begin storage driver transaction");
        }
        try {
            T result = action.call();
            if (!recovery.commitTransaction(transaction)) {
                throw new StorageInternalException("cannot commit storage driver transaction");
            }
            return result;
        } catch (Exception failure) {
            if (!recovery.rollbackTransaction(transaction)) {
                rollbackFailed = true;
                failure.addSuppressed(
                        new StorageInternalException("rollback failed; restart the driver"));
            }
            throw failure;
        } finally {
            transaction = null;
        }
    }

    /** Nested checks reuse the parsed arguments and original quoted text from the shared runner. */
    private void check(List<String> t, String rest) throws Exception {
        switch (t.get(0)) {
            case "count" ->
                    check(
                            "count " + t.get(1),
                            Long.parseLong(t.get(2)),
                            storage.getStats(t.get(1)).recordCount());
            case "fetch" -> {
                boolean present = storage.fetch(t.get(1), rid(t.get(2))).isPresent();
                check("fetch " + t.get(2), t.get(3).equals("present"), present);
            }
            case "row" -> {
                if (t.size() < 4)
                    throw new DriverException("usage: assert row TABLE RID v1 v2 ...");
                check(
                        "row " + t.get(1) + " " + t.get(2),
                        Optional.of(row(t.get(1), t.subList(3, t.size()))),
                        storage.fetch(t.get(1), rid(t.get(2))).map(StoredRow::row));
            }
            case "lookup" -> {
                int size = storage.lookupIndex(t.get(1), value(t.get(2), t.get(3))).size();
                check("lookup " + t.get(1), Integer.parseInt(t.get(4)), size);
            }
            case "range" -> {
                if (t.size() != 8)
                    throw new DriverException(
                            "usage: assert range INDEX TYPE LOWER INCLUDE_LOWER UPPER INCLUDE_UPPER N");
                check(
                        "range " + t.get(1),
                        Integer.parseInt(t.get(7)),
                        range(t.subList(1, 7)).size());
            }
            case "fail" -> {
                if (t.size() < 2) throw new DriverException("usage: assert fail COMMAND...");
                Command action = actions.get(t.get(1).toLowerCase(Locale.ROOT));
                if (action == null) throw new DriverException("unknown command: " + t.get(1));
                try {
                    String[] parts = rest.split("\\s+", 3);
                    action.execute(t.subList(2, t.size()), parts.length == 3 ? parts[2] : "");
                    check("expected failure: " + String.join(" ", t.subList(1, t.size())), false);
                } catch (Exception e) {
                    check("failed as expected: " + e.getClass().getSimpleName(), true);
                }
            }
            default -> throw new IllegalArgumentException("unknown assert: " + t.get(0));
        }
    }

    /** Formats a stored row as "block:slot | v1 | v2". */
    private String format(StoredRow row) {
        String values =
                row.row().values().stream().map(Value::toString).collect(Collectors.joining(" | "));
        return row.rid().block() + ":" + row.rid().slot() + " | " + values;
    }

    /** Reads "block:slot", or "$last" for the latest inserted RID. */
    private Rid rid(String text) {
        if (text.equals("$last")) {
            if (lastRid == null) {
                throw new IllegalStateException("no insert yet");
            }
            return lastRid;
        }
        String[] parts = text.split(":");
        return new Rid(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
    }

    private Value value(String type, String text) {
        return value(
                type.equalsIgnoreCase("int")
                        ? DataType.INTEGER
                        : DataType.valueOf(type.toUpperCase(Locale.ROOT)),
                text);
    }

    /** One value parser for schema-driven INSERTs and explicitly typed index lookups. */
    private Value value(DataType type, String text) {
        if (text.equals("NULL")) {
            return Value.nullOf(type);
        }
        return switch (type) {
            case INTEGER -> Value.ofInt(Integer.parseInt(text));
            case FLOAT -> Value.ofFloat(Float.parseFloat(text));
            case CHAR, VARCHAR -> Value.ofText(type, text);
        };
    }
}
