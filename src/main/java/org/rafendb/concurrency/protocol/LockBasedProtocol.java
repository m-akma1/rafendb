package org.rafendb.concurrency.protocol;

import java.util.Objects;
import java.util.Optional;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.lock.TableLockManager;
import org.rafendb.concurrency.lock.WaitForDeadlockDetector;

public final class LockBasedProtocol implements Protocol {
    private final WaitForDeadlockDetector detector = new WaitForDeadlockDetector();
    private volatile TableLockManager locks = new TableLockManager();

    public LockBasedProtocol() {}

    /** {@inheritDoc} */
    @Override
    public ProtocolType type() {
        return ProtocolType.LOCK_BASED;
    }

    /** {@inheritDoc} */
    @Override
    public Decision requestAccess(TxId tx, long timestamp, AccessType type, ResourceId resource) {
        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp must be positive: " + timestamp);
        }
        return locks.tryAcquire(tx, type, resource) ? Decision.ALLOWED : Decision.WAITING;
    }

    /** {@inheritDoc} */
    @Override
    public boolean holdsWrite(TxId tx, ResourceId resource) {
        return locks.holdsWrite(tx, resource);
    }

    /** {@inheritDoc} */
    @Override
    public void cancelPending(TxId tx) {
        locks.cancelPending(tx);
    }

    /** {@inheritDoc} */
    @Override
    public void release(TxId tx, TxStatus outcome) {
        if (!Objects.requireNonNull(outcome, "outcome").isFinal()) {
            throw new IllegalArgumentException("outcome must be COMMITTED or ABORTED: " + outcome);
        }
        locks.releaseAll(tx);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TxId> selectDeadlockVictim() {
        return detector.selectVictim(locks.waitForGraph());
    }

    /** {@inheritDoc} */
    @Override
    public void reset() {
        locks = new TableLockManager();
    }
}
