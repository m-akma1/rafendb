package org.rafendb.concurrency.bridge;

import java.util.List;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.common.error.RecoveryInternalException;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.txn.TransactionManager;
import org.rafendb.mock.recovery.MockRecoveryManager;

/** Test FRM that logs calls, with the status at call time, and can be set to fail. */
final class RecordingRecoveryManager implements RecoveryManager {
    private final List<String> calls;
    private final TransactionManager txns;
    private final MockRecoveryManager mock = new MockRecoveryManager();
    boolean beginSucceeds = true;
    boolean commitSucceeds = true;
    RuntimeException commitError;
    boolean rollbackSucceeds = true;
    RuntimeException rollbackError;
    RecoveryInternalException writeFailure;
    RuntimeException writeError;
    LogPayload lastPayload;

    RecordingRecoveryManager(List<String> calls, TransactionManager txns) {
        this.calls = calls;
        this.txns = txns;
    }

    @Override
    public long writeLog(LogPayload payload) throws RecoveryInternalException {
        TxId tx = payload.tx();
        calls.add("writeLog " + tx + " " + payload.type() + " [" + txns.status(tx) + "]");
        if (writeFailure != null) {
            throw writeFailure;
        }
        if (writeError != null) {
            throw writeError;
        }
        lastPayload = payload;
        return mock.writeLog(payload);
    }

    @Override
    public void forceThrough(long lsn) {
        mock.forceThrough(lsn);
    }

    @Override
    public boolean beginTransaction(TxId tx) {
        calls.add("begin " + tx + " [" + txns.status(tx) + "]");
        return beginSucceeds && mock.beginTransaction(tx);
    }

    @Override
    public boolean commitTransaction(TxId tx) {
        calls.add("commit " + tx + " [" + txns.status(tx) + "]");
        if (commitError != null) {
            throw commitError;
        }
        return commitSucceeds && mock.commitTransaction(tx);
    }

    @Override
    public boolean rollbackTransaction(TxId tx) {
        calls.add("rollback " + tx + " [" + txns.status(tx) + "]");
        if (rollbackError != null) {
            throw rollbackError;
        }
        return rollbackSucceeds && mock.rollbackTransaction(tx);
    }

    @Override
    public boolean createCheckpoint() {
        return mock.createCheckpoint();
    }

    @Override
    public boolean recoverToPointInTime(long targetTimestamp) {
        return mock.recoverToPointInTime(targetTimestamp);
    }

    @Override
    public boolean recoverToPointInTime() {
        return mock.recoverToPointInTime();
    }

    @Override
    public boolean recoverFromCrash() {
        return mock.recoverFromCrash();
    }

    @Override
    public TxStatus getTransactionStatus(TxId tx) {
        return mock.getTransactionStatus(tx);
    }
}
