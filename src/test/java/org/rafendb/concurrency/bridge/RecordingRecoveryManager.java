package org.rafendb.concurrency.bridge;

import java.util.List;
import org.rafendb.common.txn.TxId;
import org.rafendb.mock.recovery.MockRecoveryManager;

/** Test FRM that logs calls to a shared call list and can be set to fail. */
final class RecordingRecoveryManager extends MockRecoveryManager {
    private final List<String> calls;
    boolean beginSucceeds = true;
    boolean commitSucceeds = true;
    RuntimeException commitError;
    boolean rollbackSucceeds = true;
    RuntimeException rollbackError;

    RecordingRecoveryManager(List<String> calls) {
        this.calls = calls;
    }

    @Override
    public boolean beginTransaction(TxId tx) {
        calls.add("begin " + tx);
        return beginSucceeds && super.beginTransaction(tx);
    }

    @Override
    public boolean commitTransaction(TxId tx) {
        calls.add("commit " + tx);
        if (commitError != null) {
            throw commitError;
        }
        return commitSucceeds && super.commitTransaction(tx);
    }

    @Override
    public boolean rollbackTransaction(TxId tx) {
        calls.add("rollback " + tx);
        if (rollbackError != null) {
            throw rollbackError;
        }
        return rollbackSucceeds && super.rollbackTransaction(tx);
    }
}
