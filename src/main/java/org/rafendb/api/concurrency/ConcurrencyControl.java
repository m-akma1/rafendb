package org.rafendb.api.concurrency;

import java.time.Duration;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;

/**
 * What the Concurrency Control Manager offers to other components.
 *
 * <p>The same calls work for every protocol. Waiting is non-blocking: when access is not available
 * yet, {@link #requestAccess} returns WAITING and the caller asks again with the same arguments
 * until it gets ALLOWED or ABORTED. Repeating a request never creates a second lock.
 *
 * <p>Both protocols protect whole tables. CCM maps row resources to their normalized table key;
 * row identity is retained separately in change records for logging and Storage Manager writes.
 *
 * <p>Thread-safety: all methods may be called concurrently. Calls for one transaction come from one
 * session thread at a time.
 */
public interface ConcurrencyControl {

    /**
     * Starts a transaction. Waits while new transactions are held ({@link #pauseNewTransactions()}).
     *
     * @return a new id, greater than every id issued before
     * @throws IllegalStateException if the CCM is not accepting transactions, or the thread is
     *     interrupted while waiting
     */
    TxId begin();

    /**
     * Asks permission to read or write a resource. Granted only while the transaction is ACTIVE.
     *
     * @param tx a transaction from {@link #begin()}, not null
     * @param type the kind of access, not null
     * @param resource the data to access, not null
     * @return ALLOWED, WAITING, or ABORTED; ABORTED is also returned to every later request of a
     *     transaction that is failed or aborted
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code tx} is unknown
     * @throws IllegalStateException if {@code tx} is already committing or committed
     */
    AccessResult requestAccess(TxId tx, AccessType type, ResourceId resource);

    /**
     * Commits a transaction. Its commit record is durable before any of its protection is
     * released. Rejected if the transaction is not ACTIVE, e.g. already aborted.
     *
     * @param tx a transaction from {@link #begin()}, not null
     * @return COMMITTED on success, otherwise the current state with the reason
     * @throws NullPointerException if {@code tx} is null
     * @throws IllegalArgumentException if {@code tx} is unknown
     */
    TxResult commit(TxId tx);

    /**
     * Aborts a transaction and returns after its changes were rolled back. Aborting an aborted
     * transaction changes nothing; a committed transaction cannot be aborted.
     *
     * @param tx a transaction from {@link #begin()}, not null
     * @return ABORTED on success, otherwise the current state with the reason
     * @throws NullPointerException if {@code tx} is null
     * @throws IllegalArgumentException if {@code tx} is unknown
     */
    TxResult abort(TxId tx);

    /**
     * Holds back new transactions until {@link #resumeNewTransactions()}. Running transactions can
     * still finish. Does nothing if already held. Used by checkpoints.
     */
    void pauseNewTransactions();

    /**
     * Waits until every transaction that began has finished.
     *
     * @param timeout the longest time to wait, not null and not negative
     * @return {@code true} if none is left, {@code false} if the time ran out
     * @throws NullPointerException if {@code timeout} is null
     * @throws IllegalArgumentException if {@code timeout} is negative
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    boolean awaitNoActive(Duration timeout) throws InterruptedException;

    /** Lets new transactions begin again. Does nothing if not held. */
    void resumeNewTransactions();

    /**
     * Returns the protocol selected by startup configuration: LOCK_BASED by default, or
     * TIMESTAMP_BASED when configured. Display the choice at startup. Configuration changes take
     * effect on the next startup; live switching is unsupported, including while work is in flight.
     *
     * @return the active protocol
     */
    ProtocolType activeProtocol();
}
