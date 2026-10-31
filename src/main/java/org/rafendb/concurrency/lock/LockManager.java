package org.rafendb.concurrency.lock;

import java.util.Map;
import java.util.Set;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;

/**
 * Thread-safe table-level rigorous 2PL (CC-06): READ takes S, WRITE takes X. Multiple S holders
 * may coexist; X excludes other holders on the table. The CCM maps row requests to their table
 * before calling this manager; all accesses and scans use the same normalized table key.
 *
 * <p>The CCM coordinator admits only ACTIVE transactions and serializes each transaction's
 * requests with commit/abort. Locks remain held until durable commit or successful rollback.
 * Transaction state, logging, rollback, and deadlock handling belong outside this interface.
 */
public interface LockManager {

    /**
     * Attempts acquisition without blocking. Unsatisfied requests queue FIFO per table; only the
     * head may acquire on retry.
     * Consecutive readers may hold S together after acquiring in order.
     * Retry the same request before another access; polling never duplicates locks or queue entries.
     *
     * <p>Already-held or weaker locks succeed immediately, bypassing the queue. S-to-X upgrades
     * retain S and follow FIFO, waiting for other holders; resulting deadlocks require CC-07.
     *
     * @param tx an ACTIVE transaction admitted by the coordinator, not null
     * @param type READ for S or WRITE for X, not null
     * @param resource a table-level resource with an already normalized table name, not null
     * @return true if the requested protection is held; false if queued and access must not execute
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if resource is not table-level
     */
    boolean tryAcquire(TxId tx, AccessType type, ResourceId resource);

    /**
     * Returns a non-null, consistent, deeply unmodifiable wait-for snapshot for CC-07. Include
     * holder and queue-order blockers, including upgrades; exclude self-edges.
     */
    Map<TxId, Set<TxId>> waitForGraph();

    /**
     * Idempotently removes queued requests, including upgrades, without releasing held locks.
     * The coordinator marks FAILED and stops new requests before cancellation and rollback.
     *
     * @param tx a transaction whose new requests have been stopped, not null
     * @throws NullPointerException if tx is null
     */
    void cancelPending(TxId tx);

    /**
     * Idempotently removes held locks and queued requests; waiters acquire on their next retry.
     * Call only after durable commit or successful rollback, with no new or in-flight accesses.
     * A FAILED transaction whose rollback has not succeeded must retain its locks.
     *
     * @param tx a transaction whose durable commit or rollback has completed, not null
     * @throws NullPointerException if tx is null
     */
    void releaseAll(TxId tx);
}
