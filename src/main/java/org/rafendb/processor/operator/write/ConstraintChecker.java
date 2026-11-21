package org.rafendb.processor.operator.write;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.StorageQuery;
import org.rafendb.common.error.ProcessorRuntimeException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.ForeignKey;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.ReferentialAction;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.type.Value;
import org.rafendb.processor.api.SessionView;
import org.rafendb.processor.txn.TableLocks;

/**
 * Enforces primary keys and foreign keys for one INSERT, UPDATE or DELETE, after the operator has
 * worked out every row it will write and before it logs or writes any of them. A violation throws
 * {@link ProcessorRuntimeException}, so the table and the WAL are untouched and the session fails
 * the statement like any other user error.
 *
 * <ul>
 *   <li>Primary key: no two rows of a table share a key. Checked on INSERT, and on UPDATE when the
 *       statement changes a key column.
 *   <li>Foreign key, child side: a key with no NULL column must match a row of the referenced
 *       table. Checked on INSERT, and on UPDATE when the statement changes a referencing column.
 *   <li>Foreign key, parent side: a key that disappears from a referenced table (DELETE, or UPDATE
 *       of a referenced column) must not be referenced. {@link ReferentialAction#RESTRICT} rejects
 *       the statement; {@link ReferentialAction#CASCADE} is reported as unsupported.
 * </ul>
 *
 * <p>The operator already holds WRITE access to its own table. Reading another table takes READ
 * access to it, held until the transaction ends like every other lock, so the rows the check
 * relied on cannot change before commit. Key lookups use an index on a single-column key when one
 * exists, and otherwise scan the table once per check.
 *
 * <p>One instance per statement; not thread-safe.
 */
final class ConstraintChecker {
    private final SessionView session;
    private final StorageQuery storage;

    ConstraintChecker(SessionView session) {
        this.session = Objects.requireNonNull(session, "session");
        this.storage = Objects.requireNonNull(session.storageDQL(), "storage");
    }

    /**
     * Checks one new row of {@code table}.
     *
     * @param table the table the row goes into
     * @param row the full row, in table column order
     * @throws ProcessorRuntimeException if the row duplicates a primary key or references a
     *     missing row
     */
    void checkInsert(String table, Row row) {
        TableSchema schema = storage.getSchema(table);
        List<Row> rows = List.of(row);
        checkPrimaryKey(table, schema, rows, Set.of());
        for (ForeignKey key : schema.foreignKeys()) {
            checkReferencesExist(table, schema, key, rows);
        }
    }

    /**
     * Checks the rows of {@code table} an UPDATE changes. Only constraints on a column whose value
     * actually changes in some row are checked.
     *
     * @param table the updated table
     * @param rids the Rid of each target row
     * @param before each target row as it is now
     * @param after each target row as it will be, in the same order
     * @throws ProcessorRuntimeException on a duplicate primary key, a reference to a missing row,
     *     or a referenced key that would disappear
     */
    void checkUpdate(String table, List<Rid> rids, List<Row> before, List<Row> after) {
        TableSchema schema = storage.getSchema(table);
        Set<Integer> changed = changedColumns(schema, before, after);
        if (changed.isEmpty()) {
            return;
        }
        List<Integer> primaryKey = primaryKeyColumns(schema);
        if (!primaryKey.isEmpty() && touches(primaryKey, changed)) {
            checkPrimaryKey(table, schema, after, new HashSet<>(rids));
        }
        for (ForeignKey key : schema.foreignKeys()) {
            if (touches(columns(schema, key.columns()), changed)) {
                checkReferencesExist(table, schema, key, after);
            }
        }
        for (Referrer referrer : referrers(table)) {
            List<Integer> referenced = columns(schema, referrer.key().referencedColumns());
            if (touches(referenced, changed)) {
                Set<List<Value>> gone = keys(before, referenced);
                gone.removeAll(keys(after, referenced));
                checkNotReferenced(table, referrer, gone, Set.of(), "change");
            }
        }
    }

    /**
     * Checks the rows of {@code table} a DELETE removes.
     *
     * @param table the table rows are deleted from
     * @param rids the Rid of each deleted row
     * @param rows each deleted row, in the same order
     * @throws ProcessorRuntimeException if a row that stays still references a deleted row
     */
    void checkDelete(String table, List<Rid> rids, List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        TableSchema schema = storage.getSchema(table);
        for (Referrer referrer : referrers(table)) {
            List<Integer> referenced = columns(schema, referrer.key().referencedColumns());
            // A self-referencing table may delete a parent together with all its children.
            Set<Rid> leaving =
                    referrer.table().equalsIgnoreCase(table) ? new HashSet<>(rids) : Set.of();
            checkNotReferenced(table, referrer, keys(rows, referenced), leaving, "delete from");
        }
    }

