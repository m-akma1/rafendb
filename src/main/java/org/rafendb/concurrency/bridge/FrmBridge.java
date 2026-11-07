package org.rafendb.concurrency.bridge;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.api.concurrency.ConcurrencyMaintenance;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.RecoveryInternalException;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.log.LogType;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.protocol.Protocol;
import org.rafendb.concurrency.txn.TransactionManager;

public final class FrmBridge implements ConcurrencyLogging, ConcurrencyMaintenance {
    private final RecoveryManager frm;
    private final TransactionManager txns;
    private final Protocol protocol;
    private boolean initialized;

    public FrmBridge(RecoveryManager frm, TransactionManager txns, Protocol protocol) {
        this.frm = Objects.requireNonNull(frm, "frm");
        this.txns = Objects.requireNonNull(txns, "txns");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    @Override
    public synchronized void initializeAfterRecovery(RecoveryInfo recovery) {
        Objects.requireNonNull(recovery, "recovery");
        if (recovery.maxTransactionId() < 0 || recovery.maxTimestamp() < 0) {
            throw new IllegalArgumentException(
                    "high-water marks must not be negative: " + recovery);
        }
        if (initialized) {
            throw new IllegalStateException("CCM is already initialized after recovery");
        }
        protocol.reset();
        txns.open(Math.max(recovery.maxTransactionId(), recovery.maxTimestamp()));
        initialized = true;
    }

    public void logBegin(TxId tx) {
        Objects.requireNonNull(tx, "tx");
        boolean logged = false;
        RuntimeException error = null;
        try {
            logged = frm.beginTransaction(tx);
        } catch (RuntimeException e) {
            error = e;
        }
        if (!logged) {
            txns.transition(tx, TxStatus.FAILED);
            txns.transition(tx, TxStatus.ABORTED);
            throw new IllegalStateException("BEGIN of " + tx + " could not be logged", error);
        }
    }

    @Override
    public long logChange(TxId tx, ChangeRecord change) throws ConcurrencyInternalException {
        Objects.requireNonNull(tx, "tx");
        LogType type = typeOf(change);
        TxStatus status = txns.status(tx);
        if (status != TxStatus.ACTIVE) {
            throw new IllegalStateException(tx + " is " + status + " and cannot write");
        }
        ResourceId table =
                ResourceId.ofTable(change.resource().table().strip().toLowerCase(Locale.ROOT));
        if (!protocol.holdsWrite(tx, table)) {
            throw new IllegalStateException(tx + " does not hold WRITE protection for " + table);
        }
        try {
            return frm.writeLog(LogPayload.ofChange(tx, type, change));
        } catch (RecoveryInternalException | RuntimeException e) {
            ConcurrencyInternalException failure =
                    new ConcurrencyInternalException("change of " + tx + " could not be logged", e);
            try {
                abort(tx);
            } catch (ConcurrencyInternalException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private static LogType typeOf(ChangeRecord change) {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(change.resource(), "resource");
        Optional<byte[]> before = Objects.requireNonNull(change.beforeImage(), "beforeImage");
        Optional<byte[]> after = Objects.requireNonNull(change.afterImage(), "afterImage");
        if (change.resource().isTable()) {
            throw new IllegalArgumentException(
                    "change needs a row, not table " + change.resource().table());
        }
        if (before.isEmpty() && after.isEmpty()) {
            throw new IllegalArgumentException("change has neither a before nor an after image");
        }
        if (before.isEmpty()) {
            return LogType.INSERT;
        }
        return after.isEmpty() ? LogType.DELETE : LogType.UPDATE;
    }

    public TxResult commit(TxId tx) throws ConcurrencyInternalException {
        Objects.requireNonNull(tx, "tx");
        TxStatus status = txns.status(tx);
        if (status != TxStatus.ACTIVE) {
            return new TxResult(status, tx + " is " + status + " and cannot commit");
        }
        txns.transition(tx, TxStatus.PARTIALLY_COMMITTED);
        boolean durable = false;
        RuntimeException error = null;
        try {
            durable = frm.commitTransaction(tx);
        } catch (RuntimeException e) {
            error = e;
        }
        if (!durable) {
            ConcurrencyInternalException failure =
                    new ConcurrencyInternalException(
                            "COMMIT of " + tx + " could not be logged", error);
            try {
                abort(tx);
            } catch (ConcurrencyInternalException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        txns.transition(tx, TxStatus.COMMITTED);
        protocol.release(tx, TxStatus.COMMITTED);
        return new TxResult(TxStatus.COMMITTED, "");
    }

    public synchronized TxResult abort(TxId tx) throws ConcurrencyInternalException {
        Objects.requireNonNull(tx, "tx");
        TxStatus status = txns.status(tx);
        if (status == TxStatus.ABORTED) {
            return new TxResult(TxStatus.ABORTED, "");
        }
        if (status == TxStatus.COMMITTED) {
            return new TxResult(TxStatus.COMMITTED, tx + " is committed and cannot be aborted");
        }
        if (status != TxStatus.FAILED) {
            txns.transition(tx, TxStatus.FAILED);
        }
        protocol.cancelPending(tx);
        boolean rolledBack;
        try {
            rolledBack = frm.rollbackTransaction(tx);
        } catch (RuntimeException e) {
            throw new ConcurrencyInternalException("rollback of " + tx + " failed", e);
        }
        if (!rolledBack) {
            throw new ConcurrencyInternalException("rollback of " + tx + " failed");
        }
        txns.transition(tx, TxStatus.ABORTED);
        protocol.release(tx, TxStatus.ABORTED);
        return new TxResult(TxStatus.ABORTED, "");
    }
}
