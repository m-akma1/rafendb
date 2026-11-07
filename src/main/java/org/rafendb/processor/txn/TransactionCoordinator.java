package org.rafendb.processor.txn;

import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.processor.ExecutionContext;
import org.rafendb.api.processor.SessionContext;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.api.storage.StorageManager;

public class TransactionCoordinator {
    private final ExecutionContext execContext;

    public TransactionCoordinator(
            SessionContext sContext,
            ConcurrencyControl ccm,
            StorageManager sm,
            RecoveryManager frm) {
        this.execContext = new ExecutionContext(sContext, ccm, sm, frm);
    }

    public ExecutionContext getExecutionContext() {
        return this.execContext;
    }
}
