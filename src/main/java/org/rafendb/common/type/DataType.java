package org.rafendb.common.type;

/**
 * SQL column types. Length limits live in
 * {@link org.rafendb.common.schema.Column}.
 *
 * <p>
 * Immutable and thread-safe.
 */
public enum DataType {
    /** 32-bit signed integer. */
    INTEGER,
    /** single-precision number. */
    FLOAT,
    /** Character string. */
    CHAR,
    /** Variable-length character string. */
    VARCHAR;

    /**
     * Returns whether this is INTEGER or FLOAT.
     *
     * @return {@code true} for a numeric type
     */
    public boolean isNumeric() {
        return this == INTEGER || this == FLOAT;
    }

    /**
     * Returns whether this is CHAR or VARCHAR.
     *
     * @return {@code true} for a text type
     */
    public boolean isText() {
        return this == CHAR || this == VARCHAR;
    }
}
