package org.rafendb.common.tuple;

/**
 * Address of a record slot in a table's heap file. A Rid only has meaning together with its table
 * name, which every Storage Manager call passes alongside it. It does not change when the record's
 * byte offset inside the page changes; a move that would change it must be done as a logged DELETE
 * plus INSERT. Together with the table name it identifies a row in CCM change records; CCM locks
 * whole tables.
 *
 * <p>Immutable and thread-safe.
 *
 * @param block the block number inside the table's heap file, starting at zero
 * @param slot the slot number inside that block, starting at zero
 */
public record Rid(long block, int slot) implements Comparable<Rid> {
    /**
     * Validates the components.
     *
     * @throws IllegalArgumentException if {@code block} or {@code slot} is negative
     */
    public Rid {
        if (block < 0) {
            throw new IllegalArgumentException("block must be >= 0, got " + block);
        }
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
        int c = Long.compare(block, other.block);
        return c != 0 ? c : Integer.compare(slot, other.slot);
    }
}
