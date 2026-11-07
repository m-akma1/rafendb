package org.rafendb.concurrency;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.api.concurrency.ConcurrencyMaintenance;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.protocol.Protocol;
import org.rafendb.concurrency.txn.TransactionManager;

/**
 * The CCM facade (CC-01, CC-02): the one entry point other components use. It owns the transaction
 * table and forwards access decisions to the configured {@link Protocol}. The protocol is chosen
 * once at startup.
 */
public final class ConcurrencyControlManager
        implements ConcurrencyControl, ConcurrencyLogging, ConcurrencyMaintenance {
    private final TransactionManager txns;
    private final Protocol protocol;

    /**
     * Creates the facade.
     *
     * @param txns the transaction table; admission opens with {@link TransactionManager#open}
     * @param protocol the protocol selected at startup
     */
    public ConcurrencyControlManager(TransactionManager txns, Protocol protocol) {
        this.txns = Objects.requireNonNull(txns, "txns");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    @Override
    public TxId begin() {
        // [TODO] For CC3: forward BEGIN to FRM after the transaction starts.
        return txns.begin();
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
                txns.transition(tx, TxStatus.FAILED);
                // [TODO] For CC3: start the single abort flow (cancelPending, rollback, release).
                yield AccessResult.aborted(abortedMessage(tx));
            }
        };
    }

    private static String abortedMessage(TxId tx) {
        return tx + " has been aborted";
    }

    @Override
    public TxResult commit(TxId tx) throws ConcurrencyInternalException {
        // [TODO] For CC3: commit flow (wait writes, COMMIT record via FRM, then release).
        throw new UnsupportedOperationException("commit: CC3");
    }

    @Override
    public TxResult abort(TxId tx) throws ConcurrencyInternalException {
        // [TODO] For CC3: the single abort flow (FAILED, cancelPending, rollback, release).
        throw new UnsupportedOperationException("abort: CC3");
    }

    @Override
    public long logChange(TxId tx, ChangeRecord change) throws ConcurrencyInternalException {
        // [TODO] For CC3: forward the change to FRM and return its LSN.
        throw new UnsupportedOperationException("logChange: CC3");
    }

    @Override
    public void initializeAfterRecovery(RecoveryInfo recovery) {
        // [TODO] For CC3: validate, protocol.reset(), then txns.open(max of the two marks).
        throw new UnsupportedOperationException("initializeAfterRecovery: CC3");
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
