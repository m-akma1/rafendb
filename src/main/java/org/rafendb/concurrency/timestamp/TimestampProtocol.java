package org.rafendb.concurrency.timestamp;

import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;

/**
 * Thread-safe table-level strict timestamp ordering (CC-08), without Thomas' Write Rule.
 * The coordinator maps row requests to normalized table keys and assigns unique increasing begin
 * timestamps; restart uses a new transaction and timestamp. R-TS/W-TS and writer ownership are
 * tracked per table, including accesses from scans and inserts.
 */
public interface TimestampProtocol {

    /** Internal decisions; ABORT_REQUIRED does not mean rollback has finished. */
    enum Decision {
        ALLOWED,
        WAITING,
        ABORT_REQUIRED
    }

    /**
     * Non-blocking: reject reads whose timestamp is lower than W-TS, and writes whose timestamp
     * is lower than R-TS or W-TS. Otherwise wait for older uncommitted writers.
     * Revalidate retries; update metadata only when allowed.
     * The coordinator serializes validation/access against conflicts and abort, finishes each
     * operation before the next, and routes rejection through CC-04. Waits go younger-to-older;
     * holders must finish or safely abort to unblock waiters.
     *
     * @param tx the ACTIVE transaction, not null
     * @param timestamp its fixed, positive begin timestamp
     * @param type READ or WRITE, not null
     * @param resource the protected table-level resource, not null
     * @return the access decision, never null
     * @throws NullPointerException if an object argument is null
     * @throws IllegalArgumentException if timestamp is not positive or resource is not table-level
     */
    Decision requestAccess(TxId tx, long timestamp, AccessType type, ResourceId resource);

    /**
     * Idempotently clears ownership/pending requests after durable commit or successful rollback
     * and drained accesses. Retains R-TS/W-TS history; FAILED transactions remain protected.
     *
     * @param tx the finished transaction, not null
     * @param status COMMITTED or ABORTED, not null
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if status is not terminal
     */
    void complete(TxId tx, TxStatus status);
}
