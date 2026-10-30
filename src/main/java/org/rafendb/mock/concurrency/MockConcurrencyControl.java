package org.rafendb.mock.concurrency;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;

/**
 * Dummy {@link ConcurrencyControl} for tests and demos: every access is allowed, every commit and
 * abort succeeds, and nothing is tracked. Only {@link #begin()} keeps state, so ids stay unique and
 * increasing.
 */
public final class MockConcurrencyControl implements ConcurrencyControl {
    private final AtomicLong lastId = new AtomicLong();

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