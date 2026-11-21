package org.rafendb.recovery.pitr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ConcurrencyMaintenance;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.api.storage.StorageRecovery;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.recovery.FRManager;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.log.LoggerErrorException;
import org.rafendb.recovery.log.WALRecord;
import org.rafendb.recovery.rollback.RollbackManager;

/**
 * In-memory snapshot-based PITR mock. It uses the production WAL record model and applies only
 * after-images belonging to committed transactions at or before the requested boundary.
 */
public final class PitrManager {
    private final Logger logger;
    private final StorageRecovery storageManager;
    private final ConcurrencyControl concurrencyControl;
    private final PitrAdmission pitrAdmission;
    private final RollbackManager rollbackManager;
    private final StorageManager realStorage;
    private final Path snapshotDirectory;
    private Map<String, Map<Rid, String>> snapshotRows = Map.of();
    private Map<String, Map<Rid, String>> currentRows = Map.of();
    private long snapshotTimestamp;
    private long snapshotLsn;
    private boolean snapshotAvailable;
    private int transactionsReplayed;
    private int changesReplayed;
    private long lastReplayLsn;
    private int recoveredHighestTransactionId;
    private long recoveredHighestTimestamp;
    private long pitrBoundaryLsn;
    private long pitrBoundaryTimestamp;
    private Throwable lastPitrFailure;

    public PitrManager(Logger logger) throws LoggerErrorException {
        this(logger, null, null, null);
    }

    /** Creates a PITR manager connected to real recovery storage. */
    public PitrManager(Logger logger, StorageRecovery storageManager) throws LoggerErrorException {
        this(logger, storageManager, null, null);
    }

    /**
     * Creates a PITR manager connected to real storage and concurrency control.
     *
     * @param logger persistent WAL
     * @param storageManager storage recovery facade
     * @param concurrencyControl optional CCM for maintenance quiescing and reset
     */
    public PitrManager(
            Logger logger, StorageRecovery storageManager, ConcurrencyControl concurrencyControl)
            throws LoggerErrorException {
        this(logger, storageManager, concurrencyControl, null);
    }

    /** Creates a PITR manager with server admission coordination. */
    public PitrManager(
            Logger logger,
            StorageRecovery storageManager,
            ConcurrencyControl concurrencyControl,
            PitrAdmission pitrAdmission)
            throws LoggerErrorException {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.storageManager = storageManager;
        this.concurrencyControl = concurrencyControl;
        this.pitrAdmission = pitrAdmission;
        this.realStorage = storageManager instanceof StorageManager manager ? manager : null;
        String logPath = logger.getLogFilePath();
        this.snapshotDirectory =
                logPath == null || logPath.isEmpty()
                        ? Path.of(System.getProperty("java.io.tmpdir"), "rafendb-pitr-snapshot")
                        : Path.of(logPath + ".pitr-snapshot");
        this.rollbackManager =
                storageManager != null
                        ? new RollbackManager(
                                logger,
                                FRManager.storageAdapter(storageManager),
                                System::currentTimeMillis)
                        : null;
        loadPitrBoundary();
        if (realStorage != null) {
            captureLiveSnapshot(System.currentTimeMillis());
        } else {
            captureSnapshot(0, Map.of());
        }
    }

    /**
     * Replaces the mock's stable starting snapshot. The supplied state is copied; later caller
     * mutations do not alter the snapshot. Call only when the state represents the current WAL
     * boundary.
     */
    public synchronized void captureSnapshot(
            long timestamp, Map<String, ? extends Map<Rid, String>> rows)
            throws LoggerErrorException {
        if (timestamp < 0) throw new IllegalArgumentException("timestamp must not be negative");
        Objects.requireNonNull(rows, "rows");
        logger.flushAll();
        validateWalOrder(logger.getLogsSorted());
        snapshotRows = copyRows(rows);
        currentRows = copyRows(rows);
        snapshotTimestamp = timestamp;
        List<WALRecord> wal = logger.getLogsSorted();
        snapshotLsn = wal.isEmpty() ? 0 : wal.get(wal.size() - 1).getLSN();
        snapshotAvailable = true;
        clearResult();
    }

