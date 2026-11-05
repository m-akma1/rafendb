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

/**
 * Thread-safe link from the CCM to the FRM (CC-03 to CC-05). Admission stays closed until
 * initializeAfterRecovery succeeds.
 */
public final class FrmBridge implements ConcurrencyMaintenance {
    private final RecoveryManager frm;
    private final TransactionStates states;
    private final Protocol protocol;
    private volatile boolean admitting;

    /**
     * Creates a bridge with admission closed.
     *
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
     * Single abort flow for every reason (CC-04). Protection is kept until the FRM rollback
     * succeeds; aborting an ABORTED transaction does nothing. Synchronized so one transaction is
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
