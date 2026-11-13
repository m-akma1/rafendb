package org.rafendb.common.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A foreign key owned by a table, so the owner's name is not stored. Names are passed already
 * normalized by the caller.
 *
 * <p>Composite keys are allowed, though an implementation may support single-column keys only.
 * {@code referencedColumns} is always explicit (the parser resolves {@code REFERENCES t} to the
 * primary key of {@code t}) and must be exactly the referenced table's primary key columns, in
 * order.
 *
 * @param name the constraint name, non-blank
 * @param columns the referencing columns; non-empty, no blank or duplicates
 * @param referencedTable the referenced table, non-blank
 * @param referencedColumns the referenced columns; same size as {@code columns}, none blank
 * @param onDelete the action when a referenced parent row is deleted
 */
public record ForeignKey(
        String name,
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns,
        ReferentialAction onDelete) {
    /**
     * Validates and copies the components.
     *
     * @throws NullPointerException if a component or list element is {@code null}
     * @throws IllegalArgumentException if any rule above is violated
     */
    public ForeignKey {
        requireNonBlank(name, "name");
        requireNonBlank(referencedTable, "referencedTable");
        Objects.requireNonNull(onDelete, "onDelete");
        columns = List.copyOf(columns);
        referencedColumns = List.copyOf(referencedColumns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("foreign key must have at least one column");
        }
        if (referencedColumns.size() != columns.size()) {
            throw new IllegalArgumentException(
                    "referenced columns size "
                            + referencedColumns.size()
                            + " differs from columns size "
                            + columns.size());
        }
        Set<String> seen = new HashSet<>();
        for (String c : columns) {
            requireNonBlank(c, "column");
            if (!seen.add(c)) {
                throw new IllegalArgumentException("duplicate foreign key column: " + c);
            }
        }
        for (String c : referencedColumns) {
            requireNonBlank(c, "referenced column");
        }
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
