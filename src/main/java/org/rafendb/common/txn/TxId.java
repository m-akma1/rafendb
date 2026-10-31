package org.rafendb.common.txn;

/**
 * Unique transaction id. Ids grow in begin order, so a bigger id is a later transaction.
 *
 * @param value the id, must be positive
 */
public record TxId(long value) implements Comparable<TxId> {
    /** @throws IllegalArgumentException if {@code value} is not positive */
    public TxId {
        if (value <= 0) {
            throw new IllegalArgumentException("TxId must be positive: " + value);
        }
    }

    /** {@inheritDoc} */
    @Override
    public int compareTo(TxId other) {
        return Long.compare(value, other.value);
    }

    /** {@inheritDoc} Formats as {@code T7}. */
    @Override
    public String toString() {
        return "T" + value;
    }
}
