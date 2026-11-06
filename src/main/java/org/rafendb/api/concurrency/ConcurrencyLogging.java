package org.rafendb.api.concurrency;

import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.TxId;

/**
 * Thread-safe QP-to-CCM write logging (CC-03), provided by the same CCM as ConcurrencyControl.
 * One session operates each transaction at a time; commit/abort must drain in-flight writes.
 */
public interface ConcurrencyLogging {

    /**
     * Forwards one physical change to FRM after WRITE permission and before SM applies it.
     * Call once per physical change, never on WAITING retries. Keep image bytes unchanged until
     * return. On logging failure, stop the write and use the unified CC-04 abort path; if rollback
     * fails, retain FAILED and protection. No success or fabricated LSN may be returned on failure.
     *
     * @param tx the ACTIVE transaction holding WRITE protection, not null
     * @param change its physical row change, not null
     * @return the persistent LSN to pass to SM
     * @throws NullPointerException if an argument or required change field is null
     * @throws IllegalArgumentException if tx is unknown or the change is invalid
     * @throws IllegalStateException if tx is not ACTIVE or lacks WRITE protection
     * @throws ConcurrencyInternalException if logging or the following rollback fails (the FRM error
     *     is the cause)
     */
    long logChange(TxId tx, ChangeRecord change) throws ConcurrencyInternalException;
}