    // ---------------------------------------------------------------------------------------
    // The three checks
    // ---------------------------------------------------------------------------------------

    /** No two rows share a key: neither two new rows, nor a new row and a row that stays. */
    private void checkPrimaryKey(
            String table, TableSchema schema, List<Row> rows, Set<Rid> replaced) {
        List<Integer> primaryKey = primaryKeyColumns(schema);
        if (primaryKey.isEmpty()) {
            return;
        }
        Set<List<Value>> wanted = new LinkedHashSet<>();
        for (Row row : rows) {
            List<Value> key = key(row, primaryKey);
            if (!wanted.add(key)) {
                throw duplicate(table, schema, primaryKey, key);
            }
        }
        Set<List<Value>> taken = present(table, schema, primaryKey, wanted, replaced);
        if (!taken.isEmpty()) {
            throw duplicate(table, schema, primaryKey, taken.iterator().next());
        }
    }

    /** Every non-NULL key of {@code rows} under {@code key} matches a row of its parent table. */
    private void checkReferencesExist(
            String table, TableSchema schema, ForeignKey key, List<Row> rows) {
        List<Integer> local = columns(schema, key.columns());
        Set<List<Value>> wanted = keys(rows, local);
        if (wanted.isEmpty()) {
            return;
        }
        String parentTable = key.referencedTable();
        lockForReading(table, parentTable);
        TableSchema parent = storage.getSchema(parentTable);
        List<Integer> remote = columns(parent, key.referencedColumns());
        // New rows of a self-referencing table may reference each other.
        if (parentTable.equalsIgnoreCase(table)) {
            wanted.removeAll(keys(rows, remote));
        }
        Set<List<Value>> missing = new LinkedHashSet<>(wanted);
        missing.removeAll(present(parentTable, parent, remote, wanted, Set.of()));
        if (!missing.isEmpty()) {
            throw new ProcessorRuntimeException(
                    "Foreign key "
                            + key.name()
                            + " on "
                            + table
                            + " is violated: no row of "
                            + parentTable
                            + " has "
                            + describe(parent, remote, missing.iterator().next())
                            + ".");
        }
    }

    /** No row of the referrer, other than those leaving with the parent, uses a key in gone. */
    private void checkNotReferenced(
            String table, Referrer referrer, Set<List<Value>> gone, Set<Rid> leaving, String verb) {
        if (gone.isEmpty()) {
            return;
        }
        ForeignKey key = referrer.key();
        lockForReading(table, referrer.table());
        TableSchema child = storage.getSchema(referrer.table());
        List<Integer> local = columns(child, key.columns());
        Set<List<Value>> used = present(referrer.table(), child, local, gone, leaving);
        if (used.isEmpty()) {
            return;
        }
        String where = referrer.table() + "." + key.name();
        String keyText = describe(child, local, used.iterator().next());
        if (key.onDelete() == ReferentialAction.CASCADE) {
            throw new ProcessorRuntimeException(
                    "Cannot "
                            + verb
                            + " "
                            + table
                            + ": ON DELETE CASCADE of "
                            + where
                            + " is not supported, and a row still has "
                            + keyText
                            + ".");
        }
        throw new ProcessorRuntimeException(
                "Cannot "
                        + verb
                        + " "
                        + table
                        + ": a row of "
                        + referrer.table()
                        + " still references it through "
                        + where
                        + " ("
                        + keyText
                        + ").");
    }

    // ---------------------------------------------------------------------------------------
    // Reading keys
    // ---------------------------------------------------------------------------------------

    /**
     * Returns the keys of {@code wanted} that some row of {@code table} has in {@code columns},
     * ignoring the rows in {@code ignored}.
     */
    private Set<List<Value>> present(
            String table,
            TableSchema schema,
            List<Integer> columns,
            Set<List<Value>> wanted,
            Set<Rid> ignored) {
        Set<List<Value>> found = new HashSet<>();
        if (wanted.isEmpty()) {
            return found;
        }
        Optional<IndexInfo> index = usableIndex(table, schema, columns, wanted);
        try {
            if (index.isPresent()) {
                for (List<Value> key : wanted) {
                    for (Rid rid : storage.lookupIndex(index.get().name(), key.getFirst())) {
                        if (ignored.contains(rid)) continue;
                        Optional<StoredRow> stored = storage.fetch(table, rid);
                        if (stored.isPresent() && key(stored.get().row(), columns).equals(key)) {
                            found.add(key);
                            break;
                        }
                    }
                }
                return found;
            }
            try (RecordCursor cursor = storage.scan(table)) {
                StoredRow stored;
                while ((stored = cursor.next()) != null && found.size() < wanted.size()) {
                    if (ignored.contains(stored.rid())) continue;
                    List<Value> key = key(stored.row(), columns);
                    if (wanted.contains(key)) {
                        found.add(key);
                    }
                }
            }
            return found;
        } catch (StorageInternalException e) {
            throw new IllegalStateException(
                    "Storage failure while checking the keys of " + table, e);
        }
    }

