package org.rafendb.concurrency.bridge;

import java.util.Objects;
import org.rafendb.api.concurrency.ConcurrencyMaintenance;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.protocol.Protocol;

public final class FrmBridge implements ConcurrencyMaintenance {
    private final RecoveryManager frm;
    private final TransactionStates states;
    private final Protocol protocol;
    private volatile boolean admitting;

    /**
     * Creates a bridge with admission closed
     * @throws NullPointerException if an argument is null
     */
    public FrmBridge(RecoveryManager frm, TransactionStates states, Protocol protocol) {
        this.frm = Objects.requireNonNull(frm, "frm");
        this.states = Objects.requireNonNull(states, "states");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    /** Returns true once initializeAfterRecovery has succeeded. */
    public boolean isAdmitting() {
        return admitting;
    }

    @Override
    public synchronized void initializeAfterRecovery(RecoveryInfo recovery) {
        Objects.requireNonNull(recovery, "recovery");
        if (recovery.maxTransactionId() < 0 || recovery.maxTimestamp() < 0) {
            throw new IllegalArgumentException(
                    "high-water marks must not be negative: " + recovery);
        }
        if (admitting) {
            throw new IllegalStateException("CCM is already initialized after recovery");
        }
        states.resetAbove(Math.max(recovery.maxTransactionId(), recovery.maxTimestamp()));
        protocol.reset();
        admitting = true;
    }

    /**
     * Logs BEGIN for a new transaction (CC-03).
     *
     * @throws IllegalStateException if admission is closed or BEGIN cannot be logged
     */
    public void logBegin(TxId tx) {
        Objects.requireNonNull(tx, "tx");
        if (!admitting) {
            throw new IllegalStateException("CCM is not accepting transactions before recovery");
        }
        boolean logged;
        try {
            logged = frm.beginTransaction(tx);
        } catch (RuntimeException e) {
            throw new IllegalStateException("BEGIN of " + tx + " could not be logged", e);
        }
        if (!logged) {
            throw new IllegalStateException("BEGIN of " + tx + " could not be logged");
        }
    }

    /**
     * Commits durably (CC-03). COMMIT reaches the log before COMMITTED is set and protection is
     * released. If COMMIT cannot be logged, the transaction is aborted.
     *
     * @return COMMITTED, or the current status with a reason if tx is not ACTIVE
     * @throws IllegalArgumentException if tx is unknown
     * @throws ConcurrencyInternalException if COMMIT cannot be logged
     */
    public TxResult commit(TxId tx) throws ConcurrencyInternalException {
        Objects.requireNonNull(tx, "tx");
        TxStatus status = states.status(tx);
        if (status != TxStatus.ACTIVE) {
            return new TxResult(status, tx + " is " + status + " and cannot commit");
        }
        states.setStatus(tx, TxStatus.PARTIALLY_COMMITTED);
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
        states.setStatus(tx, TxStatus.COMMITTED);
        protocol.release(tx, TxStatus.COMMITTED);
        return new TxResult(TxStatus.COMMITTED, "");
    }

    /**
     * Single abort flow for every reason (CC-04). Protection is kept until the FRM rollback
     * succeeds, aborting an ABORTED transaction does nothing. Synchronized so one transaction is
     * never rolled back twice at once.
     *
     * @return ABORTED, or COMMITTED with a reason if tx already committed
     * @throws IllegalArgumentException if tx is unknown
     * @throws ConcurrencyInternalException if rollback fails; tx stays FAILED and protected
     */
    public synchronized TxResult abort(TxId tx) throws ConcurrencyInternalException {
        Objects.requireNonNull(tx, "tx");
        TxStatus status = states.status(tx);
        if (status == TxStatus.ABORTED) {
            return new TxResult(TxStatus.ABORTED, "");
        }
        if (status == TxStatus.COMMITTED) {
            return new TxResult(TxStatus.COMMITTED, tx + " is committed and cannot be aborted");
        }
        if (status != TxStatus.FAILED) {
            states.setStatus(tx, TxStatus.FAILED);
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
        states.setStatus(tx, TxStatus.ABORTED);
        protocol.release(tx, TxStatus.ABORTED);
        return new TxResult(TxStatus.ABORTED, "");
    }
}
