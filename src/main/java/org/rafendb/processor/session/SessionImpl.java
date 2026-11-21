package org.rafendb.processor.session;

import java.util.Objects;
import java.util.function.Supplier;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.processor.Session.TxState;
import org.rafendb.api.storage.StorageQuery;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.ProcessorRuntimeException;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.processor.api.SessionView;
import org.rafendb.processor.error.TransactionAbortedException;

/**
 * One client's session. It is the only writer of its transaction state; operators read it through
 * {@link SessionView}.
 *
 * <p>Transactions follow PostgreSQL: any error inside BEGIN rolls the transaction back at once
 * (releasing its locks) and leaves the session {@link TxState#FAILED}, where only ABORT is accepted.
 * A statement outside BEGIN runs in its own transaction, committed on success and rolled back on
 * error.
 *
 * <p>Rolling back is always "make sure CCM has aborted it": CCM's abort is idempotent, so it does not
 * matter whether CCM, an operator, or the session aborted first. If CCM cannot confirm the rollback,
 * the transaction is kept and the session stays FAILED, so a later ABORT retries it.
 *
 * <p>Used by one thread at a time. {@link #transactionState()} and {@link #close()} may be called
 * from another thread.
 */
final class SessionImpl implements SessionView {
    private final ConcurrencyControl ccm;
    private final StorageQuery storage;

    private volatile TxState state = TxState.IDLE;
    private volatile TxId tx;
    private volatile boolean implicit;
    private volatile boolean open = true;

    SessionImpl(ConcurrencyControl ccm, StorageQuery storage) {
        this.ccm = Objects.requireNonNull(ccm, "ccm");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public TxId currentTx() {
        return tx;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public ConcurrencyControl ccm() {
        return ccm;
    }

    @Override
    public StorageQuery storage() {
        return storage;
    }

    /**
     * Returns the transaction state; IDLE once closed.
     *
     * @return the state
     */
    TxState transactionState() {
        return open ? state : TxState.IDLE;
    }

    /** BEGIN. Inside a transaction it is an error, which fails that transaction. */
    void begin() {
        requireOpen();
        switch (state) {
            case IDLE -> startTx(false);
            case ACTIVE -> {
                ProcessorRuntimeException error =
                        new ProcessorRuntimeException("A transaction is already in progress");
                fail(error);
                throw error;
            }
            case FAILED -> throw onlyAbortAccepted();
        }
    }

    /** COMMIT of the explicit transaction. */
    void commit() {
        requireOpen();
        switch (state) {
            case IDLE -> throw noTransaction();
            case ACTIVE -> commitTx();
            case FAILED -> throw onlyAbortAccepted();
        }
    }

    /** ABORT of the explicit or failed transaction. */
    void abort() {
        requireOpen();
        if (state == TxState.IDLE) {
            throw noTransaction();
        }
        Exception unconfirmed = rollBack();
        if (unconfirmed != null) {
            state = TxState.FAILED;
            throw new ProcessorRuntimeException(
                    "The transaction could not be rolled back yet; run ABORT again", unconfirmed);
        }
        end();
    }

    /**
     * Runs one statement inside the current transaction, or inside its own when there is none.
     *
     * @param statement the work; it may read {@link #requireTx()}
     * @return what the statement returned
     */
    <T> T runStatement(Supplier<T> statement) {
        Objects.requireNonNull(statement, "statement");
        requireOpen();
        switch (state) {
            case IDLE -> startTx(true);
            case ACTIVE -> {}
            case FAILED -> throw onlyAbortAccepted();
        }
        T result;
        try {
            result = statement.get();
        } catch (RuntimeException | Error e) {
            fail(e);
            throw e;
        }
        if (implicit) {
            commitTx();
        }
        return result;
    }

    /**
     * Closes the session: rolls back its transaction, if any, and stops its lock waits. Idempotent.
     * A rollback CCM cannot confirm stays with CCM; the session is gone either way.
     */
    synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        rollBack();
        end();
    }

    /**
     * Synchronized with close, and checks again inside, so a transaction never starts on a session
     * that another thread is closing.
     */
    private synchronized void startTx(boolean implicitTx) {
        requireOpen();
        TxId started = ccm.begin();
        if (started == null) {
            throw new IllegalStateException("CCM began a transaction without an id");
        }
        tx = started;
        implicit = implicitTx;
        state = TxState.ACTIVE;
    }

    private void commitTx() {
        TxId committing = tx;
        TxResult result;
        try {
            result = ccm.commit(committing);
        } catch (ConcurrencyInternalException e) {
            throw endFailedCommit(
                    new TransactionAbortedException(
                            "The transaction could not be committed and was rolled back", e));
        }
        if (result.status() != TxStatus.COMMITTED) {
            throw endFailedCommit(
                    new TransactionAbortedException(
                            "The transaction was rolled back instead of committed: "
                                    + result.message()));
        }
        end();
    }

    /** A failed commit ends the transaction, unless its rollback cannot be confirmed. */
    private TransactionAbortedException endFailedCommit(TransactionAbortedException error) {
        Exception unconfirmed = rollBack();
        if (unconfirmed == null) {
            end();
        } else {
            error.addSuppressed(unconfirmed);
            state = TxState.FAILED;
        }
        return error;
    }

    /** An error inside the open transaction: roll it back, then IDLE (implicit) or FAILED. */
    private void fail(Throwable error) {
        Exception unconfirmed = rollBack();
        if (unconfirmed != null) {
            error.addSuppressed(unconfirmed);
        }
        if (implicit && unconfirmed == null) {
            end();
        } else {
            state = TxState.FAILED;
        }
    }

    /**
     * Makes sure CCM has aborted the current transaction, if any.
     *
     * @return {@code null} once the transaction is gone; otherwise why CCM could not confirm the
     *     rollback, and the transaction is kept for a retry
     */
    private Exception rollBack() {
        TxId aborting = tx;
        if (aborting == null) {
            return null;
        }
        try {
            TxResult result = ccm.abort(aborting);
            return switch (result.status()) {
                case ABORTED -> {
                    tx = null;
                    yield null;
                }
                case COMMITTED -> {
                    tx = null; // nothing left to roll back; report it rather than retry forever
                    yield new IllegalStateException(aborting + " was already committed");
                }
                default ->
                        new IllegalStateException(
                                "CCM answered " + result.status() + " to aborting " + aborting);
            };
        } catch (ConcurrencyInternalException | RuntimeException e) {
            return e;
        }
    }

    private void end() {
        tx = null;
        implicit = false;
        state = TxState.IDLE;
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("The session is closed");
        }
    }

    private static ProcessorRuntimeException noTransaction() {
        return new ProcessorRuntimeException("No transaction is in progress");
    }

    private static ProcessorRuntimeException onlyAbortAccepted() {
        return new ProcessorRuntimeException(
                "The current transaction has failed; only ABORT is accepted");
    }
}
