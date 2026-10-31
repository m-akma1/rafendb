package org.rafendb.recovery.pitr;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.log.WALRecord;

/**
 * In-memory snapshot-based PITR mock. It uses the production WAL record model and applies only
 * after-images belonging to committed transactions at or before the requested boundary.
 */
public final class PitrManager {
    private final Logger logger;
    private Map<String, Map<Integer, String>> snapshotRows = Map.of();
    private Map<String, Map<Integer, String>> currentRows = Map.of();
    private long snapshotTimestamp;
    private int snapshotLsn;
    private boolean snapshotAvailable;
    private int transactionsReplayed;
    private int changesReplayed;
    private int lastReplayLsn;
    private int recoveredHighestTransactionId;
    private long recoveredHighestTimestamp;

    public PitrManager(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        captureSnapshot(0, Map.of());
    }

    /**
     * Replaces the mock's stable starting snapshot. The supplied state is copied; later caller
     * mutations do not alter the snapshot. Call only when the state represents the current WAL
     * boundary.
     */
    public synchronized void captureSnapshot(
            long timestamp, Map<String, ? extends Map<Integer, String>> rows) {
        if (timestamp < 0) throw new IllegalArgumentException("timestamp must not be negative");
        Objects.requireNonNull(rows, "rows");
        snapshotRows = copyRows(rows);
        currentRows = copyRows(rows);
        snapshotTimestamp = timestamp;
        List<WALRecord> wal = logger.getLogsSorted();
        snapshotLsn = wal.isEmpty() ? 0 : wal.get(wal.size() - 1).getLSN();
        snapshotAvailable = true;
        clearResult();
    }

    /** Restores the state after every transaction whose COMMIT timestamp is at most the target. */
    public synchronized boolean recoverToTimestamp(long targetTimestamp) {
        if (!snapshotAvailable || targetTimestamp < snapshotTimestamp) return false;
        List<WALRecord> wal = durableWal();
        long latestCommitTimestamp = latestCommitTimestamp(wal);
        if (targetTimestamp > latestCommitTimestamp && targetTimestamp != snapshotTimestamp) {
            return false;
        }
        int boundaryLsn = 0;
        for (WALRecord record : wal) {
            if (record.getLogType() == LogType.COMMIT && record.getTimestamp() <= targetTimestamp) {
                boundaryLsn = Math.max(boundaryLsn, record.getLSN());
            }
        }
        return restore(wal, targetTimestamp, boundaryLsn, null);
    }

    /**
     * Restores through the target transaction's COMMIT LSN. Earlier LSNs define the boundary;
     * transaction ID ordering is deliberately irrelevant.
     */
    public synchronized boolean recoverToTransaction(int transactionId) {
        if (!snapshotAvailable || transactionId <= 0) return false;
        List<WALRecord> wal = durableWal();
        WALRecord targetCommit = null;
        for (WALRecord record : wal) {
            if (record.getIdTransaction() == transactionId
                    && record.getLogType() == LogType.COMMIT) {
                targetCommit = record;
                break;
            }
        }
        if (targetCommit == null || targetCommit.getTimestamp() < snapshotTimestamp) return false;
        return restore(wal, targetCommit.getTimestamp(), targetCommit.getLSN(), transactionId);
    }

    /** Restores through the latest durable COMMIT, or to the snapshot if there are no commits. */
    public synchronized boolean recoverToLatestCommit() {
        if (!snapshotAvailable) return false;
        List<WALRecord> wal = durableWal();
        WALRecord latest = null;
        for (WALRecord record : wal) {
            if (record.getLogType() == LogType.COMMIT
                    && record.getTimestamp() >= snapshotTimestamp
                    && (latest == null || record.getLSN() > latest.getLSN())) {
                latest = record;
            }
        }
        if (latest == null) return restore(wal, snapshotTimestamp, 0, null);
        return restore(wal, latest.getTimestamp(), latest.getLSN(), null);
    }

