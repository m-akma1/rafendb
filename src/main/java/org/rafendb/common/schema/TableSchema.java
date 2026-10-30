package org.rafendb.common.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A table definition. Names are not normalized; column lookup is an exact match.
 *
 * <p>Immutable and thread-safe.
 *
 * @param table the table name, non-blank
 * @param columns the columns in declaration order; non-empty, unique names, copied and
 *     unmodifiable
 * @param foreignKeys the outgoing foreign keys; unique names, columns must exist in this table,
 *     copied and unmodifiable
 */
public record TableSchema(String table, List<Column> columns, List<ForeignKey> foreignKeys) {
    /**
     * Validates and copies the components. Referenced tables are not checked here.
     *
     * @throws NullPointerException if a component or list element is {@code null}
     * @throws IllegalArgumentException if a rule above is violated
     */
    public TableSchema {
        Objects.requireNonNull(table, "table");
        if (table.isBlank()) {
            throw new IllegalArgumentException("table name must not be blank");
        }
        columns = List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("table must have at least one column");
        }
        Set<String> seen = new HashSet<>();
        for (Column c : columns) {
            if (!seen.add(c.name())) {
                throw new IllegalArgumentException("duplicate column name: " + c.name());
            }
        }
        foreignKeys = List.copyOf(foreignKeys);
        Set<String> fkNames = new HashSet<>();
        for (ForeignKey fk : foreignKeys) {
            if (!fkNames.add(fk.name())) {
                throw new IllegalArgumentException("duplicate foreign key name: " + fk.name());
            }
            for (String col : fk.columns()) {
                if (!seen.contains(col)) {
                    throw new IllegalArgumentException(
                            "foreign key " + fk.name() + " names unknown column: " + col);
                }
            }
        }
    }

    /**
     * Creates a table definition without foreign keys.
     *
     * @param table the table name
     * @param columns the columns in declaration order
     */
    public TableSchema(String table, List<Column> columns) {
        this(table, columns, List.of());
    }

    /**
     * Finds a column's position.
     *
     * @param name the column name
     * @return the zero-based index, or {@code -1} if absent
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public int columnIndex(String name) {
        Objects.requireNonNull(name, "name");
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds a column by name.
     *
     * @param name the column name
     * @return the column, or empty if absent
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Optional<Column> column(String name) {
        int i = columnIndex(name);
        return i < 0 ? Optional.empty() : Optional.of(columns.get(i));
    }

    /**
     * Returns the primary key columns in declaration order.
     *
     * @return an unmodifiable list, empty if there is no primary key
     */
    public List<Column> primaryKeyColumns() {
        return columns.stream().filter(Column::primaryKey).toList();
    }
}
