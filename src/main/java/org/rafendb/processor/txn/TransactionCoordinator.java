package org.rafendb.processor.txn;

import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.TransactionAbortedException;

/**
 * Coordinates transaction-related operations within a client session.
 *
 * <p>
 * TransactionCoordinator provides access to the execution context
 * and manages the lifecycle of transactions through begin, commit,
 * and abort operations.
 * </p>
 *
 * <p>
 * It uses the ConcurrencyControl, StorageManager, and RecoveryManager
 * components to support transaction processing.
 * </p>
 */
public class TransactionCoordinator {
    private final ExecutionContext execContext;

    /**
     * Constructs a TransactionCoordinator with the required session
     * and database management components.
     *
     * @param sContext the session context associated with the current client
     * @param ccm      the concurrency control component
     * @param sm       the storage manager component
     * @param frm      the recovery manager component
     */
    public TransactionCoordinator(
            SessionContext sContext,
            ConcurrencyControl ccm,
            StorageManager sm,
            RecoveryManager frm) {
        this.execContext = new ExecutionContext(sContext, ccm, sm, frm);
    }

    /**
     * Returns the execution context used by this transaction coordinator.
     *
     * @return the execution context containing the session and database
     *         management components
     */
    public ExecutionContext getExecutionContext() {
        return this.execContext;
    }

    /**
     * Initiates a new transaction for the current client session.
     *
     * <p>
     * The transaction lifecycle is managed through the concurrency
     * control component.
     * </p>
     */
    public void beginTx() throws ConcurrencyInternalException {
        if (!this.getExecutionContext().getSContext().isValid()) {
            throw new IllegalStateException("The session is not valid");
        }

        if (this.execContext.getSContext().getCurrentTxId() != null) {
            throw new IllegalStateException("The session already have running transaction");
        }

        TxId newTxId = this.execContext.getConcurrencyControl().begin();

        if (newTxId == null) {
            throw new ConcurrencyInternalException("CCM failed to provide new transaction Id");
        }

        this.execContext.getSContext().setCurrentTxId(newTxId);
    }

    /**
     * Commits the current transaction.
     *
     * <p>
     * This operation finalizes the transaction and makes its changes
     * permanent according to the transaction management protocol.
     * </p>
     */
    public void commitTx() throws ConcurrencyInternalException {
        if (!this.getExecutionContext().getSContext().isValid()) {
            throw new IllegalStateException("The session is not valid");
        }

        TxId txId = this.getExecutionContext().getSContext().getCurrentTxId();

        if (txId == null) {
            throw new IllegalStateException(
                    "No running transaction in this session, no commit target.");
        }

        TxResult commitRes = this.execContext.getConcurrencyControl().commit(txId);

        switch (commitRes.status()) {
            case COMMITTED -> {
                this.getExecutionContext().getSContext().setCurrentTxId(null);
            }
            case ABORTED -> {
                this.getExecutionContext().getSContext().setCurrentTxId(null);
                throw new TransactionAbortedException(
                        "Commit transaction with id "
                                + txId.toString()
                                + " has been aborted by CCM");
            }
            case FAILED -> {
                throw new TransactionAbortedException(
                        "Commit transaction with id "
                                + txId.toString()
                                + " has been failed. Rollback is not confirmed.");
            }
            case PARTIALLY_COMMITTED -> {
                throw new ConcurrencyInternalException(commitRes.message());
            }
            default -> {
                throw new IllegalStateException("The state shouldn't be " + commitRes.status());
            }
        }
    }

    /**
     * Aborts the current transaction.
     *
     * <p>
     * This operation cancels the transaction and delegates the
     * necessary rollback or recovery operations to the appropriate
     * database management components.
     * </p>
     */
    public void abortTx() throws ConcurrencyInternalException {
        if (!this.getExecutionContext().getSContext().isValid()) {
            throw new IllegalStateException("The session is not valid");
        }

        if (this.getExecutionContext().getSContext().getCurrentTxId() == null) {
            return;
        }

        TxId txId = this.execContext.getSContext().getCurrentTxId();

        TxResult abortRes = this.execContext.getConcurrencyControl().abort(txId);

        switch (abortRes.status()) {
            case ABORTED -> {
                this.getExecutionContext().getSContext().setCurrentTxId(null);
            }
            case FAILED -> {
                throw new ConcurrencyInternalException(
                        "Failed to abort transaction with id: "
                                + txId
                                + ". Rollback is not confirmed");
            }
            case COMMITTED -> {
                this.getExecutionContext().getSContext().setCurrentTxId(null);
                throw new IllegalStateException(
                        "The transaction with id: "
                                + txId
                                + " cannot be Aborted, because it has been COMMITTED.");
            }
            default -> {
                throw new TransactionAbortedException(
                        "The state shouldn't be " + abortRes.status());
            }
        }
    }
}
