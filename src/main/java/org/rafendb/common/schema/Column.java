package org.rafendb.common.schema;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/**
 * A table column. The name is not normalized.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * @param name       the column name, non-blank
 * @param type       the column type
 * @param length     {@code 0} for INTEGER and FLOAT; for CHAR and VARCHAR the
 *                   maximum UTF-8 bytes, in
 *                   {@code 1..}{@link #MAX_TEXT_LENGTH}
 * @param nullable   whether SQL NULL is allowed
 * @param primaryKey whether the column is in the primary key; must not be
 *                   nullable
 */
public record Column(String name, DataType type, int length, boolean nullable, boolean primaryKey) {
    /** Largest text length in bytes, so a record fits in a 4096-byte page. */
    public static final int MAX_TEXT_LENGTH = 4000;

    /**
     * Validates the components.
     *
     * @throws NullPointerException     if {@code name} or {@code type} is
     *                                  {@code null}
     * @throws IllegalArgumentException if the name is blank, the length is invalid
     *                                  for the type, or
     *                                  a primary key is nullable
     */
    public Column {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("column name must not be blank");
        }
        if (type.isNumeric()) {
            if (length != 0) {
                throw new IllegalArgumentException(type + " must have length 0, got " + length);
            }
        } else if (length < 1 || length > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    type + " length must be in 1.." + MAX_TEXT_LENGTH + ", got " + length);
        }
        if (primaryKey && nullable) {
            throw new IllegalArgumentException("primary key column must not be nullable: " + name);
        }
    }

    /**
     * Returns whether a value fits this column: NULL only if nullable, same type
     * family, and text
     * within {@link #length()} UTF-8 bytes.
     *
     * @param v the value to test
     * @return {@code true} if acceptable
     * @throws NullPointerException if {@code v} is {@code null}
     */
    public boolean accepts(Value v) {
        Objects.requireNonNull(v, "v");
        if (v.isNull()) {
            return nullable;
        }
        if (type.isText()) {
            return v.type().isText()
                    && v.asText().getBytes(StandardCharsets.UTF_8).length <= length;
        }
        return v.type() == type;
    }
}
