package org.rafendb.api.processor;

import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.api.storage.StorageManager;

public class ExecutionContext {
    private final SessionContext sContext;
    private final ConcurrencyControl ccm;
    private final StorageManager sm;
    private final RecoveryManager frm;

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

    public SessionContext getSContext() {
        return this.sContext;
    }

    public ConcurrencyControl getConcurrencyControl() {
        return this.ccm;
    }

    public StorageManager getStorageManager() {
        return this.sm;
    }

    public RecoveryManager getRecoveryManager() {
        return this.frm;
    }
}
