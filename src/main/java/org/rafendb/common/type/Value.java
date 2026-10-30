package org.rafendb.common.type;

import java.util.Objects;

/**
 * A typed value, or SQL NULL of a given type.
 *
 * <p>CHAR and VARCHAR form one text family, so equal strings are equal regardless of which. INTEGER
 * and FLOAT never equal each other. Two NULLs of one family are equal here, which is not SQL NULL
 * semantics.
 *
 * <p>A FLOAT of {@code -0.0f} is normalized to {@code 0.0f} on construction, so {@link #equals},
 * {@link #hashCode} and {@link #compareTo} treat the two zeros as the same value, as in SQL.
 *
 * <p>Immutable and thread-safe.
 *
 * @param type the value type, also set for NULL
 * @param value an {@link Integer}, {@link Float} or {@link String} matching the type, or {@code
 *     null} for SQL NULL
 */
public record Value(DataType type, Object value) implements Comparable<Value> {
    /**
     * Validates the components and normalizes a FLOAT {@code -0.0f} to {@code 0.0f}.
     *
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws IllegalArgumentException if a non-null {@code value} has the wrong Java class
     */
    public Value {
        Objects.requireNonNull(type, "type");
        if (value != null) {
            boolean matches =
                    switch (type) {
                        case INTEGER -> value instanceof Integer;
                        case FLOAT -> value instanceof Float;
                        case CHAR, VARCHAR -> value instanceof String;
                    };
            if (!matches) {
                throw new IllegalArgumentException(
                        "value of class "
                                + value.getClass().getName()
                                + " is not valid for type "
                                + type);
            }
            if (value instanceof Float f && f == 0.0f) {
                value = 0.0f;
            }
        }
    }

    /**
     * Creates an INTEGER value.
     *
     * @param v the integer
     * @return the value
     */
    public static Value ofInt(int v) {
        return new Value(DataType.INTEGER, v);
    }

    /**
     * Creates a FLOAT value; {@code -0.0f} is stored as {@code 0.0f}.
     *
     * @param v the float
     * @return the value
     */
    public static Value ofFloat(float v) {
        return new Value(DataType.FLOAT, v);
    }

    /**
     * Creates a CHAR or VARCHAR value.
     *
     * @param type CHAR or VARCHAR
     * @param s the string
     * @return the value
     * @throws NullPointerException if {@code type} or {@code s} is {@code null}
     * @throws IllegalArgumentException if {@code type} is not a text type
     */
    public static Value ofText(DataType type, String s) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(s, "s");
        if (!type.isText()) {
            throw new IllegalArgumentException("ofText requires CHAR or VARCHAR, got " + type);
        }
        return new Value(type, s);
    }

    /**
     * Creates SQL NULL of the given type.
     *
     * @param type the type
     * @return the NULL value
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static Value nullOf(DataType type) {
        return new Value(type, null);
    }

    /**
     * Returns whether this is SQL NULL.
     *
     * @return {@code true} if NULL
     */
    public boolean isNull() {
        return value == null;
    }

    /**
     * Returns the INTEGER payload.
     *
     * @return the integer
     * @throws IllegalStateException if NULL or not INTEGER
     */
    public int asInt() {
        return (Integer) payload(DataType.INTEGER);
    }

    /**
     * Returns the FLOAT payload.
     *
     * @return the float
     * @throws IllegalStateException if NULL or not FLOAT
     */
    public float asFloat() {
        return (Float) payload(DataType.FLOAT);
    }

    /**
     * Returns the CHAR or VARCHAR payload.
     *
     * @return the string
     * @throws IllegalStateException if NULL or not a text type
     */
    public String asText() {
        return (String) payload(DataType.CHAR);
    }

    /**
     * Compares within one family only; INTEGER/FLOAT promotion is the query processor's job.
     * Consistent with {@link #equals} for non-NULL values.
     *
     * @param other the value to compare with
     * @return negative, zero or positive as this is less than, equal to or greater than {@code
     *     other}
     * @throws NullPointerException if {@code other} is {@code null}
     * @throws IllegalArgumentException if either is NULL or the families differ
     */
    @Override
    public int compareTo(Value other) {
        Objects.requireNonNull(other, "other");
        if (isNull() || other.isNull()) {
            throw new IllegalArgumentException("cannot compare NULL values");
        }
        DataType a = family();
        DataType b = other.family();
        if (a != b) {
            throw new IllegalArgumentException("cannot compare " + type + " with " + other.type);
        }
        return switch (a) {
            case INTEGER -> Integer.compare((Integer) value, (Integer) other.value);
            case FLOAT -> Float.compare((Float) value, (Float) other.value);
            case CHAR, VARCHAR -> ((String) value).compareTo((String) other.value);
        };
    }

    /**
     * Compares by family and payload.
     *
     * @param o the object to compare with
     * @return {@code true} if same family and equal payload
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof Value v && family() == v.family() && Objects.equals(value, v.value);
    }

    /**
     * Returns a hash code consistent with {@link #equals}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(family(), value);
    }

    /**
     * Returns {@code NULL}, a quoted string, or the number.
     *
     * @return the text form
     */
    @Override
    public String toString() {
        if (value == null) {
            return "NULL";
        }
        return type.isText() ? "'" + value + "'" : value.toString();
    }

    /**
     * Maps CHAR and VARCHAR to CHAR.
     *
     * @return the family representative
     */
    private DataType family() {
        return type.isText() ? DataType.CHAR : type;
    }

    /**
     * Returns the payload after checking it is non-NULL and of the expected family.
     *
     * @param expected the family, CHAR for text
     * @return the payload
     * @throws IllegalStateException if NULL or of another family
     */
    private Object payload(DataType expected) {
        if (value == null) {
            throw new IllegalStateException("value is NULL");
        }
        if (family() != expected) {
            throw new IllegalStateException("value of type " + type + " is not " + expected);
        }
        return value;
    }
}
