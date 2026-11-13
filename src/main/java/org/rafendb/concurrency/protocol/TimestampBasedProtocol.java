package org.rafendb.concurrency.protocol;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.timestamp.StrictTimestampProtocol;

/** Connects timestamp ordering to CCM and protects reads through transaction completion. */
public final class TimestampBasedProtocol implements Protocol {
    private StrictTimestampProtocol timestamps = new StrictTimestampProtocol();
    private final Map<ResourceId, Set<TxId>> readers = new HashMap<>();
    private final Map<TxId, Set<ResourceId>> readBy = new HashMap<>();
    private final Map<TxId, Set<ResourceId>> writtenBy = new HashMap<>();

    public TimestampBasedProtocol() {}

    /** {@inheritDoc} */
    @Override
    public ProtocolType type() {
        return ProtocolType.TIMESTAMP_BASED;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Decision requestAccess(
            TxId tx, long timestamp, AccessType type, ResourceId resource) {
        Objects.requireNonNull(type, "type");
        if (type == AccessType.WRITE) {
            var check = timestamps.checkWrite(tx, timestamp, resource);
            if (check != StrictTimestampProtocol.Decision.ALLOWED) {
                return Decision.valueOf(check.name());
            }
            if (readers.getOrDefault(resource, Set.of()).stream()
                    .anyMatch(reader -> !reader.equals(tx))) {
                return Decision.WAITING;
            }
        }

        var decision = timestamps.requestAccess(tx, timestamp, type, resource);
        if (decision == StrictTimestampProtocol.Decision.ALLOWED) {
            if (type == AccessType.READ) {
                readers.computeIfAbsent(resource, ignored -> new HashSet<>()).add(tx);
                readBy.computeIfAbsent(tx, ignored -> new HashSet<>()).add(resource);
            } else {
                writtenBy.computeIfAbsent(tx, ignored -> new HashSet<>()).add(resource);
            }
        }
        return Decision.valueOf(decision.name());
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean holdsWrite(TxId tx, ResourceId resource) {
        return writtenBy.getOrDefault(tx, Set.of()).contains(resource);
    }

    /** {@inheritDoc} */
    @Override
    public void cancelPending(TxId tx) {
        Objects.requireNonNull(tx, "tx");
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void release(TxId tx, TxStatus outcome) {
        timestamps.complete(tx, outcome);
        Set<ResourceId> tables = readBy.remove(tx);
        if (tables != null) {
            for (ResourceId table : tables) {
                Set<TxId> tableReaders = readers.get(table);
                tableReaders.remove(tx);
                if (tableReaders.isEmpty()) {
                    readers.remove(table);
                }
            }
        }
        writtenBy.remove(tx);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TxId> selectDeadlockVictim() {
        return Optional.empty();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void reset() {
        timestamps = new StrictTimestampProtocol();
        readers.clear();
        readBy.clear();
        writtenBy.clear();
    }
}