    /** Captures a consistent logical snapshot of live tables, catalog schemas and indexes. */
    public synchronized void captureLiveSnapshot(long timestamp) throws LoggerErrorException {
        if (realStorage == null) {
            throw new IllegalStateException("PITR requires a StorageManager");
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        try {
            realStorage.flush();
            logger.flushAll();
            realStorage.captureSnapshot(snapshotDirectory);
            snapshotTimestamp = timestamp;
            List<WALRecord> wal = logger.getLogsSorted();
            snapshotLsn = wal.isEmpty() ? 0 : wal.getLast().getLSN();
            snapshotAvailable = true;
            clearResult();
        } catch (org.rafendb.common.error.StorageInternalException | RuntimeException e) {
            throw new LoggerErrorException("Failed to capture PITR snapshot", e);
        }
    }

    /** Restores the state after every transaction whose COMMIT timestamp is at most the target. */
    public synchronized boolean recoverToTimestamp(long targetTimestamp)
            throws LoggerErrorException {
        if (!snapshotAvailable || targetTimestamp < snapshotTimestamp) return false;
        if (rollbackManager != null) {
            return recoverRealToTimestamp(targetTimestamp);
        }
        List<WALRecord> wal = durableWal();
        long latestCommitTimestamp = latestCommitTimestamp(wal);
        if (targetTimestamp > latestCommitTimestamp && targetTimestamp != snapshotTimestamp) {
            return false;
        }
        long boundaryLsn = 0;
        for (WALRecord record : wal) {
            if (record.getLogType() == LogType.COMMIT && record.getTimestamp() <= targetTimestamp) {
                boundaryLsn = Math.max(boundaryLsn, record.getLSN());
            }
        }
        return runRecovery(wal, targetTimestamp, boundaryLsn, null);
    }

    /**
     * Restores through the target transaction's COMMIT LSN. Earlier LSNs define the boundary;
     * transaction ID ordering is deliberately irrelevant.
     */
    public synchronized boolean recoverToTransaction(int transactionId)
            throws LoggerErrorException {
        if (!snapshotAvailable || transactionId <= 0) return false;
        if (rollbackManager != null) {
            return recoverRealToTransaction(transactionId);
        }
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
        return runRecovery(wal, targetCommit.getTimestamp(), targetCommit.getLSN(), transactionId);
    }

    /** Restores through the latest durable COMMIT, or to the snapshot if there are no commits. */
    public synchronized boolean recoverToLatestCommit() throws LoggerErrorException {
        if (!snapshotAvailable) return false;
        if (rollbackManager != null) {
            List<WALRecord> wal = durableWal();
            WALRecord latest = null;
            for (WALRecord record : wal) {
                if (record.getLogType() == LogType.COMMIT
                        && (latest == null || record.getLsn() > latest.getLsn())) {
                    latest = record;
                }
            }
            if (latest == null) return false;
            return recoverRealToTransaction(latest.getIdTransaction());
        }
        List<WALRecord> wal = durableWal();
        WALRecord latest = null;
        for (WALRecord record : wal) {
            if (record.getLogType() == LogType.COMMIT
                    && record.getTimestamp() >= snapshotTimestamp
                    && (latest == null || record.getLSN() > latest.getLSN())) {
                latest = record;
            }
        }
        if (latest == null) return runRecovery(wal, snapshotTimestamp, 0, null);
        return runRecovery(wal, latest.getTimestamp(), latest.getLSN(), null);
    }

    private boolean recoverRealToTimestamp(long targetTimestamp) throws LoggerErrorException {
        boolean paused = false;
        boolean admissionPaused = false;
        try {
            if (targetTimestamp < snapshotTimestamp) {
                return false;
            }
            if (pitrAdmission != null) {
                pitrAdmission.pauseAdmission();
                admissionPaused = true;
                if (!pitrAdmission.awaitNoActiveSessions(Duration.ofMinutes(1))) {
                    return false;
                }
            }
            if (concurrencyControl != null) {
                concurrencyControl.pauseNewTransactions();
                paused = true;
                if (!concurrencyControl.awaitNoActive(Duration.ofMinutes(1))) {
                    return false;
                }
            }
            long boundaryLsn = latestCommitLsn(targetTimestamp);
            logger.logTransactionEvent(0, LogType.PITR_BEGIN);
            restoreLiveSnapshotAndReplay(boundaryLsn, null);
            resetConcurrencyStateFromWal();
            lastReplayLsn = latestCommitLsn(targetTimestamp);
            snapshotTimestamp = targetTimestamp;
            pitrBoundaryLsn = lastReplayLsn;
            pitrBoundaryTimestamp = targetTimestamp;
            logger.logTransactionEvent(0, LogType.PITR_END);
            pitrBoundaryLsn = logger.getCurrentLSN();
            writePitrBoundary(pitrBoundaryLsn, pitrBoundaryTimestamp);
            return true;
        } catch (InterruptedException | RuntimeException | LoggerErrorException e) {
            lastPitrFailure = e;
            return false;
        } finally {
            if (paused) {
                concurrencyControl.resumeNewTransactions();
            }
            if (admissionPaused) {
                pitrAdmission.resumeAdmission();
            }
        }
    }

    private void restoreLiveSnapshotAndReplay(long boundaryLsn, Integer targetTransaction)
            throws LoggerErrorException {
        if (realStorage == null) {
            throw new IllegalStateException("A real StorageManager is required");
        }

        try {
            realStorage.restoreSnapshot(snapshotDirectory);
            realStorage.reloadAfterRestore();

            List<WALRecord> wal = logger.getLogsSorted();
            Set<Integer> committed = new HashSet<>();
            for (WALRecord record : wal) {
                if (record.getLogType() == LogType.COMMIT
                        && record.getLSN() <= boundaryLsn
                        && (targetTransaction == null || record.getLSN() <= boundaryLsn)) {
                    committed.add(record.getIdTransaction());
                }
            }

            for (WALRecord record : wal) {
                if (!record.getLogType().isDataChange()
                        || record.getLSN() <= snapshotLsn
                        || record.getLSN() > boundaryLsn
                        || !committed.contains(record.getIdTransaction())) {
                    continue;
                }
                WALRecord commit =
                        wal.stream()
                                .filter(
                                        r ->
                                                r.getIdTransaction() == record.getIdTransaction()
                                                        && r.getLogType() == LogType.COMMIT
                                                        && r.getLSN() <= boundaryLsn)
                                .findFirst()
                                .orElse(null);
                if (commit == null || record.getLSN() >= commit.getLSN()) {
                    continue;
                }
                if (record.getTableName() == null || record.getRid() == null) {
                    throw new IllegalStateException("Malformed PITR data-change record");
                }

                Optional<Row> image = Optional.empty();
                if (record.getAfterValue() != null) {
                    byte[] raw = decodeB64Image(record.getAfterValue());
                    image = Optional.of(realStorage.decodeRow(record.getTableName(), raw));
                }
                realStorage.applyRecovery(
                        record.getTableName(), record.getRid(), image, record.getLSN());
            }

            realStorage.flush();
            realStorage.reloadAfterRestore();
        } catch (RuntimeException | org.rafendb.common.error.StorageInternalException e) {
            throw new LoggerErrorException("Failed to restore PITR snapshot", e);
        }
    }

    private static byte[] decodeB64Image(String image) {
        String prefix = "B64:";
        if (image.startsWith(prefix)) {
            return Base64.getDecoder().decode(image.substring(prefix.length()));
        }
        return image.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean recoverRealToTransaction(int transactionId) throws LoggerErrorException {
        boolean paused = false;
        boolean admissionPaused = false;
        try {
            WALRecord commit = null;
            for (WALRecord record : durableWal()) {
                if (record.getIdTransaction() == transactionId
                        && record.getLogType() == LogType.COMMIT) {
                    commit = record;
                    break;
                }
            }
            if (commit == null) {
                return false;
            }
            if (commit.getTimestamp() < snapshotTimestamp) {
                return false;
            }

            if (pitrAdmission != null) {
                pitrAdmission.pauseAdmission();
                admissionPaused = true;
                if (!pitrAdmission.awaitNoActiveSessions(Duration.ofMinutes(1))) {
                    return false;
                }
            }
            if (concurrencyControl != null) {
                concurrencyControl.pauseNewTransactions();
                paused = true;
                if (!concurrencyControl.awaitNoActive(Duration.ofMinutes(1))) {
                    return false;
                }
            }
            logger.logTransactionEvent(0, LogType.PITR_BEGIN);
            restoreLiveSnapshotAndReplay(commit.getLsn(), transactionId);
            resetConcurrencyStateFromWal();
            lastReplayLsn = commit.getLsn();
            snapshotTimestamp = commit.getTimestamp();
            pitrBoundaryLsn = lastReplayLsn;
            pitrBoundaryTimestamp = snapshotTimestamp;
            logger.logTransactionEvent(0, LogType.PITR_END);
            pitrBoundaryLsn = logger.getCurrentLSN();
            writePitrBoundary(pitrBoundaryLsn, pitrBoundaryTimestamp);
            return true;
        } catch (InterruptedException | RuntimeException | LoggerErrorException e) {
            lastPitrFailure = e;
            return false;
        } finally {
            if (paused) {
                concurrencyControl.resumeNewTransactions();
            }
            if (admissionPaused) {
                pitrAdmission.resumeAdmission();
            }
        }
    }

    private void resetConcurrencyStateFromWal() {
        if (concurrencyControl instanceof ConcurrencyMaintenance maintenance) {
            long maxId = 0;
            for (WALRecord record : logger.getLogsSorted()) {
                maxId = Math.max(maxId, record.getIdTransaction());
            }
            maintenance.initializeAfterRecovery(new RecoveryInfo(maxId, maxId));
        }
    }

    private long latestCommitLsn(long targetTimestamp) {
        long targetLsn = 0;
        for (WALRecord record : logger.getLogsSorted()) {
            if (record.getLogType() == LogType.COMMIT && record.getTimestamp() <= targetTimestamp) {
                targetLsn = Math.max(targetLsn, record.getLsn());
            }
        }
        return targetLsn;
    }

    private Path boundaryPath() {
        String logPath = logger.getLogFilePath();
        if (logPath == null || logPath.isEmpty()) {
            return Path.of(System.getProperty("java.io.tmpdir"), "rafendb-pitr.pointer");
        }
        return Path.of(logPath + ".pitr");
    }

    private void writePitrBoundary(long lsn, long timestamp) throws LoggerErrorException {
        if (lsn <= 0) {
            return;
        }
        Path pointer = boundaryPath();
        Path parent = pointer.toAbsolutePath().getParent();
        Path temporary =
                pointer.resolveSibling(
                        pointer.getFileName() + ".tmp-" + Thread.currentThread().threadId());
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    temporary,
                    lsn + ":" + timestamp + System.lineSeparator(),
                    StandardCharsets.US_ASCII);
            try (var channel =
                    java.nio.channels.FileChannel.open(
                            temporary, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        pointer,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, pointer, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new LoggerErrorException("Failed to persist PITR boundary", e);
        }
    }

    private void loadPitrBoundary() {
        pitrBoundaryLsn = 0;
        pitrBoundaryTimestamp = 0;
        try {
            Path pointer = boundaryPath();
            if (!Files.exists(pointer)) {
                return;
            }
            String[] parts =
                    Files.readString(pointer, StandardCharsets.US_ASCII).trim().split(":", -1);
            if (parts.length != 2) {
                return;
            }
            long lsn = Long.parseLong(parts[0]);
            long timestamp = Long.parseLong(parts[1]);
            if (lsn > 0 && timestamp >= 0) {
                pitrBoundaryLsn = lsn;
                pitrBoundaryTimestamp = timestamp;
            }
        } catch (IOException | RuntimeException ignored) {
            pitrBoundaryLsn = 0;
            pitrBoundaryTimestamp = 0;
        }
    }

    private boolean runRecovery(
            List<WALRecord> wal, long timestampBoundary, long lsnBoundary, Integer targetTx)
            throws LoggerErrorException {
        boolean restored = restore(wal, timestampBoundary, lsnBoundary, targetTx);
        if (restored && lsnBoundary > 0) {
            pitrBoundaryLsn = lsnBoundary;
            pitrBoundaryTimestamp = timestampBoundary;
            writePitrBoundary(lsnBoundary, timestampBoundary);
        }
        return restored;
    }

    private boolean restore(
            List<WALRecord> wal, long timestampBoundary, long lsnBoundary, Integer targetTx) {
        Set<Integer> begun = new HashSet<>();
        Set<Integer> finished = new HashSet<>();
        Set<Integer> aborted = new HashSet<>();
        Map<Integer, WALRecord> commits = new HashMap<>();
        long previousLsn = 0;
        int highTransactionId = 0;
        long highTimestamp = 0;
        for (WALRecord record : wal) {
            if (record.getLSN() <= previousLsn) return false;
            previousLsn = record.getLSN();
            highTransactionId = Math.max(highTransactionId, record.getIdTransaction());
            highTimestamp = Math.max(highTimestamp, record.getTimestamp());
            switch (record.getLogType()) {
                case BEGIN -> {
                    if (!begun.add(record.getIdTransaction())
                            || finished.contains(record.getIdTransaction())) return false;
                }
                case COMMIT -> {
                    if (!begun.contains(record.getIdTransaction())
                            || !finished.add(record.getIdTransaction())) return false;
                    commits.put(record.getIdTransaction(), record);
                }
                case ABORT -> {
                    if (!begun.contains(record.getIdTransaction())
                            || !finished.add(record.getIdTransaction())) return false;
                    aborted.add(record.getIdTransaction());
                }
                case INSERT, UPDATE, DELETE -> {
                    if (!begun.contains(record.getIdTransaction())
                            || finished.contains(record.getIdTransaction())) return false;
                }
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
        Map<String, Map<Rid, String>> restored = copyRows(snapshotRows);
        Set<Integer> replayedTransactions = new HashSet<>();
        int replayed = 0;
        long lastLsn = 0;
        for (WALRecord record : wal) {
            if (!record.getLogType().isDataChange()
                    || record.getLSN() <= snapshotLsn
                    || record.getLSN() > lsnBoundary
                    || !eligible.contains(record.getIdTransaction())) continue;
            WALRecord commit = commits.get(record.getIdTransaction());
            if (commit == null || record.getLSN() >= commit.getLSN()) continue;
            if (record.getTableName() == null || record.getRid() == null) return false;
            if (record.getLogType() != LogType.DELETE && record.getAfterValue() == null)
                return false;
            Map<Rid, String> table =
                    restored.computeIfAbsent(record.getTableName(), k -> new HashMap<>());
            boolean rowExists = table.containsKey(record.getRid());
            switch (record.getLogType()) {
                case INSERT -> {
                    if (rowExists) return false;
                    table.put(record.getRid(), record.getAfterValue());
                }
                case UPDATE -> {
                    if (!rowExists) return false;
                    table.put(record.getRid(), record.getAfterValue());
                }
                case DELETE -> {
                    if (!rowExists) return false;
                    table.remove(record.getRid());
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

    private List<WALRecord> durableWal() throws LoggerErrorException {
        logger.flushAll();
        List<WALRecord> wal = logger.getLogsSorted();
        validateWalOrder(wal);
        return wal;
    }

    private static void validateWalOrder(List<WALRecord> wal) {
        long previousLsn = 0;
        for (WALRecord record : wal) {
            if (record.getLsn() <= previousLsn) {
                throw new IllegalStateException("WAL LSNs are not strictly increasing");
            }
            previousLsn = record.getLsn();
        }
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

    private static Map<String, Map<Rid, String>> copyRows(
            Map<String, ? extends Map<Rid, String>> rows) {
        Map<String, Map<Rid, String>> copy = new HashMap<>();
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
    public synchronized Map<String, Map<Rid, String>> getCurrentRows() {
        return copyRows(currentRows);
    }

    public synchronized int getTransactionsReplayed() {
        return transactionsReplayed;
    }

    public synchronized int getChangesReplayed() {
        return changesReplayed;
    }

    public synchronized long getLastReplayLsn() {
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

    public synchronized long getSnapshotLsn() {
        return snapshotLsn;
    }

    public synchronized List<WALRecord> getWalRecords() {
        return logger.getLogsSorted();
    }

    /** Returns the durable PITR boundary LSN, or zero if no PITR has completed. */
    public synchronized long getPitrBoundaryLsn() {
        loadPitrBoundary();
        return pitrBoundaryLsn;
    }

    /** Returns the durable PITR boundary timestamp, or zero if no PITR has completed. */
    public synchronized long getPitrBoundaryTimestamp() {
        loadPitrBoundary();
        return pitrBoundaryTimestamp;
    }

    /** Returns the exception behind the latest failed real PITR operation, if one occurred. */
    public synchronized Throwable getLastPitrFailure() {
        return lastPitrFailure;
    }
}
