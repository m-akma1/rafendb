package org.rafendb.common.schema;

import java.util.Map;
import java.util.Objects;

/**
 * Table statistics for the QO cost model. Column names are not normalized.
 *
 * <p>Immutable and thread-safe.
 *
 * @param recordCount n_r, number of records, {@code >= 0}
 * @param blockCount b_r, number of blocks, {@code >= 0}
 * @param averageRecordSize l_r, average record size in bytes, finite and {@code >= 0}
 * @param blockingFactor f_r, records per block, finite and {@code >= 0}
 * @param distinctValues V(A, r), distinct values per column, each {@code >= 0}; copied and
 *     unmodifiable
 */
public record TableStats(
        long recordCount,
        long blockCount,
        double averageRecordSize,
        double blockingFactor,
        Map<String, Long> distinctValues) {
    /**
     * Validates and copies the components.
     *
     * @throws NullPointerException if {@code distinctValues} or any key or value is {@code null}
     * @throws IllegalArgumentException if a number is negative or not finite, or a key is blank
     */
    public TableStats {
        requireNonNegative(recordCount, "recordCount");
        requireNonNegative(blockCount, "blockCount");
        requireFiniteNonNegative(averageRecordSize, "averageRecordSize");
        requireFiniteNonNegative(blockingFactor, "blockingFactor");
        distinctValues = Map.copyOf(distinctValues);
        for (Map.Entry<String, Long> e : distinctValues.entrySet()) {
            if (e.getKey().isBlank()) {
                throw new IllegalArgumentException("distinctValues column name must not be blank");
            }
            requireNonNegative(e.getValue(), "distinctValues[" + e.getKey() + "]");
        }
    }

    /**
     * Returns V(A, r) for a column.
     *
     * @param column the column name
     * @return the distinct value count
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the column is unknown
     */
    public long distinctValues(String column) {
        Objects.requireNonNull(column, "column");
        Long v = distinctValues.get(column);
        if (v == null) {
            throw new IllegalArgumentException("unknown column: " + column);
        }
        return v;
    }

    /**
     * Rejects a negative long.
     *
     * @param v the value
     * @param what the component name
     */
    private static void requireNonNegative(long v, String what) {
        if (v < 0) {
            throw new IllegalArgumentException(what + " must be >= 0, got " + v);
        }
    }

    /**
     * Rejects a negative or non-finite double.
     *
     * @param v the value
     * @param what the component name
     */
    private static void requireFiniteNonNegative(double v, String what) {
        if (!Double.isFinite(v) || v < 0) {
            throw new IllegalArgumentException(what + " must be finite and >= 0, got " + v);
        }
    }
}
