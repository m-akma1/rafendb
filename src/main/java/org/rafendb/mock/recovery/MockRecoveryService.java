package org.rafendb.mock.recovery;

import java.util.concurrent.atomic.AtomicLong;
import org.rafendb.api.recovery.RecoveryService;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.TxId;

/**
 * Thread-safe FRM dummy with per-instance synthetic LSNs. No validation, persistence, undo,
 * or transaction history; use only as a substitute for the RecoveryService contract.
 */
public final class MockRecoveryService implements RecoveryService {
    private final AtomicLong lastLsn = new AtomicLong();

    /** {@inheritDoc} Does nothing. */
    @Override
    public void logBegin(TxId tx, long timestamp) {}

    /** {@inheritDoc} Returns a synthetic LSN; nothing is persisted. */
    @Override
    public long logChange(TxId tx, ChangeRecord change) {
        return lastLsn.incrementAndGet();
    }

    /** {@inheritDoc} Does nothing. */
    @Override
    public void commit(TxId tx) {}

    /** {@inheritDoc} Does nothing; no data is restored. */
    @Override
    public void rollback(TxId tx) {}

    /** {@inheritDoc} Always returns empty-history high-water marks. */
    @Override
    public RecoveryInfo recover() {
        return new RecoveryInfo(0, 0);
    }
}
