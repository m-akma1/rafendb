package org.rafendb.concurrency;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.api.concurrency.ConcurrencyMaintenance;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.bridge.FrmBridge;
import org.rafendb.concurrency.protocol.Protocol;
import org.rafendb.concurrency.txn.TransactionManager;

public final class ConcurrencyControlManager
        implements ConcurrencyControl, ConcurrencyLogging, ConcurrencyMaintenance {
    private final TransactionManager txns;
    private final Protocol protocol;
    private final FrmBridge bridge;

    public ConcurrencyControlManager(
            TransactionManager txns, Protocol protocol, RecoveryManager frm) {
        this.txns = Objects.requireNonNull(txns, "txns");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.bridge = new FrmBridge(frm, txns, protocol);
    }

    @Override
    public TxId begin() {
        TxId tx = txns.begin();
        bridge.logBegin(tx);
        return tx;
    }

    /** Detects cycles when a request waits; rollback duration depends on FRM. */
    @Override
    public AccessResult requestAccess(TxId tx, AccessType type, ResourceId resource) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(resource, "resource");
        TxId victim = null;
        Protocol.Decision decision;
        synchronized (txns) {
            TxStatus status = txns.status(tx);
            switch (status) {
                case ACTIVE -> {}
                case FAILED, ABORTED -> {
                    return AccessResult.aborted(abortedMessage(tx));
                }
                case PARTIALLY_COMMITTED, COMMITTED ->
                        throw new IllegalStateException(tx + " is already " + status);
            }
            ResourceId table =
                    ResourceId.ofTable(resource.table().strip().toLowerCase(Locale.ROOT));
            decision = protocol.requestAccess(tx, txns.timestamp(tx), type, table);
            if (decision == Protocol.Decision.ABORT_REQUIRED) {
                victim = tx;
            } else if (decision == Protocol.Decision.WAITING) {
                victim = protocol.selectDeadlockVictim().orElse(null);
            }
            if (victim != null) {
                // Stop new accesses while the wait-for snapshot is still valid.
                txns.transition(victim, TxStatus.FAILED);
                protocol.cancelPending(victim);
            }
        }
        if (victim != null) {
            abortAfterViolation(victim);
        }
        if (tx.equals(victim)) {
            return AccessResult.aborted(abortedMessage(tx));
        }
        return switch (decision) {
            case ALLOWED -> AccessResult.allowed();
            case WAITING -> AccessResult.waiting();
            case ABORT_REQUIRED -> throw new IllegalStateException("missing abort victim");
        };
    }

    private void abortAfterViolation(TxId tx) {
        try {
            bridge.abort(tx);
        } catch (ConcurrencyInternalException e) {

        }
    }

    private static String abortedMessage(TxId tx) {
        return tx + " has been aborted";
    }

    @Override
    public TxResult commit(TxId tx) throws ConcurrencyInternalException {
        return bridge.commit(tx);
    }

    @Override
    public TxResult abort(TxId tx) throws ConcurrencyInternalException {
        return bridge.abort(tx);
    }

    @Override
    public long logChange(TxId tx, ChangeRecord change) throws ConcurrencyInternalException {
        return bridge.logChange(tx, change);
    }

    @Override
    public void initializeAfterRecovery(RecoveryInfo recovery) {
        bridge.initializeAfterRecovery(recovery);
    }

    @Override
    public void pauseNewTransactions() {
        txns.pause();
    }

    @Override
    public boolean awaitNoActive(Duration timeout) throws InterruptedException {
        return txns.awaitNoActive(timeout);
    }

    @Override
    public void resumeNewTransactions() {
        txns.resume();
    }

    @Override
    public ProtocolType activeProtocol() {
        return protocol.type();
    }
}
