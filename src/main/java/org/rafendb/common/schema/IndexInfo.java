package org.rafendb.common.schema;

import java.util.Objects;

/**
 * A single-column index. Names are not normalized.
 *
 * <p>Immutable and thread-safe.
 *
 * @param name the index name, non-blank, unique across the database
 * @param table the indexed table, non-blank
 * @param column the indexed column, non-blank
 * @param type the index structure
 */
public record IndexInfo(String name, String table, String column, IndexType type) {
    /**
     * Validates the components.
     *
     * @throws NullPointerException if any component is {@code null}
     * @throws IllegalArgumentException if {@code name}, {@code table} or {@code column} is blank
     */
    public IndexInfo {
        requireNonBlank(name, "name");
        requireNonBlank(table, "table");
        requireNonBlank(column, "column");
        Objects.requireNonNull(type, "type");
    }

    /**
     * Rejects a null or blank name.
     *
     * @param s the name
     * @param what the component name
     */
    private static void requireNonBlank(String s, String what) {
        Objects.requireNonNull(s, what);
        if (s.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
    }
}
