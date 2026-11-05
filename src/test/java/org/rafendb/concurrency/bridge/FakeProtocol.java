package org.rafendb.concurrency.bridge;

import java.util.List;
import java.util.Optional;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.protocol.Protocol;

/** Protocol for bridge tests; records protection calls into a shared call list. */
final class FakeProtocol implements Protocol {
    private final List<String> calls;
    boolean failReset;

    FakeProtocol(List<String> calls) {
        this.calls = calls;
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
        calls.add("cancelPending " + tx);
    }

    @Override
    public void release(TxId tx, TxStatus outcome) {
        calls.add("release " + tx + " " + outcome);
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
