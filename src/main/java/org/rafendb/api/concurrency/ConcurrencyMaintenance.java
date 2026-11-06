package org.rafendb.api.concurrency;

import org.rafendb.common.txn.RecoveryInfo;

/**
 * Thread-safe startup initialization (CC-05), provided by the same CCM as ConcurrencyControl.
 * Admission starts closed; checkpoint resume must not bypass this recovery gate.
 */
public interface ConcurrencyMaintenance {

    /**
     * After successful exclusive FRM recovery, clears stale transaction/lock/timestamp state and
     * seeds both generators strictly above the larger recovered high-water mark. Opens admission
     * only on success; failures leave it closed. Never reset while transactions are in flight.
     *
     * @param recovery successful recovery metadata, not null
     * @throws NullPointerException if recovery is null
     * @throws IllegalArgumentException if high-water marks are negative
     * @throws IllegalStateException if initialization already succeeded, work is in flight,
     *     generators are exhausted, or initialization cannot safely finish
     */
    void initializeAfterRecovery(RecoveryInfo recovery);
}
