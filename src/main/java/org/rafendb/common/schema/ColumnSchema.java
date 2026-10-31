package org.rafendb.common.schema;

import java.util.Objects;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.type.DataType;

/** Identitas dan tipe kolom output operator sesuai kontrak evaluator QP. */
public record ColumnSchema(Expr.ColumnRef reference, DataType type) {
    public ColumnSchema {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(reference.column(), "column");
        Objects.requireNonNull(type, "type");
        if (reference.column().isBlank()) {
            throw new IllegalArgumentException("Column name is blank");
        }
    }
}
