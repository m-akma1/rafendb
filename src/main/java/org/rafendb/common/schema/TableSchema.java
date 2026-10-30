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
 */
public record TableSchema(String table, List<Column> columns) {
    /**
     * Validates and copies the components.
     *
     * @throws NullPointerException if {@code table}, {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the table name is blank, there are no columns, or column
     *     names repeat
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
