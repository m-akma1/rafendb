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
        command(
                "lookup INDEX TYPE VALUE",
                (args, rest) ->
                        out().println(
                                        storage.lookupIndex(
                                                args.get(0), value(args.get(1), args.get(2)))));
        command("assert count|fetch|lookup|fail ...", this::check);
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
        out().println("assert count T N | assert fetch T RID present|absent");
        out().println("assert lookup INDEX TYPE VALUE N | assert fail COMMAND...");
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
        List<Column> columns = storage.getSchema(table).columns();
        if (tokens.size() != columns.size()) {
            throw new IllegalArgumentException(
                    "expected " + columns.size() + " values, got " + tokens.size());
        }
        List<Value> values = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            values.add(value(columns.get(i).type(), tokens.get(i)));
        }
        Row row = new Row(values);
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
            case "lookup" -> {
                int size = storage.lookupIndex(t.get(1), value(t.get(2), t.get(3))).size();
                check("lookup " + t.get(1), Integer.parseInt(t.get(4)), size);
            }
            case "fail" -> {
                if (t.size() < 2) throw new DriverException("usage: assert fail COMMAND...");
                try {
                    Command action = actions.get(t.get(1).toLowerCase(Locale.ROOT));
                    if (action == null) throw new DriverException("unknown command: " + t.get(1));
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
