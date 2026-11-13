package org.rafendb.common.tuple;

import java.util.List;
import org.rafendb.common.type.Value;

/**
 * Values in column order, without an address. Also used as before/after image for logging.
 *
 * <p>Immutable and thread-safe.
 *
 * @param values the values, copied and unmodifiable; SQL NULL is a {@link Value} with a null
 *     payload, never a null element
 */
public record Row(List<Value> values) {
    /**
     * Copies the values.
     *
     * @throws NullPointerException if {@code values} or any element is {@code null}
     */
    public Row {
        values = List.copyOf(values);
    }

    /**
     * Returns the value at a column position.
     *
     * @param index the zero-based position
     * @return the value
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public Value get(int index) {
        return values.get(index);
    }

    /**
     * Returns the number of values.
     *
     * @return the count
     */
    public int size() {
        return values.size();
    }
}
