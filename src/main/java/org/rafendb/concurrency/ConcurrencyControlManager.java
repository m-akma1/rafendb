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

/**
 * The CCM facade (CC-01, CC-02): the one entry point other components use. It owns the transaction
 * table and forwards access decisions to the configured {@link Protocol}. The protocol is chosen
 * once at startup. Logging, commit, abort and recovery initialization go through {@link FrmBridge}
 * (CC-03 to CC-05).
 */
public final class ConcurrencyControlManager
        implements ConcurrencyControl, ConcurrencyLogging, ConcurrencyMaintenance {
    private final TransactionManager txns;
    private final Protocol protocol;
    private final FrmBridge bridge;

    /**
     * Creates the facade.
     *
     * @param txns the transaction table; admission opens with {@link #initializeAfterRecovery}
     * @param protocol the protocol selected at startup
     * @param frm the recovery manager the CCM logs and rolls back through
     */
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

    @Override
    public AccessResult requestAccess(TxId tx, AccessType type, ResourceId resource) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(resource, "resource");
        TxStatus status = txns.status(tx);
        switch (status) {
            case ACTIVE -> {}
            case FAILED, ABORTED -> {
                return AccessResult.aborted(abortedMessage(tx));
            }
            case PARTIALLY_COMMITTED, COMMITTED ->
                    throw new IllegalStateException(tx + " is already " + status);
        }
        ResourceId table = ResourceId.ofTable(resource.table().strip().toLowerCase(Locale.ROOT));
        return switch (protocol.requestAccess(tx, txns.timestamp(tx), type, table)) {
            case ALLOWED -> AccessResult.allowed();
            case WAITING -> AccessResult.waiting();
            case ABORT_REQUIRED -> {
                abortAfterViolation(tx);
                yield AccessResult.aborted(abortedMessage(tx));
            }
        };
    }

    /** Runs the abort flow; if rollback fails, tx stays FAILED and protected for a later abort. */
    private void abortAfterViolation(TxId tx) {
        try {
            bridge.abort(tx);
        } catch (ConcurrencyInternalException e) {
            // QP's own ABORT retries the rollback and reports the error.
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
