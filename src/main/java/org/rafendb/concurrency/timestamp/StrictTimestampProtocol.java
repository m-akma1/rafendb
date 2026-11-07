package org.rafendb.concurrency.timestamp;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;

/** Table-level strict timestamp ordering (CC-08), without Thomas' Write Rule. */
public final class StrictTimestampProtocol implements TimestampProtocol {

    private static final class TableEntry {
        /** Largest timestamp that read the table, or 0 if it was never read. */
        private long readTs;

        /** Largest timestamp that wrote the table, or 0 if it was never written. */
        private long writeTs;

        /** The transaction holding uncommitted changes on the table, or {@code null}. */
        private TxId writer;
    }

    /** Timestamp state per normalized table-level resource. */
    private final ConcurrentHashMap<ResourceId, TableEntry> tables = new ConcurrentHashMap<>();

    /** Tables currently owned as uncommitted writer, per transaction, for release in complete. */
    private final ConcurrentHashMap<TxId, Set<ResourceId>> writtenBy = new ConcurrentHashMap<>();

    /** Creates a protocol with no history. */
    public StrictTimestampProtocol() {}

    /** Checks a write without changing timestamp or ownership state. */
    public Decision checkWrite(TxId tx, long timestamp, ResourceId resource) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(resource, "resource");
        if (timestamp <= 0 || !resource.isTable()) {
            throw new IllegalArgumentException(
                    "timestamp must be positive and resource table-level");
        }
        TableEntry entry = tables.get(resource);
        if (entry == null) {
            return Decision.ALLOWED;
        }
        synchronized (entry) {
            if (tx.equals(entry.writer)) {
                return Decision.ALLOWED;
            }
            if (timestamp < entry.readTs || timestamp < entry.writeTs) {
                return Decision.ABORT_REQUIRED;
            }
            return entry.writer == null ? Decision.ALLOWED : Decision.WAITING;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Decision requestAccess(TxId tx, long timestamp, AccessType type, ResourceId resource) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(resource, "resource");
        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp must be positive: " + timestamp);
        }
        if (!resource.isTable()) {
            throw new IllegalArgumentException("resource must be table-level: " + resource);
        }

        TableEntry entry = tables.computeIfAbsent(resource, key -> new TableEntry());
        synchronized (entry) {
            if (tx.equals(entry.writer)) {
                return Decision.ALLOWED;
            }
            return type == AccessType.READ
                    ? decideRead(entry, timestamp)
                    : decideWrite(entry, tx, timestamp, resource);
        }
    }

    /**
     * Decides a READ by a transaction that is not the table's uncommitted writer.
     *
     * @param entry the table state; the caller holds its monitor
     * @param timestamp the reader's timestamp
     * @return ABORT_REQUIRED, WAITING or ALLOWED; R-TS is raised only when ALLOWED
     */
    private static Decision decideRead(TableEntry entry, long timestamp) {
        if (timestamp < entry.writeTs) {
            return Decision.ABORT_REQUIRED;
        }
        if (entry.writer != null) {
            return Decision.WAITING;
        }
        entry.readTs = Math.max(entry.readTs, timestamp);
        return Decision.ALLOWED;
    }

    /**
     * Decides a WRITE by a transaction that is not yet the table's uncommitted writer.
     *
     * @param entry the table state; the caller holds its monitor
     * @param tx the writer
     * @param timestamp the writer's timestamp
     * @param resource the table, recorded so that {@link #complete} can release it
     * @return ABORT_REQUIRED, WAITING or ALLOWED; W-TS and the writer change only when ALLOWED
     */
    private Decision decideWrite(TableEntry entry, TxId tx, long timestamp, ResourceId resource) {
        if (timestamp < entry.readTs || timestamp < entry.writeTs) {
            return Decision.ABORT_REQUIRED;
        }
        if (entry.writer != null) {
            return Decision.WAITING;
        }
        entry.writeTs = timestamp;
        entry.writer = tx;
        writtenBy.computeIfAbsent(tx, key -> ConcurrentHashMap.newKeySet()).add(resource);
        return Decision.ALLOWED;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Thread-safe and idempotent: completing an unknown or already completed transaction does
     * nothing. R-TS and W-TS are not touched.
     */
    @Override
    public void complete(TxId tx, TxStatus status) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(status, "status");
        if (!status.isFinal()) {
            throw new IllegalArgumentException("status must be COMMITTED or ABORTED: " + status);
        }

        Set<ResourceId> owned = writtenBy.remove(tx);
        if (owned == null) {
            return;
        }
        for (ResourceId resource : owned) {
            TableEntry entry = tables.get(resource);
            if (entry == null) {
                continue;
            }
            synchronized (entry) {
                if (tx.equals(entry.writer)) {
                    entry.writer = null;
                }
            }
        }
    }
}
