package org.rafendb.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.rafendb.api.storage.StorageMetadata;
import org.rafendb.common.error.GeneralRuntimeException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.query.QueryResult;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.ForeignKey;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/**
 * The server-side meta-commands a client sends as one line, such as {@code \d customers}. They
 * inspect the catalog and statistics (SM-08, SM-09, SM-10) without locks and never touch a
 * session's transaction, so they show the storage as it is now, including changes other sessions
 * have not committed yet.
 *
 * <ul>
 *   <li>{@code \dt}, or {@code \d} alone: the tables.
 *   <li>{@code \d <table>}: its columns, then its indexes and foreign keys when it has any.
 *   <li>{@code \di [table]}: the indexes of one table, or of all.
 *   <li>{@code \stats [table]}: n_r, b_r, l_r and f_r of one table, or of all; for one table also
 *       V(A,r) per column.
 * </ul>
 *
 * <p>Thread-safe as long as the storage metadata is.
 */
public final class MetaCommands {
    /** One titled table of a meta-command's answer. */
    public record Section(String title, QueryResult.Rows rows) {
        /**
         * Checks the rows.
         *
         * @param title shown above the rows, or {@code null} for none
         * @param rows the rows, not null
         */
        public Section {
            Objects.requireNonNull(rows, "rows");
        }
    }

    private final StorageMetadata storage;

    /**
     * Creates the commands over a storage's metadata.
     *
     * @param storage where tables, schemas, indexes and statistics are read from, or {@code null}
     *     when no storage is open, so every command fails
     */
    public MetaCommands(StorageMetadata storage) {
        this.storage = storage;
    }

    /**
     * Runs one meta-command line.
     *
     * @param line the command and its arguments, such as {@code \d customers}
     * @return the sections to show, in order
     * @throws GeneralRuntimeException if the command is unknown, its arguments are wrong, a table
     *     does not exist, or no storage is open
     */
    public List<Section> run(String line) {
        String[] words = line.strip().split("\\s+");
        String command = words[0];
        if (words.length > 2) {
            throw new GeneralRuntimeException(command + " takes at most one table name.");
        }
        String table = words.length == 2 ? words[1] : null;
        if (storage == null) {
            throw new GeneralRuntimeException(
                    command + " is not available: no storage is open with --mock=qp.");
        }
        return switch (command) {
            case "\\dt" -> {
                if (table != null) {
                    throw new GeneralRuntimeException("\\dt takes no argument.");
                }
                yield tables();
            }
            case "\\d" -> table == null ? tables() : describe(table);
            case "\\di" -> List.of(new Section(null, indexes(table, true)));
            case "\\stats" -> stats(table);
            default -> throw new GeneralRuntimeException("Unknown meta-command: " + command + ".");
        };
    }

    private List<Section> tables() {
        List<Row> rows = new ArrayList<>();
        for (String name : storage.listTables()) {
            rows.add(row(name));
        }
        return List.of(new Section(null, new QueryResult.Rows(List.of("table"), rows)));
    }

    private List<Section> describe(String table) {
        TableSchema schema = storage.getSchema(existing(table));
        List<Section> sections = new ArrayList<>();
        List<Row> columns = new ArrayList<>();
        for (Column column : schema.columns()) {
            columns.add(
                    row(
                            column.name(),
                            typeName(column),
                            yesNo(column.nullable()),
                            yesNo(column.primaryKey())));
        }
        sections.add(
                new Section(
                        "Table \"" + table + "\"",
                        new QueryResult.Rows(
                                List.of("column", "type", "nullable", "primary key"), columns)));
        QueryResult.Rows indexes = indexes(table, false);
        if (!indexes.rows().isEmpty()) {
            sections.add(new Section("Indexes", indexes));
        }
        List<Row> keys = new ArrayList<>();
        for (ForeignKey key : schema.foreignKeys()) {
            keys.add(
                    row(
                            key.name(),
                            String.join(", ", key.columns()),
                            key.referencedTable()
                                    + "("
                                    + String.join(", ", key.referencedColumns())
                                    + ")",
                            key.onDelete().name().toLowerCase(Locale.ROOT)));
        }
        if (!keys.isEmpty()) {
            sections.add(
                    new Section(
                            "Foreign keys",
                            new QueryResult.Rows(
                                    List.of("name", "columns", "references", "on delete"), keys)));
        }
        return sections;
    }

    /** The indexes of one table, or of all when {@code table} is null. */
    private QueryResult.Rows indexes(String table, boolean withTable) {
        List<String> tables = table == null ? storage.listTables() : List.of(existing(table));
        List<Row> rows = new ArrayList<>();
        for (String name : tables) {
            for (IndexInfo index : storage.listIndexes(name)) {
                String type =
                        switch (index.type()) {
                            case HASH -> "hash";
                            case B_PLUS_TREE -> "b+tree";
                        };
                rows.add(
                        withTable
                                ? row(index.name(), index.table(), index.column(), type)
                                : row(index.name(), index.column(), type));
            }
        }
        List<String> columns =
                withTable
                        ? List.of("name", "table", "column", "type")
                        : List.of("name", "column", "type");
        return new QueryResult.Rows(columns, rows);
    }

    private List<Section> stats(String table) {
        List<String> tables = table == null ? storage.listTables() : List.of(existing(table));
        List<Row> rows = new ArrayList<>();
        TableStats last = null;
        for (String name : tables) {
            last = statsOf(name);
            rows.add(
                    row(
                            name,
                            Long.toString(last.recordCount()),
                            Long.toString(last.blockCount()),
                            decimal(last.averageRecordSize()),
                            decimal(last.blockingFactor())));
        }
        List<Section> sections = new ArrayList<>();
        sections.add(
                new Section(
                        null,
                        new QueryResult.Rows(List.of("table", "n_r", "b_r", "l_r", "f_r"), rows)));
        if (table != null) {
            List<Row> distinct = new ArrayList<>();
            for (Column column : storage.getSchema(table).columns()) {
                Long count = last.distinctValues().get(column.name());
                distinct.add(row(column.name(), count == null ? "?" : count.toString()));
            }
            sections.add(
                    new Section(
                            "Distinct values",
                            new QueryResult.Rows(List.of("column", "V(A,r)"), distinct)));
        }
        return sections;
    }

    private TableStats statsOf(String table) {
        try {
            return storage.getStats(table);
        } catch (StorageInternalException e) {
            throw new GeneralRuntimeException(
                    "Cannot read the statistics of " + table + ": " + e.getMessage(), e);
        }
    }

    /** Returns the name if the table exists, so a typo is reported here rather than by SM. */
    private String existing(String table) {
        if (!storage.listTables().contains(table)) {
            throw new GeneralRuntimeException("Did not find a table named \"" + table + "\".");
        }
        return table;
    }

    private static String typeName(Column column) {
        String name = column.type().name().toLowerCase(Locale.ROOT);
        return column.type().isText() ? name + "(" + column.length() + ")" : name;
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static Row row(String... cells) {
        List<Value> values = new ArrayList<>(cells.length);
        for (String cell : cells) {
            values.add(Value.ofText(DataType.VARCHAR, cell));
        }
        return new Row(values);
    }
}
