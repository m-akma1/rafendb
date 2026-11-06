package org.rafendb.common.schema;

import java.util.Objects;

/**
 * A foreign key with the name of the table that owns it, used to find which tables reference a
 * table.
 *
 * @param referencingTable the owning table, non-blank
 * @param foreignKey the foreign key
 */
public record ReferencingForeignKey(String referencingTable, ForeignKey foreignKey) {
    /**
     * Validates the components.
     *
     * @throws NullPointerException if any component is {@code null}
     * @throws IllegalArgumentException if {@code referencingTable} is blank
     */
    public ReferencingForeignKey {
        Objects.requireNonNull(referencingTable, "referencingTable");
        Objects.requireNonNull(foreignKey, "foreignKey");
        if (referencingTable.isBlank()) {
            throw new IllegalArgumentException("referencingTable must not be blank");
        }
    }
}
