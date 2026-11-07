package org.rafendb.concurrency.timestamp;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;

/**
 * Table-level strict timestamp ordering (CC-08), without Thomas' Write Rule.
 *
 * <p>Every table keeps three facts: R-TS (the largest timestamp that read it), W-TS (the largest
 * timestamp that wrote it) and the uncommitted writer, if any. A request is decided as follows:
 *
 * <ul>
 *   <li><b>Own write.</b> A transaction that is already the uncommitted writer of the table is
 *       always {@link Decision#ALLOWED}; this makes repeated requests idempotent.
 *   <li><b>READ.</b> {@link Decision#ABORT_REQUIRED} if its timestamp is lower than W-TS. Otherwise
 *       {@link Decision#WAITING} while another transaction is the uncommitted writer (no dirty
 *       read). Otherwise {@link Decision#ALLOWED} and R-TS becomes the larger of R-TS and the
 *       timestamp.
 *   <li><b>WRITE.</b> {@link Decision#ABORT_REQUIRED} if its timestamp is lower than R-TS or W-TS.
 *       Otherwise {@link Decision#WAITING} while another transaction is the uncommitted writer (no
 *       dirty write). Otherwise {@link Decision#ALLOWED}, W-TS becomes the timestamp and the
 *       transaction becomes the uncommitted writer.
 * </ul>
 *
 * <p>The timestamp check always comes before the wait check, so a request that can never succeed
 * is rejected immediately instead of waiting. A waiting transaction always has a larger timestamp
 * than the writer it waits for, so waits only go from younger to older and can never form a cycle:
 * this protocol is deadlock-free. Because requests never block, a WAITING answer is final for that
 * call; the caller retries the same request later and the decision is computed again from scratch.
 * Metadata changes only when a request is allowed.
 *
 * <p><b>Thread-safety.</b> Each table has its own monitor that guards its R-TS, W-TS and writer, so
 * transactions on different tables never contend. No method holds two monitors at once, hence the
 * monitors cannot deadlock. The coordinator is expected to serialize the requests of one
 * transaction with its {@link #complete} call, as the {@link TimestampProtocol} contract states.
 *
 * <p>R-TS and W-TS history is deliberately kept after a transaction finishes, including after an
 * abort. Dropping it could let a late transaction read or overwrite data that a younger transaction
 * already used. The price is that an aborted writer may still cause an older transaction to be
 * rejected; that is safe, and the rejected transaction restarts with a new, larger timestamp.
 */
public final class StrictTimestampProtocol implements TimestampProtocol {

    /** Timestamp state of one table; every field is guarded by the entry's own monitor. */
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

    /**
     * {@inheritDoc}
     *
     * <p>Thread-safe. Repeating a request is safe: a granted request stays granted, and a WAITING
     * request is evaluated again without changing any state.
     */
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