    private boolean restore(
            List<WALRecord> wal, long timestampBoundary, int lsnBoundary, Integer targetTx) {
        Set<Integer> begun = new HashSet<>();
        Set<Integer> aborted = new HashSet<>();
        Map<Integer, WALRecord> commits = new HashMap<>();
        int previousLsn = 0;
        int highTransactionId = 0;
        long highTimestamp = 0;
        for (WALRecord record : wal) {
            if (record.getLSN() <= previousLsn) return false;
            previousLsn = record.getLSN();
            highTransactionId = Math.max(highTransactionId, record.getIdTransaction());
            highTimestamp = Math.max(highTimestamp, record.getTimestamp());
            switch (record.getLogType()) {
                case BEGIN -> begun.add(record.getIdTransaction());
                case COMMIT -> commits.putIfAbsent(record.getIdTransaction(), record);
                case ABORT -> aborted.add(record.getIdTransaction());
                default -> {}
            }
        }
        Set<Integer> eligible = new HashSet<>();
        for (var item : commits.entrySet()) {
            WALRecord commit = item.getValue();
            boolean inBoundary =
                    targetTx == null
                            ? commit.getTimestamp() <= timestampBoundary
                                    && commit.getLSN() <= lsnBoundary
                            : commit.getLSN() <= lsnBoundary;
            if (inBoundary && begun.contains(item.getKey()) && !aborted.contains(item.getKey())) {
                eligible.add(item.getKey());
            }
        }

        // Build off to the side so a malformed record or invalid target never partially mutates
        // state.
        Map<String, Map<Integer, String>> restored = copyRows(snapshotRows);
        Set<Integer> replayedTransactions = new HashSet<>();
        int replayed = 0;
        int lastLsn = 0;
        for (WALRecord record : wal) {
            if (!record.getLogType().isDataChange()
                    || record.getLSN() <= snapshotLsn
                    || record.getLSN() > lsnBoundary
                    || !eligible.contains(record.getIdTransaction())) continue;
            WALRecord commit = commits.get(record.getIdTransaction());
            if (commit == null || record.getLSN() >= commit.getLSN()) continue;
            if (record.getTableName() == null || record.getIdRecord() < 0) return false;
            if (record.getLogType() != LogType.DELETE && record.getAfterValue() == null)
                return false;
            Map<Integer, String> table =
                    restored.computeIfAbsent(record.getTableName(), k -> new HashMap<>());
            boolean rowExists = table.containsKey(record.getIdRecord());
            switch (record.getLogType()) {
                case INSERT -> {
                    if (rowExists) return false;
                    table.put(record.getIdRecord(), record.getAfterValue());
                }
                case UPDATE -> {
                    if (!rowExists) return false;
                    table.put(record.getIdRecord(), record.getAfterValue());
                }
                case DELETE -> {
                    if (!rowExists) return false;
                    table.remove(record.getIdRecord());
                }
                default -> throw new IllegalStateException("Not a data change");
            }
            replayed++;
            replayedTransactions.add(record.getIdTransaction());
            lastLsn = record.getLSN();
        }
        currentRows = restored;
        transactionsReplayed = replayedTransactions.size();
        changesReplayed = replayed;
        lastReplayLsn = lastLsn;
        recoveredHighestTransactionId = highTransactionId;
        recoveredHighestTimestamp = highTimestamp;
        return true;
    }

    private List<WALRecord> durableWal() {
        logger.flushAll();
        return logger.getLogsSorted();
    }

    private static long latestCommitTimestamp(List<WALRecord> wal) {
        long latest = 0;
        for (WALRecord record : wal) {
            if (record.getLogType() == LogType.COMMIT) {
                latest = Math.max(latest, record.getTimestamp());
            }
        }
        return latest;
    }

    private static Map<String, Map<Integer, String>> copyRows(
            Map<String, ? extends Map<Integer, String>> rows) {
        Map<String, Map<Integer, String>> copy = new HashMap<>();
        rows.forEach((table, records) -> copy.put(table, new HashMap<>(records)));
        return copy;
    }

    private void clearResult() {
        transactionsReplayed = 0;
        changesReplayed = 0;
        lastReplayLsn = 0;
        recoveredHighestTransactionId = 0;
        recoveredHighestTimestamp = 0;
    }

    /** Returns a defensive copy of the current mock database contents. */
    public synchronized Map<String, Map<Integer, String>> getCurrentRows() {
        return copyRows(currentRows);
    }

    public synchronized int getTransactionsReplayed() {
        return transactionsReplayed;
    }

    public synchronized int getChangesReplayed() {
        return changesReplayed;
    }

    public synchronized int getLastReplayLsn() {
        return lastReplayLsn;
    }

    /** High-water marks include history after the selected target to prevent identifier reuse. */
    public synchronized int getRecoveredHighestTransactionId() {
        return recoveredHighestTransactionId;
    }

    public synchronized long getRecoveredHighestTimestamp() {
        return recoveredHighestTimestamp;
    }

    public synchronized long getSnapshotTimestamp() {
        return snapshotTimestamp;
    }

    public synchronized int getSnapshotLsn() {
        return snapshotLsn;
    }

    public synchronized List<WALRecord> getWalRecords() {
        return logger.getLogsSorted();
    }
}
