package org.rafendb.processor.txn;

import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.TxId;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;

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
    public void beginTx() {
        if (this.getExecutionContext().getSContext().isValid()) {
            throw new IllegalStateException("The session is not valid");
        }

        if (this.execContext.getSContext().getCurrentTxId() != null) {
            throw new IllegalStateException("The session already have running transaction");
        }

        TxId newTxId = this.execContext.getConcurrencyControl().begin();
        if (newTxId != null) {
            this.execContext.getSContext().setCurrentTxId(newTxId);
        }
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
        // TxResult commitRes =
        //         this.execContext
        //                 .getConcurrencyControl()
        //                 .commit(this.execContext.getSContext().getCurrentTxId());

        // TO-Do = change currenttx id and handle if tx status is aborted.
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
        // TxResult abortRes =
        //         this.execContext
        //                 .getConcurrencyControl()
        //                 .abort(this.execContext.getSContext().getCurrentTxId());
        // TO-Do = change currenttx id and handle if aborted is failed
    }
}
