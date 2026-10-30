package org.rafendb.common.expr;

import java.util.Objects;

/** Column reference; an empty qualifier means unqualified, never null. */
public record ColumnRef(String qualifier, String name) {
    public ColumnRef {
        Objects.requireNonNull(qualifier, "qualifier");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("Column name is blank");
    }
}
