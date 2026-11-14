package org.rafendb.concurrency.txn;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;

/**
 * Transaction table of the CCM. Issues ids, tracks each transaction's status, and holds new transactions for checkpoints.
 */
public final class TransactionManager {
    private final Map<TxId, TxStatus> table = new HashMap<>();
    private long lastId;
    private int unfinished;
    private boolean open;
    private boolean paused;

    /**
     * Opens admission after recovery. Later ids and timestamps are greater than {@code highWater}.
     *
     * @param highWater the larger recovered transaction id and timestamp, not negative
     * @throws IllegalArgumentException if highWater is negative
     * @throws IllegalStateException if already open
     */
    public synchronized void open(long highWater) {
        if (highWater < 0) {
            throw new IllegalArgumentException("highWater must not be negative: " + highWater);
        }
        if (open) {
            throw new IllegalStateException("already open");
        }
        table.clear();
        unfinished = 0;
        lastId = highWater;
        open = true;
    }

    /**
     * Starts a transaction. Waits while new transactions are held.
     *
     * @return a new ACTIVE transaction with an id greater than every id issued before
     * @throws IllegalStateException if not open, ids are exhausted, or interrupted while waiting
     */
    public synchronized TxId begin() {
        if (!open) {
            throw new IllegalStateException("not accepting transactions");
        }
        try {
            while (paused) {
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to begin", e);
        }
        if (lastId == Long.MAX_VALUE) {
            throw new IllegalStateException("transaction ids exhausted");
        }
        TxId tx = new TxId(++lastId);
        table.put(tx, TxStatus.ACTIVE);
        unfinished++;
        return tx;
    }

    /**
     * Returns a transaction's begin timestamp, for timestamp-based protocols.
     *
     * @throws NullPointerException if tx is null
     * @throws IllegalArgumentException if tx is unknown
     */
    public synchronized long timestamp(TxId tx) {
        status(tx);
        return tx.value();
    }

    /**
     * Returns a transaction's current status.
     *
     * @throws NullPointerException if tx is null
     * @throws IllegalArgumentException if tx is unknown
     */
    public synchronized TxStatus status(TxId tx) {
        TxStatus status = table.get(Objects.requireNonNull(tx, "tx"));
        if (status == null) {
            throw new IllegalArgumentException("unknown transaction: " + tx);
        }
        return status;
    }

    /**
     * Moves a transaction to its next status. Allowed: ACTIVE to PARTIALLY_COMMITTED or FAILED,
     * PARTIALLY_COMMITTED to COMMITTED or FAILED, FAILED to ABORTED.
     *
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if tx is unknown
     * @throws IllegalStateException if the move is not allowed
     */
    public synchronized void transition(TxId tx, TxStatus next) {
        Objects.requireNonNull(next, "next");
        TxStatus current = status(tx);
        if (!allowed(current, next)) {
            throw new IllegalStateException(tx + ": cannot go from " + current + " to " + next);
        }
        table.put(tx, next);
        if (next.isFinal()) {
            unfinished--;
            notifyAll();
        }
    }

    private static boolean allowed(TxStatus from, TxStatus to) {
        return switch (from) {
            case ACTIVE -> to == TxStatus.PARTIALLY_COMMITTED || to == TxStatus.FAILED;
            case PARTIALLY_COMMITTED -> to == TxStatus.COMMITTED || to == TxStatus.FAILED;
            case FAILED -> to == TxStatus.ABORTED;
            case ABORTED, COMMITTED -> false;
        };
    }

    /** Holds new transactions in {@link #begin()} until {@link #resume()}. */
    public synchronized void pause() {
        paused = true;
    }

    /** Lets held transactions begin. Does nothing if not paused. */
    public synchronized void resume() {
        paused = false;
        notifyAll();
    }

    /**
     * Waits until every transaction that began is COMMITTED or ABORTED.
     *
     * @param timeout the longest time to wait, not null and not negative
     * @return {@code true} if none is left, {@code false} if the time ran out
     * @throws NullPointerException if timeout is null
     * @throws IllegalArgumentException if timeout is negative
     * @throws InterruptedException if interrupted while waiting
     */
    public synchronized boolean awaitNoActive(Duration timeout) throws InterruptedException {
        if (Objects.requireNonNull(timeout, "timeout").isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        long remaining;
        try {
            remaining = timeout.toNanos();
        } catch (ArithmeticException e) {
            remaining = Long.MAX_VALUE;
        }
        while (unfinished > 0) {
            if (remaining <= 0) {
                return false;
            }
            long start = System.nanoTime();
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining -= System.nanoTime() - start;
        }
        return true;
    }
}
