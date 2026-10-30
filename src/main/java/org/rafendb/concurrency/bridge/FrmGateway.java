package org.rafendb.concurrency.bridge;

import org.rafendb.common.txn.TxId;

/**
 * Thread-safe FRM access for the bridge (CC-03, CC-04): BEGIN and COMMIT logging and rollback.
 * Stands in for api.recovery until it exists; data-change logging is added once the change record
 * is agreed with FR2. Any RuntimeException means the call did not complete.
 *
 * <p>The bridge reports COMMITTED only after logCommit returns, and releases protection only after
 * rollback returns. Transaction state and locks belong outside this interface.
 */
interface FrmGateway {

    /**
     * Appends a BEGIN record for a new transaction.
     *
     * @param tx the transaction just begun, not null
     * @return the LSN of the BEGIN record
     * @throws NullPointerException if tx is null
     */
    long logBegin(TxId tx);

    /**
     * Appends a COMMIT record and forces the log through it; on return the COMMIT and every
     * earlier record of the transaction are durable. On failure the transaction is not committed.
     *
     * @param tx a PARTIALLY_COMMITTED transaction, not null
     * @return the LSN of the COMMIT record
     * @throws NullPointerException if tx is null
     */
    long logCommit(TxId tx);

    /**
     * Idempotently undoes every change of a FAILED transaction in reverse LSN order, then logs its
     * final ABORT; returns only after undo completes. On failure the transaction stays FAILED and
     * protected.
     *
     * @param tx a FAILED transaction, not null
     * @throws NullPointerException if tx is null
     */
    void rollback(TxId tx);
}
