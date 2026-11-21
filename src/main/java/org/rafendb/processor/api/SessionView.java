package org.rafendb.processor.api;

import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.storage.StorageQuery;
import org.rafendb.common.txn.TxId;

/**
 * What an operator may know about the session it runs in: the session's current transaction, whether
 * the session is still open, and the components it reaches through.
 *
 * <p>Read-only. Only the session itself starts, commits, and aborts its transaction. When CCM gives
 * up on the transaction, an operator may still call {@code ccm().abort} (it is idempotent) to
 * release locks early, then throws {@link
 * org.rafendb.processor.error.TransactionAbortedException}; the session brings its own state in line
 * when it catches that.
 */
public interface SessionView {

    /**
     * Returns the session's current transaction.
     *
     * @return the transaction, or {@code null} when the session has none
     */
    TxId currentTx();

    /**
     * Returns whether the session is still open. A closed session's waits for locks must stop.
     *
     * @return {@code false} once the session is closed
     */
    boolean isOpen();

    /**
     * Returns the concurrency control component shared by all sessions.
     *
     * @return the CCM
     */
    ConcurrencyControl ccm();

    /**
     * Returns the storage component shared by all sessions.
     *
     * @return the storage, through its query interface only
     */
    StorageQuery storage();

    /**
     * Returns the current transaction of an open session, for work that must run inside one.
     *
     * @return the current transaction, never {@code null}
     * @throws IllegalStateException if the session is closed or has no transaction
     */
    default TxId requireTx() {
        if (!isOpen()) {
            throw new IllegalStateException("The session is closed");
        }
        TxId tx = currentTx();
        if (tx == null) {
            throw new IllegalStateException(
                    "No active transaction: run this inside a session statement");
        }
        return tx;
    }
}
