package org.rafendb.driver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.engine.Engine;

/**
 * Standalone driver for the Storage Manager. Reads commands from the keyboard or from a scenario
 * file, keeps going after an error, and reports how many checks passed. Writes use a local
 * counter as the LSN because no recovery manager is attached.
 */
public final class StorageDriver {
    private final StorageManager storage;
    private final PrintStream out;
    private long nextLsn = 1;
    private Rid lastRid;
    private int passed;
    private int failed;

    /** Creates a driver that talks to the given Storage Manager and prints to the given stream. */
    public StorageDriver(StorageManager storage, PrintStream out) {
        this.storage = storage;
        this.out = out;
    }

    /**
     * Entry point. Options: --dir path (default: a new temporary folder), --frames n (default 64),
     * --run file (default: interactive).
     */
    public static void main(String[] args) throws Exception {
        Path dir = null;
        int frames = 64;
        Path script = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir" -> dir = Path.of(args[++i]);
                case "--frames" -> frames = Integer.parseInt(args[++i]);
                case "--run" -> script = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        if (dir == null) {
            dir = Files.createTempDirectory("rafendb-storage");
        } else {
            Files.createDirectories(dir);
        }
        StorageManager storage = Engine.storage(dir, frames, lsn -> {});
        StorageDriver driver = new StorageDriver(storage, System.out);
        try {
            if (script != null) {
                driver.runScenario(script);
            } else {
                driver.run();
            }
        } finally {
            storage.close();
        }
        if (driver.failed > 0) {
            System.exit(1);
        }
    }

