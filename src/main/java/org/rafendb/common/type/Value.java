package org.rafendb.common.type;

import java.util.Objects;

/** Immutable scalars with QP4's proposed sorting order, not SQL predicate comparison. */
public sealed interface Value extends Comparable<Value> {
    /** SQL NULL sentinel, distinct from Java null and cursor exhaustion. */
    final class NullValue implements Value {
        public static final NullValue INSTANCE = new NullValue();

        private NullValue() {}

        @Override
        public String toString() {
            return "NULL";
        }
    }

    /**
     * Orders SQL NULL first, numbers numerically, and text by Unicode code point, case-sensitive.
     * Descending order reverses this ordering, so SQL NULL is last. Signed zero compares equal.
     * Numeric comparison can return zero across distinct record types; it is not consistent
     * with record equals. Do not use this ordering to implement SQL equality predicates.
     * @throws NullPointerException if other is Java null
     * @throws IllegalArgumentException for text versus numeric comparisons
     */
    @Override
    default int compareTo(Value other) {
        Objects.requireNonNull(other, "other");
        if (this == NullValue.INSTANCE) return other == NullValue.INSTANCE ? 0 : -1;
        if (other == NullValue.INSTANCE) return 1;
        if (this instanceof TextValue left && other instanceof TextValue right) {
            String a = left.value();
            String b = right.value();
            int i = 0, j = 0;
            while (i < a.length() && j < b.length()) {
                int ac = a.codePointAt(i), bc = b.codePointAt(j);
                int result = Integer.compare(ac, bc);
                if (result != 0) return result;
                i += Character.charCount(ac);
                j += Character.charCount(bc);
            }
            return Integer.compare(a.length() - i, b.length() - j);
        }
        if (this instanceof TextValue || other instanceof TextValue) {
            throw new IllegalArgumentException("Cannot compare text and numeric values");
        }
        double a =
                this instanceof IntegerValue integer
                        ? integer.value()
                        : ((FloatValue) this).value();
        double b =
                other instanceof IntegerValue integer
                        ? integer.value()
                        : ((FloatValue) other).value();
        return a == b ? 0 : Double.compare(a, b);
    }

    /** INTEGER payload; range must be reconciled with storage serialization. */
    record IntegerValue(int value) implements Value {}

    /** FLOAT payload; precision must be reconciled with storage serialization. */
    record FloatValue(double value) implements Value {
        public FloatValue {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("FLOAT must be finite");
        }
    }

    /** Text payload. CHAR/VARCHAR length and padding belong to schema validation. */
    record TextValue(String value) implements Value {
        public TextValue {
            Objects.requireNonNull(value, "value");
        }
    }
}
