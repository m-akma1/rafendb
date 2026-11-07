package org.rafendb.concurrency.bridge;

import java.util.List;
import java.util.Optional;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.protocol.Protocol;
import org.rafendb.concurrency.txn.TransactionManager;

/** Test protocol that logs protection calls, with the status at call time, to a shared list. */
final class FakeProtocol implements Protocol {
    private final List<String> calls;
    private final TransactionManager txns;
    boolean failReset;

    FakeProtocol(List<String> calls, TransactionManager txns) {
        this.calls = calls;
        this.txns = txns;
    }

    @Override
    public ProtocolType type() {
        return ProtocolType.LOCK_BASED;
    }

    @Override
    public Decision requestAccess(TxId tx, long timestamp, AccessType type, ResourceId resource) {
        return Decision.ALLOWED;
    }

    @Override
    public void cancelPending(TxId tx) {
        calls.add("cancelPending " + tx + " [" + txns.status(tx) + "]");
    }

    @Override
    public void release(TxId tx, TxStatus outcome) {
        calls.add("release " + tx + " " + outcome + " [" + txns.status(tx) + "]");
    }

    @Override
    public Optional<TxId> selectDeadlockVictim() {
        return Optional.empty();
    }

    @Override
    public void reset() {
        if (failReset) {
            throw new IllegalStateException("protocol reset failed");
        }
        calls.add("reset");
    }
}
