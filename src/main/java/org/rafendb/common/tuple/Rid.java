package org.rafendb.common.tuple;

import java.util.Objects;

/**
 * Address of a record slot. It does not change when the record's byte offset in the page changes;
 * a move that would change it must be a logged DELETE plus INSERT. With the table name it
 * identifies a record for CCM locking.
 *
 * <p>Immutable and thread-safe.
 *
 * @param block the block holding the slot
 * @param slot the slot number, starting at zero
 */
public record Rid(BlockId block, int slot) implements Comparable<Rid> {
    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code block} is {@code null}
     * @throws IllegalArgumentException if {@code slot} is negative
     */
    public Rid {
        Objects.requireNonNull(block, "block");
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be >= 0, got " + slot);
        }
    }

    /**
     * Orders by block, then slot.
     *
     * @param other the Rid to compare with
     * @return negative, zero or positive as this is before, equal to or after {@code other}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    @Override
    public int compareTo(Rid other) {
        Objects.requireNonNull(other, "other");
        int c = block.compareTo(other.block);
        return c != 0 ? c : Integer.compare(slot, other.slot);
    }
}
