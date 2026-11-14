package org.rafendb.optimizer.binder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.rafendb.common.error.OptimizerRuntimeException.ValidationException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.optimizer.catalog.Catalog;

/**
 * Resolved table scope for one query or one join node.
 *
 * <p>Tables are kept in the order they are written in FROM, and a column's offset is its position
 * in the concatenation of the tables' rows in that order. This matches the row layout that a join
 * or cartesian product produces from its inputs, so an offset is valid for the node whose input is
 * exactly this scope.
 */
final class Scope {
    record Table(String name, TableSchema schema, int offset) {}

    record Resolved(Table table, Column column, int offset) {}

    private final List<Table> tables;
    private final Set<String> hidden;

    Scope(Catalog catalog, List<String> names) {
        List<Table> resolved = new ArrayList<>();
        int offset = 0;
        for (String name : names) {
            try {
                String canonical = canonicalName(catalog.listTables(), name);
                if (canonical == null)
                    throw new ValidationException("Table does not exist: " + name);
                TableSchema schema = catalog.getSchema(canonical);
                if (resolved.stream().anyMatch(t -> eq(t.name(), schema.table()))) {
                    throw new ValidationException("Table appears more than once in FROM: " + name);
                }
                resolved.add(new Table(schema.table(), schema, offset));
                offset += schema.columns().size();
            } catch (StorageRuntimeException e) {
                throw new ValidationException("Table does not exist: " + name, e);
            }
        }
        tables = List.copyOf(resolved);
        hidden = Set.of();
    }

    private Scope(List<Table> tables, Set<String> hidden) {
        this.tables = List.copyOf(tables);
        this.hidden = Set.copyOf(hidden);
    }

    List<Table> tables() {
        return tables;
    }

    /** Returns the table written as {@code name} (case-insensitive), or {@code null}. */
    Table table(String name) {
        for (Table t : tables) if (eq(t.name(), name)) return t;
        return null;
    }

    /**
     * Returns a scope over only the given tables, in the given order, with offsets recomputed from
     * zero. Used for a join node, whose condition is evaluated on that node's own input row.
     *
     * @param names table names as written; all must be in this scope
     */
    Scope restrictTo(List<String> names) {
        List<Table> subset = new ArrayList<>();
        int offset = 0;
        for (String name : names) {
            Table t = table(name);
            if (t == null)
                throw new ValidationException("Table '" + name + "' is not in FROM clause");
            subset.add(new Table(t.name(), t.schema(), offset));
            offset += t.schema().columns().size();
        }
        return new Scope(subset, hidden);
    }

    /**
     * Returns a scope in which the given {@code table.column} keys (lower case) are merged away by
     * a NATURAL JOIN: they stay in the row and stay reachable as {@code table.column}, but an
     * unqualified name and {@code SELECT *} no longer see them.
     */
    Scope withHidden(Set<String> keys) {
        return new Scope(tables, keys);
    }

    boolean isHidden(Table table, Column column) {
        return hidden.contains(key(table.name(), column.name()));
    }

    static String key(String table, String column) {
        return table.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
    }

    Resolved resolve(String tableName, String columnName) {
        List<Table> candidates =
                tableName == null
                        ? tables
                        : tables.stream().filter(t -> eq(t.name(), tableName)).toList();
        if (tableName != null && candidates.isEmpty())
            throw new ValidationException("Table '" + tableName + "' is not in FROM clause");
        List<Resolved> matches = new ArrayList<>();
        for (Table t : candidates)
            for (int i = 0; i < t.schema().columns().size(); i++) {
                Column c = t.schema().columns().get(i);
                if (!eq(c.name(), columnName)) continue;
                if (tableName == null && isHidden(t, c)) continue;
                matches.add(new Resolved(t, c, t.offset() + i));
            }
        if (matches.size() > 1) {
            List<Resolved> exact =
                    matches.stream().filter(r -> r.column().name().equals(columnName)).toList();
            if (!exact.isEmpty()) matches = exact;
        }
        if (matches.isEmpty())
            throw new ValidationException(
                    "Column '"
                            + (tableName == null ? columnName : tableName + "." + columnName)
                            + "' does not exist");
        if (matches.size() > 1)
            throw new ValidationException("Ambiguous column '" + columnName + "'");
        return matches.get(0);
    }

    static String canonicalName(List<String> names, String requested) {
        for (String name : names) if (name.equals(requested)) return name;
        for (String name : names) if (eq(name, requested)) return name;
        return null;
    }

    static boolean eq(String a, String b) {
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
