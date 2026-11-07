package org.rafendb.optimizer.binder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.rafendb.common.error.OptimizerRuntimeException.ValidationException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.optimizer.catalog.Catalog;

/** Resolved table scope for one query. */
final class Scope {
    record Table(String name, TableSchema schema, int offset) {}

    record Resolved(Table table, Column column, int offset) {}

    private final List<Table> tables;

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
    }

    List<Table> tables() {
        return tables;
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
                if (eq(c.name(), columnName)) matches.add(new Resolved(t, c, t.offset() + i));
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
