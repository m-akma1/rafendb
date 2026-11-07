package org.rafendb.processor.operator.sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.rafendb.api.processor.Operator;
import org.rafendb.common.plan.SortPlanNode.Direction;
import org.rafendb.common.schema.ColumnSchema;
import org.rafendb.common.tuple.ResultRow;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/**
 * An operator that sorts the rows of its child by a single key column.
 *
 * <p>This operator is blocking: it reads the whole child output when opened and then returns the
 * rows in sorted order. Sorting is stable, so rows with equal keys keep the order in which the
 * child produced them. {@code NULL} is smaller than every non-NULL value, so it comes first in
 * ascending order and last in descending order.
 *
 * <p><b>Thread-safety:</b> instances are not thread-safe. An instance belongs to a single query
 * execution of a single session and must not be shared between threads. The class keeps no static
 * mutable state, so separate instances may run concurrently.
 */
public final class SortOp implements Operator {
    private final Operator child;

    private final int keyOffset;

    private final Direction direction;

    private final List<ResultRow> sorted = new ArrayList<>();

    /** Index of the next sorted row to return. */
    private int pos = 0;

    private boolean isOpen = false;

    /**
     * Constructs a sort operator.
     *
     * @param child the operator whose output is sorted; must not be {@code null}
     * @param keyOffset the zero-based position of the sort key column in the rows produced by
     *     {@code child}; must be within the child's row width
     * @param direction the sort direction, either {@code ASC} or {@code DESC}; must not be
     *     {@code null}
     * @throws NullPointerException if {@code child} or {@code direction} is {@code null}
     * @throws IllegalArgumentException if {@code keyOffset} is outside the child's row width
     */
    public SortOp(Operator child, int keyOffset, Direction direction) {
        this.child = Objects.requireNonNull(child, "child");
        this.direction = Objects.requireNonNull(direction, "direction");
        if (keyOffset < 0 || keyOffset >= child.schema().size()) {
            throw new IllegalArgumentException("Invalid sort key offset: " + keyOffset);
        }
        this.keyOffset = keyOffset;
    }

    private class CompareRows implements Comparator<ResultRow> {

        /**
         * Compares two rows by the key column, honouring the sort direction.
         *
         * <p>NULL is smaller than every non-NULL value. Numeric values compare across INTEGER and
         * FLOAT types, while text values compare by Unicode code point.
         *
         * @param a the first row; must not be {@code null}
         * @param b the second row; must not be {@code null}
         * @return a negative integer, zero, or a positive integer if {@code a} sorts before, equal to,
         *     or after {@code b} respectively
         */
        @Override
        public int compare(ResultRow a, ResultRow b) {
            Value aVal = a.values().get(keyOffset);
            Value bVal = b.values().get(keyOffset);

            if (aVal.isNull() && bVal.isNull()) {
                return 0;
            }

            if (aVal.isNull()) {
                return direction == Direction.ASC ? -1 : 1;
            }

            if (bVal.isNull()) {
                return direction == Direction.ASC ? 1 : -1;
            }

            int result = compareNonNull(aVal, bVal);

            return direction == Direction.ASC ? result : -Integer.signum(result);
        }

        private int compareNonNull(Value a, Value b) {
            if (a.type().isNumeric() && b.type().isNumeric()) {
                return Double.compare(asDouble(a), asDouble(b));
            }
            if (a.type().isText() && b.type().isText()) {
                return compareCodePoints(a.asText(), b.asText());
            }
            return a.compareTo(b);
        }

        private double asDouble(Value value) {
            if (value.type() == DataType.INTEGER) {
                return value.asInt();
            }
            return value.asFloat();
        }

        private int compareCodePoints(String a, String b) {
            int aOffset = 0;
            int bOffset = 0;
            while (aOffset < a.length() && bOffset < b.length()) {
                int aCodePoint = a.codePointAt(aOffset);
                int bCodePoint = b.codePointAt(bOffset);
                int comparison = Integer.compare(aCodePoint, bCodePoint);
                if (comparison != 0) {
                    return comparison;
                }
                aOffset += Character.charCount(aCodePoint);
                bOffset += Character.charCount(bCodePoint);
            }
            return Boolean.compare(aOffset < a.length(), bOffset < b.length());
        }
    }

    /**
     * Opens the child, reads all its rows into an internal buffer, and sorts them by the specified
     * key column.
     *
     * <p>This is a blocking operation: all child rows must be read before sorted output becomes
     * available. Sorting is stable, preserving the original relative order of rows with equal keys.
     *
     * <p>May be called repeatedly, including without a preceding {@link #close()}. Each call
     * resets the operator state and starts reading from the beginning.
     *
     * @throws RuntimeException if an error occurs while opening, reading, or sorting the child
     */
    @Override
    public void open() {
        if (isOpen) {
            close();
        }

        sorted.clear();
        pos = 0;

        try {
            child.open();

            // Read all rows from child
            List<ResultRow> rows = new ArrayList<>(); // temp sorted buffer
            ResultRow row;
            while ((row = child.next()) != null) {
                rows.add(row);
            }

            rows.sort(new CompareRows());

            // Move result from temp buffer -> sorted
            sorted.addAll(rows);

            pos = 0;
            isOpen = true;
        } catch (RuntimeException | Error e) {
            sorted.clear();
            pos = 0;
            isOpen = false;

            try {
                child.close();
            } catch (RuntimeException | Error closeError) {
                e.addSuppressed(closeError);
            }

            throw e;
        }
    }

    /**
     * Returns the schema of the rows produced by this operator.
     *
     * <p>Sorting changes only row order, so the output schema is identical to the child schema.
     *
     * @return the child schema; never {@code null}, unmodifiable
     */
    @Override
    public List<ColumnSchema> schema() {
        return child.schema();
    }

    /**
     * Advances to and returns the next row in sorted order.
     *
     * <p>Returns {@code null} when all sorted rows have been consumed. Subsequent calls continue
     * returning {@code null} until the operator is reopened.
     *
     * @return the next sorted {@link ResultRow}, or {@code null} if no more rows are available
     * @throws IllegalStateException if the operator has not been opened or has been closed
     */
    @Override
    public ResultRow next() {
        if (!isOpen) {
            throw new IllegalStateException("Sort operator is not open");
        }
        if (pos >= sorted.size()) {
            return null;
        }

        return sorted.get(pos++);
    }

    /**
     * Releases the buffered rows and closes the child operator.
     *
     * <p>Safe to call multiple times, including after opening, consuming some or all rows, or
     * encountering an exception.
     */
    @Override
    public void close() {
        sorted.clear();
        pos = 0;
        isOpen = false;

        child.close();
    }
}
