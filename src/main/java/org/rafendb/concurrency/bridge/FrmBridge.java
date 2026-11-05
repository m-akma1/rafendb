package org.rafendb.concurrency.bridge;

import java.util.Objects;
import org.rafendb.api.concurrency.ConcurrencyMaintenance;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.concurrency.protocol.Protocol;

/**
 * Thread-safe FRM bridge of the CCM (CC-03 to CC-05). The coordinator delegates logging, the abort
 * flow and post-recovery initialization here; QP never calls the FRM directly.
 *
 * <p>Admission starts closed. Only a successful initializeAfterRecovery opens it, so no transaction
 * begins before FRM recovery has finished. Checkpoint pause and resume belong outside this class.
 */
public final class FrmBridge implements ConcurrencyMaintenance {
    private final RecoveryManager frm;
    private final TransactionStates states;
    private final Protocol protocol;
    private volatile boolean admitting;

    /**
     * Creates a bridge with admission closed.
     *
     * @param frm the recovery manager, not null
     * @param states the coordinator's transaction table, not null
     * @param protocol the active protocol, not null
     * @throws NullPointerException if an argument is null
     */
    public FrmBridge(RecoveryManager frm, TransactionStates states, Protocol protocol) {
        this.frm = Objects.requireNonNull(frm, "frm");
        this.states = Objects.requireNonNull(states, "states");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    /**
     * Returns whether new transactions may begin; false until initializeAfterRecovery succeeds.
     *
     * @return true once admission is open
     */
    public boolean isAdmitting() {
        return admitting;
    }

    /**
     * {@inheritDoc}
     *
     * <p>No transaction can be in flight here: begin is refused until this succeeds once, and later
     * calls are rejected. Admission opens only after both resets have returned.
     */
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
}