    /** An index answers the lookup when the key is one column and every key has its type. */
    private Optional<IndexInfo> usableIndex(
            String table, TableSchema schema, List<Integer> columns, Set<List<Value>> wanted) {
        if (columns.size() != 1) {
            return Optional.empty();
        }
        Column column = schema.columns().get(columns.getFirst());
        for (List<Value> key : wanted) {
            if (key.getFirst().type() != column.type()) {
                return Optional.empty();
            }
        }
        return storage.listIndexes(table).stream()
                .filter(i -> i.column().equalsIgnoreCase(column.name()))
                .findFirst();
    }

    /** A foreign key that references {@code table}, and the table that declares it. */
    private record Referrer(String table, ForeignKey key) {}

    private List<Referrer> referrers(String table) {
        List<Referrer> found = new ArrayList<>();
        for (String other : storage.listTables()) {
            for (ForeignKey key : storage.getSchema(other).foreignKeys()) {
                if (key.referencedTable().equalsIgnoreCase(table)) {
                    found.add(new Referrer(other, key));
                }
            }
        }
        return found;
    }

    /** The statement's own table is already locked for writing. */
    private void lockForReading(String table, String other) {
        if (!other.equalsIgnoreCase(table)) {
            TableLocks.acquireRead(session, other);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------

    private static List<Integer> primaryKeyColumns(TableSchema schema) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < schema.columns().size(); i++) {
            if (schema.columns().get(i).primaryKey()) found.add(i);
        }
        return found;
    }

    private static List<Integer> columns(TableSchema schema, List<String> names) {
        List<Integer> found = new ArrayList<>(names.size());
        for (String name : names) {
            int index = -1;
            for (int i = 0; i < schema.columns().size(); i++) {
                if (schema.columns().get(i).name().equalsIgnoreCase(name)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                throw new IllegalStateException(
                        "Constraint column " + name + " is not in table " + schema.table());
            }
            found.add(index);
        }
        return found;
    }

    private static Set<Integer> changedColumns(
            TableSchema schema, List<Row> before, List<Row> after) {
        Set<Integer> changed = new HashSet<>();
        for (int r = 0; r < before.size(); r++) {
            for (int c = 0; c < schema.columns().size(); c++) {
                if (!before.get(r).values().get(c).equals(after.get(r).values().get(c))) {
                    changed.add(c);
                }
            }
        }
        return changed;
    }

    private static boolean touches(List<Integer> columns, Set<Integer> changed) {
        for (int c : columns) {
            if (changed.contains(c)) return true;
        }
        return false;
    }

    private static List<Value> key(Row row, List<Integer> columns) {
        List<Value> key = new ArrayList<>(columns.size());
        for (int c : columns) {
            key.add(row.values().get(c));
        }
        return key;
    }

    /** The keys of the rows, skipping keys with a NULL column: they reference nothing. */
    private static Set<List<Value>> keys(List<Row> rows, List<Integer> columns) {
        Set<List<Value>> keys = new LinkedHashSet<>();
        for (Row row : rows) {
            List<Value> key = key(row, columns);
            if (key.stream().noneMatch(Value::isNull)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static ProcessorRuntimeException duplicate(
            String table, TableSchema schema, List<Integer> columns, List<Value> key) {
        return new ProcessorRuntimeException(
                "Duplicate primary key in " + table + ": " + describe(schema, columns, key) + ".");
    }

    /** {@code id = 1}, or {@code (a, b) = (1, 'x')} for a composite key. */
    private static String describe(TableSchema schema, List<Integer> columns, List<Value> key) {
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            names.add(schema.columns().get(columns.get(i)).name());
            values.add(String.valueOf(key.get(i)));
        }
        return columns.size() == 1
                ? names.getFirst() + " = " + values.getFirst()
                : "(" + String.join(", ", names) + ") = (" + String.join(", ", values) + ")";
    }
}
