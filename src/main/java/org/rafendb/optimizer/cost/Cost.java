package org.rafendb.optimizer.cost;

import java.util.Objects;

/** Immutable I/O cost estimate, measured in seeks and transfers. */
public final class Cost {
    /** Provisional weights; not a final contract and subject to QO5/API owner approval. */
    public static final double SEEK_MILLIS = 4.0;

    public static final double TRANSFER_MILLIS = 0.1;

    private final long seeks;
    private final long transfers;

    public Cost(long seeks, long transfers) {
        if (seeks < 0 || transfers < 0) {
            throw new IllegalArgumentException("Cost values must be nonnegative");
        }
        this.seeks = seeks;
        this.transfers = transfers;
    }

    public long getSeeks() {
        return seeks;
    }

    public long getTransfers() {
        return transfers;
    }

    public double timeMs() {
        return seeks * SEEK_MILLIS + transfers * TRANSFER_MILLIS;
    }

    public Cost plus(Cost other) {
        Objects.requireNonNull(other, "other cost");
        return new Cost(
                Math.addExact(seeks, other.seeks), Math.addExact(transfers, other.transfers));
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Cost that && seeks == that.seeks && transfers == that.transfers;
    }

    @Override
    public int hashCode() {
        return Objects.hash(seeks, transfers);
    }

    @Override
    public String toString() {
        return "Cost[seeks=" + seeks + ", transfers=" + transfers + "]";
    }
}
