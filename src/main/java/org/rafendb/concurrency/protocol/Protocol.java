package org.rafendb.concurrency.protocol;

import java.util.Optional;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;

/**
 * Strategy for the active protocol, lock-based or timestamp-based.
 */
public interface Protocol {

    /** Decision for one access request. */
    enum Decision {
        ALLOWED,
        WAITING,
        /** The access breaks timestamp order; the facade must abort the transaction. */
        ABORT_REQUIRED
    }

    /** Returns the protocol this object implements. */
    ProtocolType type();

    /**
     * Decides an access request without blocking; retrying while WAITING creates no duplicates.
     * Lock-based ignores {@code timestamp} and never returns ABORT_REQUIRED.
     *
     * @param resource a table-level resource with a normalized table name
     * @throws IllegalArgumentException if timestamp is not positive or resource is not table-level
     */
    Decision requestAccess(TxId tx, long timestamp, AccessType type, ResourceId resource);

    /** Removes a FAILED transaction's queued requests but keeps what it holds. No-op under timestamp.*/
    void cancelPending(TxId tx);

    /**
     * Releases a finished transaction's protection. Call only after durable commit or successful
     * rollback.
     *
     * @param outcome COMMITTED or ABORTED
     * @throws IllegalArgumentException if outcome is not final
     */
    void release(TxId tx, TxStatus outcome);

    /** Returns a deadlock victim, or empty if none; always empty under timestamp-based. */
    Optional<TxId> selectDeadlockVictim();

    /** Forgets all locks and timestamp records. Call only after recovery, with no transaction running. */
    void reset();
}
