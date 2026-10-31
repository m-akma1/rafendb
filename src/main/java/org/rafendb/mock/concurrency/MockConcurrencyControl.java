package org.rafendb.mock.concurrency;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.api.concurrency.ConcurrencyMaintenance;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;

/**
 * Thread-safe CCM dummy: all accesses and completions succeed. IDs and synthetic LSNs increase
 * per instance; initialization is a no-op. No validation, isolation, WAL, or recovery is performed.
 */
public final class MockConcurrencyControl
        implements ConcurrencyControl, ConcurrencyLogging, ConcurrencyMaintenance {
    private final AtomicLong lastId = new AtomicLong();
    private final AtomicLong lastLsn = new AtomicLong();

    /** {@inheritDoc} */
    @Override
    public TxId begin() {
        return new TxId(lastId.incrementAndGet());
    }

    /** {@inheritDoc} Always {@code ALLOWED}. */
    @Override
    public AccessResult requestAccess(TxId tx, AccessType type, ResourceId resource) {
        return AccessResult.allowed();
    }

    /** {@inheritDoc} Returns a synthetic LSN; nothing is persisted. */
    @Override
    public long logChange(TxId tx, ChangeRecord change) {
        return lastLsn.incrementAndGet();
    }

    /** {@inheritDoc} Does nothing; no recovery state is tracked. */
    @Override
    public void initializeAfterRecovery(RecoveryInfo recovery) {}

    /** {@inheritDoc} Always {@code COMMITTED}. */
    @Override
    public TxResult commit(TxId tx) {
        return new TxResult(TxStatus.COMMITTED, "");
    }

    /** {@inheritDoc} Always {@code ABORTED}. */
    @Override
    public TxResult abort(TxId tx) {
        return new TxResult(TxStatus.ABORTED, "");
    }

    /** {@inheritDoc} Does nothing. */
    @Override
    public void pauseNewTransactions() {}

    /** {@inheritDoc} Always {@code true}. */
    @Override
    public boolean awaitNoActive(Duration timeout) {
        return true;
    }

    /** {@inheritDoc} Does nothing. */
    @Override
    public void resumeNewTransactions() {}

    /** {@inheritDoc} Always {@link ProtocolType#LOCK_BASED}. */
    @Override
    public ProtocolType activeProtocol() {
        return ProtocolType.LOCK_BASED;
    }
}
