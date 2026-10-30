package org.rafendb.concurrency.bridge;

import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;

/**
 * Thread-safe view of the coordinator's transaction table (CC-01) for the bridge: status,
 * transitions, and the reset after recovery (CC-05). Stands in for CC1's transaction manager until
 * it exists.
 *
 * <p>The bridge drives PARTIALLY_COMMITTED, COMMITTED, FAILED and ABORTED in the order the commit
 * and abort flows require; this interface only rejects illegal transitions.
 */
interface TransactionStates {

    /**
     * Returns the current status of a transaction.
     *
     * @param tx a transaction from begin, not null
     * @return its status, never null
     * @throws NullPointerException if tx is null
     * @throws IllegalArgumentException if tx is unknown
     */
    TxStatus status(TxId tx);

    /**
     * Moves a transaction to the next status. COMMITTED and ABORTED are final.
     *
     * @param tx a transaction from begin, not null
     * @param next the new status, not null
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if tx is unknown
     * @throws IllegalStateException if the transition is not allowed
     */
    void setStatus(TxId tx, TxStatus next);

    /**
     * Forgets every transaction and makes later transaction ids and timestamps greater than
     * maxTxId. Call only after recovery, with no transaction running.
     *
     * @param maxTxId the highest transaction id in the whole log, or 0 if it has none
     * @throws IllegalArgumentException if maxTxId is negative
     */
    void resetAbove(long maxTxId);
}
