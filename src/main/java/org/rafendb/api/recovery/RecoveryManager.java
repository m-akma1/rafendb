package org.rafendb.api.recovery;

import org.rafendb.api.storage.WalSync;
import org.rafendb.common.txn.TxStatus;

/**
 * Primary public contract of the Failure Recovery Manager (FRM).
 *
 * <p>Provides transaction logging, rollback, checkpointing, and crash recovery services to
 * Concurrency Control Manager (CCM) and Query Processor (QP), and implements {@link WalSync}
 * to guarantee Write-Ahead Logging for StorageManager before dirty page flush/eviction.
 */
public interface RecoveryManager extends WalSync, AutoCloseable {

    /**
     * Appends a log entry to the write-ahead log under WAL rules.
     *
     * @param payload the record payload to log
     * @return the allocated Log Sequence Number (LSN) for this record
     * @throws LoggerErrorException if logging fails
     */
    long writeLog(LogPayload payload) throws LoggerErrorException;

    /**
     * Records the start of a transaction (BEGIN).
     *
     * @param idTransaction transaction identifier
     * @return true if successfully recorded
     */
    boolean beginTransaction(int idTransaction);

    /**
     * Records the commit of a transaction (COMMIT) and forces the log to stable storage.
     *
     * @param idTransaction transaction identifier
     * @return true if successfully committed and durable
     */
    boolean commitTransaction(int idTransaction);

    /**
     * Rolls back an active or failed transaction and records its ABORT.
     *
     * @param idTransaction transaction identifier
     * @return true if rollback succeeded
     */
    boolean rollbackTransaction(int idTransaction);

    /**
     * Initiates creation of a quiescent checkpoint.
     *
     * @return true if checkpoint succeeded
     */
    boolean createCheckpoint();

    /**
     * Recovers database state up to a specific timestamp boundary.
     *
     * @param targetTimestamp timestamp in epoch milliseconds
     * @return true if point-in-time recovery succeeded
     */
    boolean recoverToPointInTime(long targetTimestamp);

    /**
     * Recovers database state to current point-in-time.
     *
     * @return true if point-in-time recovery succeeded
     */
    boolean recoverToPointInTime();

    /**
     * Runs crash recovery (ARIES Analysis, Redo, Undo passes) upon startup.
     *
     * @return true if recovery succeeded
     */
    boolean recoverFromCrash();

    /**
     * Runs the Redo phase to reapply committed modifications from the WAL.
     *
     * @return true if redo succeeded
     */
    default boolean redo() {
        return false;
    }

    /**
     * Runs the Undo phase to reverse uncommitted modifications of active transactions.
     *
     * @return true if undo succeeded
     */
    default boolean undo() {
        return false;
    }

    /**
     * Returns the current lifecycle status of a transaction according to the WAL log.
     *
     * @param idTransaction transaction identifier
     * @return current {@link TxStatus}, or null if unknown
     */
    TxStatus getTransactionStatus(int idTransaction);

    @Override
    default void close() {}
}
