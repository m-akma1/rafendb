package org.rafendb.processor.api;

import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.api.storage.StorageManager;

/**
 * Provide the execution environment such as dependency to SessionContext, CCM, SM, and FRM
 * required by query operators during query executions.
 *
 * <p>ExecutionContext provides access to the current session, concurrency
 * control, storage management, and recovery management components.<p>
 * ExecutionContext
 */
public class ExecutionContext {
    private final SessionContext sContext;
    private final ConcurrencyControl ccm;
    private final StorageManager sm;
    private final RecoveryManager frm;

    /**
     * Constructs an ExecutionContext with the required session and
     * database management components.
     *
     * @param sContext the session context associated with the current client
     * @param ccm the concurrency control component
     * @param sm the storage manager component
     * @param frm the recovery manager component
     */
    public ExecutionContext(
            SessionContext sContext,
            ConcurrencyControl ccm,
            StorageManager sm,
            RecoveryManager frm) {
        this.sContext = sContext;
        this.ccm = ccm;
        this.sm = sm;
        this.frm = frm;
    }

    /**
     * Returns the session context associated with this execution.
     *
     * @return the current session context
     */
    public SessionContext getSContext() {
        return this.sContext;
    }

    /**
     * Returns the concurrency control component.
     *
     * @return the concurrency control component
     */
    public ConcurrencyControl getConcurrencyControl() {
        return this.ccm;
    }

    /**
     * Returns the storage manager component.
     *
     * @return the storage manager component
     */
    public StorageManager getStorageManager() {
        return this.sm;
    }

    /**
     * Returns the recovery manager component.
     *
     * @return the recovery manager component
     */
    public RecoveryManager getRecoveryManager() {
        return this.frm;
    }
}
