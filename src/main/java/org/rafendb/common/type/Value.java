package org.rafendb.common.type;

import java.util.Objects;

public final class Value implements Comparable<Value> {
    private final DataType type;
    private final Object data;

    private Value(DataType type, Object data) {
        this.type = type;
        this.data = data;
    }

    public static Value ofInt(int v) {
        return new Value(DataType.INT, v);
    }

    public static Value ofFloat(float v) {
        return new Value(DataType.FLOAT, v);
    }

    public static Value ofText(DataType type, String text) {
        if (type != DataType.CHAR && type != DataType.VARCHAR) {
            throw new IllegalArgumentException("not a text type: " + type);
        }
        return new Value(type, Objects.requireNonNull(text, "text"));
    }

    public static Value ofNull() {
        return new Value(null, null);
    }

    public DataType getType() {
        return type;
    }

    public int asInt() {
        return (Integer) data;
    }

    public float asFloat() {
        return (Float) data;
    }

    public String asText() {
        return (String) data;
    }

    @Override
    public int compareTo(Value other) {
        Objects.requireNonNull(other, "other");

        if (type == DataType.NULL && other.type == DataType.NULL) {
            return 0;
        }
        if (type == DataType.NULL) {
            return -1;
        }
        if (other.type == DataType.NULL) {
            return 1;
        }

        boolean thisNumeric = type == DataType.INT || type == DataType.FLOAT;
        boolean otherNumeric = other.type == DataType.INT || other.type == DataType.FLOAT;

        if (thisNumeric && otherNumeric) {
            double a = type == DataType.INT ? asInt() : asFloat();

            double b = other.type == DataType.INT ? other.asInt() : other.asFloat();

            return Double.compare(a, b);
        }

        boolean thisText = type == DataType.CHAR || type == DataType.VARCHAR;
        boolean otherText = other.type == DataType.CHAR || other.type == DataType.VARCHAR;

        if (thisText && otherText) {
            return asText().compareTo(other.asText());
        }

        throw new IllegalArgumentException(
                "Cannot compare different type families: " + type + " and " + other.type);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Value other && type == other.type && Objects.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, data);
    }

    @Override
    public String toString() {
        return type + ":" + data;
    }
}
