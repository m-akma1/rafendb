package org.rafendb.concurrency.bridge;

import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;

/**
 * Thread-safe release of the active protocol's protection for the bridge (CC-04): LockManager under
 * lock-based, TimestampProtocol under timestamp-based. Stands in for CC1's protocol strategy until
 * it exists.
 *
 * <p>The bridge cancels pending requests when a transaction fails, and releases protection only
 * after durable commit or successful rollback. Deciding access belongs outside this interface.
 */
interface ProtectionRelease {

    /**
     * Idempotently removes a FAILED transaction's queued requests without releasing what it holds;
     * does nothing under timestamp-based.
     *
     * @param tx a FAILED transaction whose new requests have been stopped, not null
     * @throws NullPointerException if tx is null
     */
    void cancelPending(TxId tx);

    /**
     * Idempotently releases every lock or timestamp ownership of a finished transaction and lets
     * waiters retry. Call only after durable commit or successful rollback.
     *
     * @param tx a transaction whose durable commit or rollback has completed, not null
     * @param outcome COMMITTED or ABORTED, not null
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if outcome is not final
     */
    void release(TxId tx, TxStatus outcome);
}
