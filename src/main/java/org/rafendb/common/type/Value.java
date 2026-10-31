package org.rafendb.common.type;

import java.util.Objects;

public final class Value {
    private final DataType type;
    private final Object data;

    private Value(DataType type, Object data) {
        this.type = Objects.requireNonNull(type, "type");
        this.data = Objects.requireNonNull(data, "data");
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
    public boolean equals(Object o) {
        return o instanceof Value other && type == other.type && data.equals(other.data);
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