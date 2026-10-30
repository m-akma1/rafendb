package org.rafendb.common.tuple;

import java.util.Objects;

/**
 * Identifies one block of a logical file. The file name is not normalized.
 *
 * <p>Immutable and thread-safe.
 *
 * @param fileName the logical file name, non-blank
 * @param number the block number, starting at zero
 */
public record BlockId(String fileName, long number) implements Comparable<BlockId> {
    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code fileName} is {@code null}
     * @throws IllegalArgumentException if {@code fileName} is blank or {@code number} is negative
     */
    public BlockId {
        Objects.requireNonNull(fileName, "fileName");
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (number < 0) {
            throw new IllegalArgumentException("number must be >= 0, got " + number);
        }
    }

    /**
     * Orders by file name, then block number.
     *
     * @param other the id to compare with
     * @return negative, zero or positive as this is before, equal to or after {@code other}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    @Override
    public int compareTo(BlockId other) {
        Objects.requireNonNull(other, "other");
        int c = fileName.compareTo(other.fileName);
        return c != 0 ? c : Long.compare(number, other.number);
    }
}
