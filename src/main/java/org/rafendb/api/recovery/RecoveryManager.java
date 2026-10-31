package org.rafendb.api.recovery;

import org.rafendb.api.storage.WalSync;
import org.rafendb.common.error.RecoveryInternalException;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;

/**
 * Main contract of the Failure Recovery Manager (FRM).
 *
 * <p>Provides transaction logging, rollback, checkpointing and crash recovery to the Concurrency
 * Control Manager (CCM) and the Query Processor (QP). As a {@link WalSync}, it is given to the
 * Storage Manager so the log is forced before a dirty page is written.
 *
 * <p>Implementations are thread-safe: many client sessions call them at the same time. Null
 * arguments throw {@link NullPointerException}.
 */
public interface RecoveryManager extends WalSync, AutoCloseable {

    /**
     * Appends a record to the write-ahead log. The record is buffered; it is durable only after
     * {@link #forceThrough} with its LSN or a later one, or after a commit.
     *
     * @param payload the record content, not null
     * @return the LSN assigned to the record
     * @throws NullPointerException if {@code payload} is null
     * @throws RecoveryInternalException if the record cannot be appended
     */
    long writeLog(LogPayload payload) throws RecoveryInternalException;

    /**
     * Forces the log; on return every record with LSN {@code <= lsn} is on stable storage.
     *
     * @param lsn the highest LSN that must be durable
     * @throws RecoveryInternalException if the log cannot be forced
     */
    @Override
    void forceThrough(long lsn) throws RecoveryInternalException;

    /**
     * Records the start of a transaction (BEGIN).
     *
     * @param tx the transaction, not null
     * @return {@code true} if the record was written, {@code false} otherwise
     * @throws NullPointerException if {@code tx} is null
     */
    boolean beginTransaction(TxId tx);

    /**
     * Records the commit of a transaction (COMMIT) and forces the log to stable storage.
     *
     * @param tx the transaction, not null
     * @return {@code true} if the commit is durable, {@code false} otherwise
     * @throws NullPointerException if {@code tx} is null
     */
    boolean commitTransaction(TxId tx);

    /**
     * Undoes the changes of an active or failed transaction and records its ABORT.
     *
     * @param tx the transaction, not null
     * @return {@code true} if the rollback finished, {@code false} otherwise
     * @throws NullPointerException if {@code tx} is null
     */
    boolean rollbackTransaction(TxId tx);

    /**
     * Creates a quiescent checkpoint.
     *
     * @return {@code true} if the checkpoint was created, {@code false} otherwise
     */
    boolean createCheckpoint();

    /**
     * Restores the database to its state at a point in time.
     *
     * @param targetTimestamp the point in time, in epoch milliseconds
     * @return {@code true} if the recovery succeeded, {@code false} otherwise
     */
    boolean recoverToPointInTime(long targetTimestamp);

    /**
     * Restores the database to its latest state.
     *
     * @return {@code true} if the recovery succeeded, {@code false} otherwise
     */
    boolean recoverToPointInTime();

    /**
     * Runs crash recovery (ARIES analysis, redo and undo) at startup.
     *
     * @return {@code true} if the recovery succeeded, {@code false} otherwise
     */
    boolean recoverFromCrash();

    /**
     * Runs the redo phase, reapplying logged changes from the WAL.
     *
     * @return {@code true} if redo succeeded; {@code false} if it failed or is not supported, which
     *     is the default
     */
    default boolean redo() {
        return false;
    }

    /**
     * Runs the undo phase, reversing the changes of transactions that did not finish.
     *
     * @return {@code true} if undo succeeded; {@code false} if it failed or is not supported, which
     *     is the default
     */
    default boolean undo() {
        return false;
    }

    /**
     * Returns the status of a transaction according to the log.
     *
     * @param tx the transaction, not null
     * @return its status, or {@code null} if the log has no record of it
     * @throws NullPointerException if {@code tx} is null
     */
    TxStatus getTransactionStatus(TxId tx);

    /** Releases resources. Idempotent. Does nothing by default. */
    @Override
    default void close() {}
}