    /** Reads commands from standard input until "quit" or end of input. */
    public void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        out.println("StorageDriver ready. Type help, or quit to leave.");
        while (true) {
            out.print("sm> ");
            out.flush();
            String line = in.readLine();
            if (line == null || line.strip().equals("quit")) {
                return;
            }
            execute(line);
        }
    }

    /** Runs one command; an error is printed and counted, and the driver keeps going. */
    public void execute(String command) {
        try {
            dispatch(tokenize(command));
        } catch (Exception e) {
            failed++;
            out.println("ERROR: " + e);
        }
    }

    /** Runs every non-empty, non-comment line of a file as a command and prints a summary. */
    public void runScenario(Path file) throws IOException {
        for (String line : Files.readAllLines(file)) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            out.println("> " + text);
            execute(text);
        }
        out.println("RESULT: " + passed + " passed, " + failed + " failed");
    }

    /** Executes one tokenized command. */
    private void dispatch(List<String> t) throws Exception {
        if (t.isEmpty()) {
            return;
        }
        switch (t.get(0).toLowerCase()) {
            case "help" -> help();
            case "tables" -> out.println(storage.listTables());
            case "schema" -> schema(t.get(1));
            case "indexes" -> storage.listIndexes(t.get(1)).forEach(out::println);
            case "scan" ->
                    scan(t.get(1), t.size() > 2 ? Integer.parseInt(t.get(2)) : Integer.MAX_VALUE);
            case "fetch" -> fetch(t.get(1), rid(t.get(2)));
            case "insert" -> insert(t.get(1), t.subList(2, t.size()));
            case "delete" -> out.println(storage.delete(t.get(1), rid(t.get(2)), nextLsn++));
            case "clear" -> out.println(storage.clearTable(t.get(1), row -> nextLsn++));
            case "stats" -> out.println(storage.getStats(t.get(1)));
            case "drop" -> storage.dropTable(t.get(1), false);
            case "sample" -> storage.loadSampleDatabase();
            case "flush" -> storage.flush();
            case "lookup" -> out.println(storage.lookupIndex(t.get(1), value(t.get(2), t.get(3))));
            case "assert" -> check(t.subList(1, t.size()));
            default -> throw new IllegalArgumentException("unknown command: " + t.get(0));
        }
    }

    /** Prints the command list. */
    private void help() {
        out.println("tables | schema T | indexes T | scan T [limit] | fetch T RID | stats T");
        out.println("insert T v1 v2 ... (NULL for null, quotes for text with spaces)");
        out.println("delete T RID | clear T | drop T | sample | flush");
        out.println("lookup INDEX int|float|char|varchar VALUE");
        out.println("RID is block:slot, or $last for the RID of the latest insert");
        out.println("assert count T N | assert fetch T RID present|absent");
        out.println("assert lookup INDEX TYPE VALUE N | assert fail COMMAND...");
    }

    /** Prints the columns of a table. */
    private void schema(String table) {
        TableSchema schema = storage.getSchema(table);
        for (Column c : schema.columns()) {
            out.println(
                    c.name()
                            + " "
                            + c.type()
                            + "("
                            + c.length()
                            + ")"
                            + (c.nullable() ? " nullable" : "")
                            + (c.primaryKey() ? " pk" : ""));
        }
    }

    /** Prints up to limit rows of a table. */
    private void scan(String table, int limit) throws Exception {
        int shown = 0;
        try (RecordCursor rows = storage.scan(table)) {
            StoredRow row;
            while (shown < limit && (row = rows.next()) != null) {
                out.println(format(row));
                shown++;
            }
        }
        out.println(shown + " row(s)");
    }

    /** Prints one record or "absent". */
    private void fetch(String table, Rid rid) throws Exception {
        Optional<StoredRow> row = storage.fetch(table, rid);
        out.println(row.isPresent() ? format(row.get()) : "absent");
    }

    /** Reserves a RID, then inserts the row with a fresh LSN. */
    private void insert(String table, List<String> tokens) throws Exception {
        TableSchema schema = storage.getSchema(table);
        List<Column> columns = schema.columns();
        if (tokens.size() != columns.size()) {
            throw new IllegalArgumentException(
                    "expected " + columns.size() + " values, got " + tokens.size());
        }
        List<Value> values = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            values.add(parse(columns.get(i), tokens.get(i)));
        }
        Row row = new Row(values);
        Rid rid = storage.reserveRid(table, row);
        try {
            storage.insert(table, rid, row, nextLsn++);
        } catch (Exception e) {
            storage.cancelReservation(table, rid);
            throw e;
        }
        lastRid = rid;
        out.println("inserted at " + rid.block() + ":" + rid.slot());
    }

    /** Evaluates an assert command and records pass or fail. */
    private void check(List<String> t) throws Exception {
        switch (t.get(0)) {
            case "count" ->
                    verify(count(t.get(1)) == Long.parseLong(t.get(2)), "count " + t.get(1));
            case "fetch" -> {
                boolean present = storage.fetch(t.get(1), rid(t.get(2))).isPresent();
                verify(present == t.get(3).equals("present"), "fetch " + t.get(2));
            }
            case "lookup" -> {
                int size = storage.lookupIndex(t.get(1), value(t.get(2), t.get(3))).size();
                verify(size == Integer.parseInt(t.get(4)), "lookup " + t.get(1));
            }
            case "fail" -> {
                try {
                    dispatch(t.subList(1, t.size()));
                    verify(false, "expected failure: " + String.join(" ", t.subList(1, t.size())));
                } catch (Exception e) {
                    verify(true, "failed as expected: " + e.getClass().getSimpleName());
                }
            }
            default -> throw new IllegalArgumentException("unknown assert: " + t.get(0));
        }
    }

    /** Counts the live rows of a table. */
    private long count(String table) throws Exception {
        long n = 0;
        try (RecordCursor rows = storage.scan(table)) {
            while (rows.next() != null) {
                n++;
            }
        }
        return n;
    }

    /** Records one check result. */
    private void verify(boolean ok, String what) {
        if (ok) {
            passed++;
            out.println("PASS " + what);
        } else {
            failed++;
            out.println("FAIL " + what);
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

    /** Reads a value of a named type, used by index lookups. */
    private Value value(String type, String text) {
        return switch (type.toLowerCase()) {
            case "int" -> Value.ofInt(Integer.parseInt(text));
            case "float" -> Value.ofFloat(Float.parseFloat(text));
            case "char" -> Value.ofText(DataType.CHAR, text);
            case "varchar" -> Value.ofText(DataType.VARCHAR, text);
            default -> throw new IllegalArgumentException("unknown type: " + type);
        };
    }

    /** Reads a value for a column, or NULL. */
    private Value parse(Column column, String text) {
        if (text.equals("NULL")) {
            return Value.nullOf(column.type());
        }
        return switch (column.type()) {
            case INTEGER -> Value.ofInt(Integer.parseInt(text));
            case FLOAT -> Value.ofFloat(Float.parseFloat(text));
            case CHAR, VARCHAR -> Value.ofText(column.type(), text);
        };
    }

    /** Splits a line on spaces; double quotes keep spaces inside one token. */
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean has = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
                has = true;
            } else if (Character.isWhitespace(ch) && !quoted) {
                if (has) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    has = false;
                }
            } else {
                current.append(ch);
                has = true;
            }
        }
        if (has) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
