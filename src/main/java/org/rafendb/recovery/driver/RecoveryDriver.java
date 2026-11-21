package org.rafendb.recovery.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.Component;
import org.rafendb.common.error.RecoveryInternalException;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.concurrency.ConcurrencyControlManager;
import org.rafendb.concurrency.protocol.TimestampBasedProtocol;
import org.rafendb.concurrency.txn.TransactionManager;
import org.rafendb.driver.Driver;
import org.rafendb.driver.DriverException;
import org.rafendb.engine.Engine;
import org.rafendb.mock.concurrency.MockConcurrencyControl;
import org.rafendb.recovery.FRManager;
import org.rafendb.recovery.checkpoint.CheckpointManager;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.log.LoggerErrorException;
import org.rafendb.recovery.log.WALRecord;
import org.rafendb.recovery.restart.CrashRecoveryEngine;
import org.rafendb.recovery.rollback.RollbackManager;
import org.rafendb.recovery.rollback.UndoStorage;

/**
 * Unified driver for the Failure Recovery Manager (FRM) component.
 *
 * <p>Extends {@link Driver} ({@link Component#FRM}) untuk menyediakan antarmuka CLI interaktif dan
 * eksekusi skenario ({@code --mock}, {@code --run}, {@code --help}, {@code --version}, serta
 * {@code --dir}, {@code --wal}, {@code --frames}), sekaligus mempertahankan suite pengujian
 * terintegrasi ({@link #runAllTests()}) dan simulasi crash proses anak ({@code
 * --simulate-crash-child}).
 */
public final class RecoveryDriver extends Driver {

    private record RowEntry(Rid rid, Row row) {}

    // State sesi Driver (dikelola oleh setup() dan close())
    private Path sessionDir;
    private boolean ownsSessionDir = false;
    private Path sessionWalFile;
    private int bufferFrames = 8;
    private StorageManager sessionRealStorage;
    private final MockStorage sessionMockStorage = new MockStorage();
    private FRManager sessionFrm;
    private ConcurrencyControl sessionCcm;

    // State terisolasi untuk rangkaian uji mandiri (runAllTests() & method test*())
    private FRManager frm;
    private Path tempLogDir;
    private Path currentLogFile;
    private final MockStorage storage = new MockStorage();

    /**
     * Mock in-memory storage manager untuk keperluan verifikasi pengujian recovery.
     */
    public static class MockStorage implements UndoStorage {
        private static final String NULL_VALUE_SENTINEL = "\0__NULL__";
        private final Map<String, String> data = new ConcurrentHashMap<>();

        static String encodeImage(String value) {
            return value == null ? NULL_VALUE_SENTINEL : value;
        }

        private static String decodeImage(String stored) {
            return NULL_VALUE_SENTINEL.equals(stored) ? null : stored;
        }

        @Override
        public void applyUndo(String tableName, int idRecord, String image, long lsn) {
            String key = tableName + "#" + idRecord;
            if (image == null) {
                data.remove(key);
            } else {
                data.put(key, image);
            }
        }

        public void put(String tableName, int idRecord, String value) {
            data.put(tableName + "#" + idRecord, encodeImage(value));
        }

        public String get(String tableName, int idRecord) {
            String stored = data.get(tableName + "#" + idRecord);
            return stored == null ? null : decodeImage(stored);
        }

        public String getRaw(String tableName, int idRecord) {
            return data.get(tableName + "#" + idRecord);
        }

        public boolean exists(String tableName, int idRecord) {
            return data.containsKey(tableName + "#" + idRecord);
        }

        public void clear() {
            data.clear();
        }

        public Map<String, String> snapshot() {
            Map<String, String> copy = new HashMap<>();
            data.forEach((k, v) -> copy.put(k, decodeImage(v)));
            return copy;
        }
    }

    /** Creates the Failure Recovery Manager driver. */
    public RecoveryDriver() {
        super(Component.FRM);
    }

    /**
     * Entry point utama untuk Failure Recovery Manager Driver.
     *
     * @param args argumen baris perintah
     * @throws Exception jika simulasi crash proses anak gagal
     */
    public static void main(String[] args) throws Exception {
        if (args != null && args.length == 3 && "--simulate-crash-child".equals(args[0])) {
            runCrashChildSimulation(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        new RecoveryDriver().launch(args);
    }

    @Override
    protected void help() {
        super.help();
        out().println("FRM options:");
        out().println("  --dir=<path>     data & WAL directory (default: temporary directory)");
        out().println("  --wal=<path>     WAL file path (default: <dir>/wal.log)");
        out().println("  --frames=<n>     buffer frames for real StorageManager (default: 8)");
    }

    @Override
    protected void setup() throws Exception {
        String dirOpt = option("dir");
        if (dirOpt != null) {
            sessionDir = Path.of(dirOpt);
            ownsSessionDir = false;
            Files.createDirectories(sessionDir);
        } else {
            sessionDir = Files.createTempDirectory("rafendb-frm-driver-");
            ownsSessionDir = true;
        }

        String walOpt = option("wal");
        if (walOpt != null) {
            sessionWalFile = Path.of(walOpt);
            Path parent = sessionWalFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } else {
            sessionWalFile = sessionDir.resolve("wal.log");
        }

        String framesOpt = option("frames", "8");
        try {
            bufferFrames = Integer.parseInt(framesOpt);
        } catch (NumberFormatException e) {
            throw new DriverException("--frames must be a positive integer: " + framesOpt);
        }
        if (bufferFrames <= 0) {
            throw new DriverException("--frames must be a positive integer: " + framesOpt);
        }

        sessionMockStorage.clear();
        openSession();
        registerCommands();
    }

    @Override
    protected void close() throws Exception {
        Exception firstError = null;
        if (sessionRealStorage != null) {
            try {
                sessionRealStorage.close();
            } catch (Exception e) {
                firstError = e;
            } finally {
                sessionRealStorage = null;
            }
        }
        if (sessionFrm != null) {
            try {
                sessionFrm.close();
            } catch (Exception e) {
                if (firstError == null) {
                    firstError = e;
                }
            } finally {
                sessionFrm = null;
            }
        }
        if (frm != null) {
            try {
                frm.close();
            } catch (Exception e) {
                if (firstError == null) {
                    firstError = e;
                }
            } finally {
                frm = null;
            }
        }
        if (ownsSessionDir) {
            deleteRecursively(sessionDir);
            sessionDir = null;
            ownsSessionDir = false;
        }
        deleteRecursively(tempLogDir);
        tempLogDir = null;
        if (firstError != null) {
            throw firstError;
        }
    }

    private void openSession() throws Exception {
        Logger logger = new Logger(sessionWalFile);
        if (mocked(Component.SM)) {
            sessionRealStorage = null;
            sessionFrm = new FRManager(logger, sessionMockStorage);
        } else {
            Path dataDir = sessionDir.resolve("data");
            Files.createDirectories(dataDir);
            sessionRealStorage = Engine.storage(dataDir, bufferFrames, logger);
            sessionFrm = new FRManager(logger, sessionRealStorage);
        }

        sessionCcm = createSessionCcm();
    }

    private ConcurrencyControl createSessionCcm() {
        if (mocked(Component.CCM)) {
            return new MockConcurrencyControl();
        }
        ConcurrencyControlManager ccm =
                new ConcurrencyControlManager(
                        new TransactionManager(), new TimestampBasedProtocol(), sessionFrm);
        ccm.initializeAfterRecovery(sessionFrm.getRecoveryInfo());
        return ccm;
    }

    private void closeSessionForCrash() throws Exception {
        Exception firstError = null;
        if (sessionRealStorage != null) {
            try {
                sessionRealStorage.close();
            } catch (Exception e) {
                firstError = e;
            } finally {
                sessionRealStorage = null;
            }
        }
        if (sessionFrm != null) {
            try {
                sessionFrm.getLogger().closeWithoutFlush();
            } catch (Exception e) {
                if (firstError == null) {
                    firstError = e;
                }
            } finally {
                sessionFrm = null;
            }
        }
        if (firstError != null) {
            throw firstError;
        }
    }

    private void registerCommands() {
        command(
                "test",
                "test [name]",
                "run FRM integration tests (all, undo, redo, recovery, interleaved, pitr,"
                        + " real-storage, checkpoint)",
                (args, rest) -> runTestCommand(args));
        command(
                "begin",
                "begin <txId>",
                "start a transaction (BEGIN)",
                (args, rest) -> {
                    if (args.size() != 1) {
                        throw new DriverException("usage: begin <txId>");
                    }
                    int txId = parseInt(args.get(0), "txId");
                    if (!sessionFrm.beginTransaction(txId)) {
                        throw new DriverException("begin failed for T" + txId);
                    }
                    out().println("T" + txId + " BEGIN");
                });
        command(
                "insert",
                "insert <txId> <table> <id> <value>",
                "log INSERT and write to storage",
                (args, rest) -> {
                    if (args.size() != 4) {
                        throw new DriverException("usage: insert <txId> <table> <id> <value>");
                    }
                    int txId = parseInt(args.get(0), "txId");
                    String table = args.get(1);
                    int id = parseInt(args.get(2), "id");
                    String value = parseNullable(args.get(3));
                    long lsn = performInsert(txId, table, id, value);
                    out().println(
                                    "INSERT LSN="
                                            + lsn
                                            + " ("
                                            + table
                                            + "#"
                                            + id
                                            + "="
                                            + value
                                            + ")");
                });
        command(
                "update",
                "update <txId> <table> <id> <value>",
                "log UPDATE and write to storage",
                (args, rest) -> {
                    if (args.size() != 4) {
                        throw new DriverException("usage: update <txId> <table> <id> <value>");
                    }
                    int txId = parseInt(args.get(0), "txId");
                    String table = args.get(1);
                    int id = parseInt(args.get(2), "id");
                    String value = parseNullable(args.get(3));
                    long lsn = performUpdate(txId, table, id, value, false);
                    out().println(
                                    "UPDATE LSN="
                                            + lsn
                                            + " ("
                                            + table
                                            + "#"
                                            + id
                                            + "="
                                            + value
                                            + ")");
                });
        command(
                "update-wal",
                "update-wal <txId> <table> <id> <value>",
                "log UPDATE to WAL only (for REDO simulation)",
                (args, rest) -> {
                    if (args.size() != 4) {
                        throw new DriverException("usage: update-wal <txId> <table> <id> <value>");
                    }
                    int txId = parseInt(args.get(0), "txId");
                    String table = args.get(1);
                    int id = parseInt(args.get(2), "id");
                    String value = parseNullable(args.get(3));
                    long lsn = performUpdate(txId, table, id, value, true);
                    out().println(
                                    "UPDATE(WAL-ONLY) LSN="
                                            + lsn
                                            + " ("
                                            + table
                                            + "#"
                                            + id
                                            + "="
                                            + value
                                            + ")");
                });
        command(
                "delete",
                "delete <txId> <table> <id>",
                "log DELETE and remove from storage",
                (args, rest) -> {
                    if (args.size() != 3) {
                        throw new DriverException("usage: delete <txId> <table> <id>");
                    }
                    int txId = parseInt(args.get(0), "txId");
                    String table = args.get(1);
                    int id = parseInt(args.get(2), "id");
                    long lsn = performDelete(txId, table, id);
                    out().println("DELETE LSN=" + lsn + " (" + table + "#" + id + ")");
                });
        command(
                "commit",
                "commit <txId>",
                "commit a transaction (COMMIT + WAL flush)",
                (args, rest) -> {
                    if (args.size() != 1) {
                        throw new DriverException("usage: commit <txId>");
                    }
                    int txId = parseInt(args.get(0), "txId");
                    if (!sessionFrm.commitTransaction(txId)) {
                        throw new DriverException("commit failed for T" + txId);
                    }
                    out().println("T" + txId + " COMMIT");
                });
        command(
                "rollback",
                "rollback <txId>",
                "rollback a transaction (UNDO + CLR + ABORT)",
                (args, rest) -> {
                    if (args.size() != 1) {
                        throw new DriverException("usage: rollback <txId>");
                    }
                    int txId = parseInt(args.get(0), "txId");
                    if (!sessionFrm.rollbackTransaction(txId)) {
                        throw new DriverException("rollback failed for T" + txId);
                    }
                    out().println("T" + txId + " ROLLBACK");
                });
        command(
                "checkpoint",
                "checkpoint",
                "create a quiescent checkpoint using CCM",
                (args, rest) -> {
                    if (!args.isEmpty()) {
                        throw new DriverException("usage: checkpoint");
                    }
                    if (!sessionFrm.createCheckpoint(sessionCcm)) {
                        throw new DriverException("checkpoint failed");
                    }
                    out().println("CHECKPOINT COMPLETE");
                });
        command(
                "crash",
                "crash",
                "simulate crash and restart from WAL and storage",
                (args, rest) -> {
                    if (!args.isEmpty()) {
                        throw new DriverException("usage: crash");
                    }
                    closeSessionForCrash();
                    openSession();
                    out().println("CRASH SIMULATED & RESTARTED");
                });
        command(
                "recover",
                "recover",
                "run ARIES crash recovery and sync CCM",
                (args, rest) -> {
                    if (!args.isEmpty()) {
                        throw new DriverException("usage: recover");
                    }
                    if (!sessionFrm.recoverFromCrash()) {
                        throw new DriverException("recoverFromCrash failed");
                    }
                    sessionCcm = createSessionCcm();
                    out().println("CRASH RECOVERY COMPLETE");
                });
        command(
                "redo",
                "redo",
                "run redo phase",
                (args, rest) -> {
                    if (!args.isEmpty()) {
                        throw new DriverException("usage: redo");
                    }
                    if (!sessionFrm.redo()) {
                        throw new DriverException("redo failed");
                    }
                    out().println("REDO COMPLETE");
                });
        command(
                "undo",
                "undo",
                "run undo phase",
                (args, rest) -> {
                    if (!args.isEmpty()) {
                        throw new DriverException("usage: undo");
                    }
                    if (!sessionFrm.undo()) {
                        throw new DriverException("undo failed");
                    }
                    out().println("UNDO COMPLETE");
                });
        command(
                "pitr-ts",
                "pitr-ts <epochMs>",
                "recover to point-in-time timestamp",
                (args, rest) -> {
                    if (args.size() != 1) {
                        throw new DriverException("usage: pitr-ts <epochMs>");
                    }
                    long targetTs = parseLong(args.get(0), "epochMs");
                    if (!sessionFrm.recoverToPointInTime(targetTs)) {
                        throw new DriverException("recoverToPointInTime failed for " + targetTs);
                    }
                    sessionCcm = createSessionCcm();
                    out().println("PITR COMPLETE ts=" + targetTs);
                });
        command(
                "pitr-tx",
                "pitr-tx <txId>",
                "recover to state right after committed transaction",
                (args, rest) -> {
                    if (args.size() != 1) {
                        throw new DriverException("usage: pitr-tx <txId>");
                    }
                    int txId = parseInt(args.get(0), "txId");
                    if (!sessionFrm.recoverToTransaction(txId)) {
                        throw new DriverException("recoverToTransaction failed for T" + txId);
                    }
                    sessionCcm = createSessionCcm();
                    out().println("PITR COMPLETE tx=" + txId);
                });
        command(
                "status",
                "status <txId>",
                "show transaction status from WAL",
                (args, rest) -> {
                    if (args.size() != 1) {
                        throw new DriverException("usage: status <txId>");
                    }
                    int txId = parseInt(args.get(0), "txId");
                    out().println("T" + txId + ": " + sessionFrm.getTransactionStatus(txId));
                });
        command(
                "get",
                "get <table> <id>",
                "read current value of a record",
                (args, rest) -> {
                    if (args.size() != 2) {
                        throw new DriverException("usage: get <table> <id>");
                    }
                    String table = args.get(0);
                    int id = parseInt(args.get(1), "id");
                    out().println(table + "#" + id + " = " + readValue(table, id));
                });
        command(
                "logs",
                "logs [walFile]",
                "inspect WAL records in LSN order",
                (args, rest) -> {
                    if (args.size() > 1) {
                        throw new DriverException("usage: logs [walFile]");
                    }
                    String walFile = args.isEmpty() ? null : args.get(0);
                    printLogs(walFile, null);
                });
        command(
                "tx",
                "tx <txId> [walFile]",
                "filter WAL records by transaction ID",
                (args, rest) -> {
                    if (args.isEmpty() || args.size() > 2) {
                        throw new DriverException("usage: tx <txId> [walFile]");
                    }
                    int txId = parseInt(args.get(0), "txId");
                    String walFile = args.size() == 2 ? args.get(1) : null;
                    printLogs(walFile, txId);
                });
        command(
                "assert",
                "assert val <table> <id> <expected|null> | assert exists <table> <id>"
                        + " <true|false> | assert status <txId> <status|null> | assert logs"
                        + " <minCount>",
                "verify expected state and record PASS/FAIL",
                (args, rest) -> runAssert(args));
    }

    private void runTestCommand(List<String> args) throws RecoveryInternalException {
        if (args.size() > 1) {
            throw new DriverException(
                    "usage: test"
                            + " [all|undo|redo|recovery|interleaved|pitr|real-storage|checkpoint]");
        }
        String target = args.isEmpty() ? "all" : args.get(0).toLowerCase(Locale.ROOT);
        switch (target) {
            case "all" -> check("frm integration test suite", runAllTests());
            case "undo" -> check("test undo", testUndo());
            case "redo" -> check("test redo", testRedo());
            case "recovery" -> check("test recovery", testRecovery());
            case "interleaved" -> check("test interleaved", testInterleavedTransactionsRecovery());
            case "pitr" -> check("test pitr", testPointInTime());
            case "real-storage" ->
                    check("test real-storage", testRealStorageMultiTransactionCrashRecovery());
            case "checkpoint" ->
                    check("test checkpoint", testRealStorageWithoutAndWithCheckpoint());
            default ->
                    throw new DriverException(
                            "unknown test '"
                                    + target
                                    + "'; use all, undo, redo, recovery, interleaved, pitr,"
                                    + " real-storage, or checkpoint");
        }
    }

    private void runAssert(List<String> args) {
        if (args.isEmpty()) {
            throw new DriverException(
                    "usage: assert val <table> <id> <expected|null> | assert exists <table> <id>"
                            + " <true|false> | assert status <txId> <status|null> | assert logs"
                            + " <minCount>");
        }
        String kind = args.get(0).toLowerCase(Locale.ROOT);
        switch (kind) {
            case "val" -> {
                if (args.size() != 4) {
                    throw new DriverException("usage: assert val <table> <id> <expected|null>");
                }
                String table = args.get(1);
                int id = parseInt(args.get(2), "id");
                String expected = parseNullable(args.get(3));
                String actual = readValue(table, id);
                check("val " + table + "#" + id, expected, actual);
            }
            case "exists" -> {
                if (args.size() != 4) {
                    throw new DriverException("usage: assert exists <table> <id> <true|false>");
                }
                String table = args.get(1);
                int id = parseInt(args.get(2), "id");
                boolean expected = parseBooleanStrict(args.get(3));
                boolean actual = recordExists(table, id);
                check("exists " + table + "#" + id, expected, actual);
            }
            case "status" -> {
                if (args.size() != 3) {
                    throw new DriverException("usage: assert status <txId> <status|null>");
                }
                int txId = parseInt(args.get(1), "txId");
                String expectedRaw = parseNullable(args.get(2));
                String expected = expectedRaw == null ? null : expectedRaw.toUpperCase(Locale.ROOT);
                TxStatus st = sessionFrm.getTransactionStatus(txId);
                String actual = st == null ? null : st.name();
                check("status T" + txId, expected, actual);
            }
            case "logs" -> {
                if (args.size() != 2) {
                    throw new DriverException("usage: assert logs <minCount>");
                }
                int minCount = parseInt(args.get(1), "minCount");
                int actual = sessionFrm.getLogger().getLogsSorted().size();
                check("logs >= " + minCount, actual >= minCount);
            }
            default -> throw new DriverException("unknown assert target: " + kind);
        }
    }

    private long performInsert(int txId, String table, int id, String value) throws Exception {
        if (sessionRealStorage == null) {
            if (sessionMockStorage.exists(table, id)) {
                throw new DriverException("record already exists: " + table + "#" + id);
            }
            long lsn =
                    sessionFrm.writeLog(
                            txId, LogType.INSERT, table, id, null, MockStorage.encodeImage(value));
            sessionMockStorage.put(table, id, value);
            return lsn;
        }
        ensureTableExists(table);
        if (findRealRow(table, id).isPresent()) {
            throw new DriverException("record already exists: " + table + "#" + id);
        }
        Row newRow = buildRow(id, value);
        Rid rid = sessionRealStorage.reserveRid(table, newRow);
        byte[] afterBytes = sessionRealStorage.encodeRow(table, newRow);
        long lsn =
                sessionFrm.writeLog(
                        new LogPayload(
                                new TxId(txId),
                                org.rafendb.common.log.LogType.INSERT,
                                System.currentTimeMillis(),
                                Optional.of(
                                        new ChangeRecord(
                                                ResourceId.ofRow(table, rid),
                                                Optional.empty(),
                                                Optional.of(afterBytes)))));
        try {
            sessionRealStorage.insert(table, rid, newRow, lsn);
        } catch (Exception e) {
            sessionRealStorage.cancelReservation(table, rid);
            throw e;
        }
        return lsn;
    }

    private long performUpdate(int txId, String table, int id, String value, boolean walOnly)
            throws Exception {
        if (sessionRealStorage == null) {
            if (!sessionMockStorage.exists(table, id)) {
                throw new DriverException("record not found: " + table + "#" + id);
            }
            String before = sessionMockStorage.getRaw(table, id);
            String after = MockStorage.encodeImage(value);
            long lsn = sessionFrm.writeLog(txId, LogType.UPDATE, table, id, before, after);
            if (!walOnly) {
                sessionMockStorage.put(table, id, value);
            }
            return lsn;
        }
        RowEntry existing =
                findRealRow(table, id)
                        .orElseThrow(
                                () -> new DriverException("record not found: " + table + "#" + id));
        Row newRow = buildRow(id, value);
        byte[] beforeBytes = sessionRealStorage.encodeRow(table, existing.row());
        byte[] afterBytes = sessionRealStorage.encodeRow(table, newRow);
        long lsn =
                sessionFrm.writeLog(
                        new LogPayload(
                                new TxId(txId),
                                org.rafendb.common.log.LogType.UPDATE,
                                System.currentTimeMillis(),
                                Optional.of(
                                        new ChangeRecord(
                                                ResourceId.ofRow(table, existing.rid()),
                                                Optional.of(beforeBytes),
                                                Optional.of(afterBytes)))));
        if (!walOnly) {
            sessionRealStorage.update(table, existing.rid(), newRow, lsn);
        }
        return lsn;
    }

    private long performDelete(int txId, String table, int id) throws Exception {
        if (sessionRealStorage == null) {
            if (!sessionMockStorage.exists(table, id)) {
                throw new DriverException("record not found: " + table + "#" + id);
            }
            String before = sessionMockStorage.getRaw(table, id);
            long lsn = sessionFrm.writeLog(txId, LogType.DELETE, table, id, before, null);
            sessionMockStorage.applyUndo(table, id, null, lsn);
            return lsn;
        }
        RowEntry existing =
                findRealRow(table, id)
                        .orElseThrow(
                                () -> new DriverException("record not found: " + table + "#" + id));
        byte[] beforeBytes = sessionRealStorage.encodeRow(table, existing.row());
        long lsn =
                sessionFrm.writeLog(
                        new LogPayload(
                                new TxId(txId),
                                org.rafendb.common.log.LogType.DELETE,
                                System.currentTimeMillis(),
                                Optional.of(
                                        new ChangeRecord(
                                                ResourceId.ofRow(table, existing.rid()),
                                                Optional.of(beforeBytes),
                                                Optional.empty()))));
        sessionRealStorage.delete(table, existing.rid(), lsn);
        return lsn;
    }

    private String readValue(String table, int id) {
        if (sessionRealStorage == null) {
            return sessionMockStorage.get(table, id);
        }
        try {
            return findRealRow(table, id).map(e -> extractValue(e.row())).orElse(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read record " + table + "#" + id, e);
        }
    }

    private boolean recordExists(String table, int id) {
        if (sessionRealStorage == null) {
            return sessionMockStorage.exists(table, id);
        }
        try {
            return findRealRow(table, id).isPresent();
        } catch (Exception e) {
            throw new RuntimeException("Failed to check record existence " + table + "#" + id, e);
        }
    }

    private void ensureTableExists(String table) throws Exception {
        if (!sessionRealStorage.listTables().contains(table)) {
            sessionRealStorage.createTable(
                    new TableSchema(
                            table,
                            List.of(
                                    new Column("id", DataType.INTEGER, 0, false, true),
                                    new Column("val", DataType.VARCHAR, 255, true, false))));
        }
    }

    private Optional<RowEntry> findRealRow(String table, int id) throws Exception {
        if (!sessionRealStorage.listTables().contains(table)) {
            return Optional.empty();
        }
        try (RecordCursor cursor = sessionRealStorage.scan(table)) {
            StoredRow sr;
            while ((sr = cursor.next()) != null) {
                Value keyVal = sr.row().values().getFirst();
                if (!keyVal.isNull() && keyVal.asInt() == id) {
                    return Optional.of(new RowEntry(sr.rid(), sr.row()));
                }
            }
        }
        return Optional.empty();
    }

    private static Row buildRow(int id, String value) {
        return new Row(
                List.of(
                        Value.ofInt(id),
                        value == null
                                ? Value.nullOf(DataType.VARCHAR)
                                : Value.ofText(DataType.VARCHAR, value)));
    }

    private static String extractValue(Row row) {
        Value val = row.values().get(1);
        return val.isNull() ? null : String.valueOf(val.value());
    }

    private void printLogs(String walFile, Integer txFilter) throws Exception {
        if (walFile == null || walFile.isEmpty()) {
            printLoggerRecords(
                    sessionFrm.getLogger(),
                    sessionWalFile != null ? sessionWalFile.toString() : "[session]",
                    txFilter);
            return;
        }
        try (Logger fileLogger = new Logger(walFile)) {
            printLoggerRecords(fileLogger, walFile, txFilter);
        }
    }

    private void printLoggerRecords(Logger targetLogger, String sourceLabel, Integer txFilter) {
        List<WALRecord> logs =
                txFilter == null
                        ? targetLogger.getLogsSorted()
                        : targetLogger.getLogsByTransaction(txFilter);
        out().println("=== WAL RECORD VIEWER ===");
        out().println("Log Source: " + sourceLabel);
        if (txFilter != null) {
            out().println("Filtered for Transaction: " + txFilter);
        }
        out().println("Total Records: " + logs.size());
        out().println("----------------------------------------------");
        for (WALRecord record : logs) {
            out().println(record);
        }
        out().println("==============================================");
    }

    private static int parseInt(String raw, String field) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new DriverException("invalid integer for " + field + ": " + raw);
        }
    }

    private static long parseLong(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new DriverException("invalid long for " + field + ": " + raw);
        }
    }

    private static String parseNullable(String raw) {
        return "null".equalsIgnoreCase(raw) ? null : raw;
    }

    private static boolean parseBooleanStrict(String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new DriverException("invalid boolean (expected true|false): " + raw);
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
        } catch (IOException ignored) {
        }
    }

    private void initNewEnvironment() {
        try {
            if (frm != null) {
                frm.close();
                frm = null;
            }
            if (tempLogDir == null) {
                tempLogDir = Files.createTempDirectory("rafendb-frm-test-");
            }
            currentLogFile = tempLogDir.resolve("wal-" + System.nanoTime() + ".log");
            Logger logger = new Logger(currentLogFile);
            RollbackManager rollbackManager = new RollbackManager(logger, storage);
            CheckpointManager checkpointManager = new CheckpointManager(logger);
            CrashRecoveryEngine crashRecoveryEngine = new CrashRecoveryEngine(logger, storage);
            this.frm =
                    new FRManager(logger, rollbackManager, checkpointManager, crashRecoveryEngine);
        } catch (IOException | LoggerErrorException e) {
            throw new RuntimeException("Failed to initialize driver environment", e);
        }
    }

    /**
     * Mensimulasikan crash sistem secara mendadak dan restart basis data.
     * Menutup logger tanpa buffer flush tambahan, lalu menginisialisasi FRManager baru dari file log yang ada di disk.
     */
    public void simulateCrashAndRestart() {
        try {
            // Tutup channel file tanpa clean flush
            frm.getLogger().closeWithoutFlush();
            Logger newLogger = new Logger(currentLogFile);
            RollbackManager rollbackManager = new RollbackManager(newLogger, storage);
            CheckpointManager checkpointManager = new CheckpointManager(newLogger);
            CrashRecoveryEngine crashRecoveryEngine = new CrashRecoveryEngine(newLogger, storage);
            this.frm =
                    new FRManager(
                            newLogger, rollbackManager, checkpointManager, crashRecoveryEngine);
        } catch (Exception e) {
            throw new RuntimeException("Failed during simulateCrashAndRestart", e);
        }
    }

    /**
     * Menguji mekanisme Undo untuk transaksi yang dibatalkan (rollback).
     */
    public boolean testUndo() throws RecoveryInternalException {
        storage.clear();
        initNewEnvironment();

        int tx1 = 1;
        frm.beginTransaction(tx1);

        // Record awal
        storage.put("account", 101, "Alice:100");
        frm.writeLog(tx1, LogType.UPDATE, "account", 101, "Alice:100", "Alice:250");
        storage.put("account", 101, "Alice:250");

        // Rollback Tx1
        boolean rollbackSuccess = frm.rollbackTransaction(tx1);
        String restoredVal = storage.get("account", 101);
        out().println("  [DETAIL] Expected: restored=Alice:100 | Actual: restored=" + restoredVal);

        return rollbackSuccess && "Alice:100".equals(restoredVal);
    }

    /**
     * Menguji mekanisme Redo dengan menerapkan ulang perubahan transaksi yang sudah commit dari WAL.
     */
    public boolean testRedo() {
        storage.clear();
        initNewEnvironment();
        try {
            frm.beginTransaction(1);
            frm.writeLog(1, LogType.INSERT, "accounts", 101, null, "Redone-Value");
            frm.commitTransaction(1);

            boolean ok = frm.redo();
            String actual = storage.get("accounts", 101);
            out().println(
                            "  [DETAIL] Expected: redo=true, val=Redone-Value | Actual: redo="
                                    + ok
                                    + ", val="
                                    + actual);
            return ok && "Redone-Value".equals(actual);
        } catch (RecoveryInternalException e) {
            e.printStackTrace(out());
            return false;
        }
    }

    /**
     * Menguji skenario crash recovery dengan persistensi log nyata ke disk.
     * Mensimulasikan:
     * - T1 menulis data dan berhasil COMMIT.
     * - T2 menulis perubahan kotor (dirty data) namun sistem mengalami CRASH sebelum COMMIT.
     * - Sistem restart dari file WAL dan menjalankan recoverFromCrash (Redo T1, Undo T2).
     */
    public boolean testRecovery() throws RecoveryInternalException {
        storage.clear();
        initNewEnvironment();

        // 1. Transaksi 1 committed
        frm.beginTransaction(1);
        frm.writeLog(1, LogType.INSERT, "accounts", 101, null, "User1-Committed");
        storage.put("accounts", 101, "User1-Committed");
        frm.commitTransaction(1);

        // 2. Transaksi 2 belum commit saat crash
        frm.beginTransaction(2);
        frm.writeLog(2, LogType.UPDATE, "accounts", 101, "User1-Committed", "User1-Dirty");
        storage.put("accounts", 101, "User1-Dirty");

        // 3. Simulasi crash mendadak dan restart dari disk
        simulateCrashAndRestart();

        // 4. Jalankan prosedur crash recovery
        boolean recoveryOk = frm.recoverFromCrash();
        String valAfterRecovery = storage.get("accounts", 101);
        out().println("  [DETAIL] Expected: User1-Committed | Actual: " + valAfterRecovery);

        return recoveryOk && "User1-Committed".equals(valAfterRecovery);
    }

    /**
     * Menguji pemulihan transaksi bersisipan (interleaved transactions).
     * Transaksi T1, T2, dan T3 berjalan bersamaan:
     * - T1 commit
     * - T2 di-rollback
     * - T3 commit
     */
    public boolean testInterleavedTransactionsRecovery() throws RecoveryInternalException {
        storage.clear();
        initNewEnvironment();

        frm.beginTransaction(1);
        frm.beginTransaction(2);
        frm.beginTransaction(3);

        // T1 write
        frm.writeLog(1, LogType.INSERT, "ledger", 10, null, "Val-T1");
        storage.put("ledger", 10, "Val-T1");

        // T2 write (interleaved)
        frm.writeLog(2, LogType.INSERT, "ledger", 20, null, "Val-T2");
        storage.put("ledger", 20, "Val-T2");

        // T1 commit
        boolean t1Commit = frm.commitTransaction(1);

        // T3 write
        frm.writeLog(3, LogType.INSERT, "ledger", 30, null, "Val-T3");
        storage.put("ledger", 30, "Val-T3");

        // T3 commit
        boolean t3Commit = frm.commitTransaction(3);

        // T2 rollback
        boolean t2Rollback = frm.rollbackTransaction(2);

        String v10 = storage.get("ledger", 10);
        String v20 = storage.get("ledger", 20);
        String v30 = storage.get("ledger", 30);
        boolean t1Ok = "Val-T1".equals(v10);
        boolean t2Ok = (v20 == null);
        boolean t3Ok = "Val-T3".equals(v30);
        out().println(
                        "  [DETAIL] Expected: ledger#10=Val-T1, ledger#20=null, ledger#30=Val-T3"
                                + " | Actual: ledger#10="
                                + v10
                                + ", ledger#20="
                                + v20
                                + ", ledger#30="
                                + v30);

        return t1Commit && t3Commit && t2Rollback && t1Ok && t2Ok && t3Ok;
    }

    /**
     * Menguji pemulihan point-in-time ke timestamp sebelum perubahan tertentu terjadi.
     */
    public boolean testPointInTime() throws RecoveryInternalException {
        storage.clear();
        initNewEnvironment();

        frm.beginTransaction(1);
        frm.writeLog(1, LogType.INSERT, "docs", 1, null, "Version1");
        storage.put("docs", 1, "Version1");
        frm.commitTransaction(1);

        long timestampCutoff = System.currentTimeMillis();
        try {
            Thread.sleep(50); // jeda waktu agar timestamp berikutnya terpisah
        } catch (InterruptedException ignored) {
        }

        frm.beginTransaction(2);
        frm.writeLog(2, LogType.UPDATE, "docs", 1, "Version1", "Version2");
        storage.put("docs", 1, "Version2");
        frm.commitTransaction(2);

        // Pulihkan ke titik waktu sebelum T2
        boolean pitrOk = frm.recoverToPointInTime(timestampCutoff);
        String valAfterPitr = storage.get("docs", 1);
        out().println("  [DETAIL] Expected: Version1 | Actual: " + valAfterPitr);

        return pitrOk && "Version1".equals(valAfterPitr);
    }

    /**
     * FR-08 & FR-09: Menguji skenario multi-transaksi dengan {@link StorageManager} asli berbasis
     * berkas, mencakup INSERT/UPDATE/DELETE, rollback, checkpoint, redo (belum flush ke disk), dan
     * undo (terlanjur ditulis ke disk).
     */
    public boolean testRealStorageMultiTransactionCrashRecovery() {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("frm-real-driver-");
            Path dataDir = dir.resolve("data");
            Path walPath = dir.resolve("wal.log");
            ConcurrencyControl mockCc = new MockConcurrencyControl();

            TableSchema schema =
                    new TableSchema(
                            "accounts",
                            List.of(
                                    new Column("id", DataType.INTEGER, 0, false, true),
                                    new Column("owner", DataType.VARCHAR, 50, true, false)));
            Row r1 = new Row(List.of(Value.ofInt(1), Value.ofText(DataType.VARCHAR, "Alice")));
            Row r1Updated =
                    new Row(List.of(Value.ofInt(1), Value.ofText(DataType.VARCHAR, "Alice-V2")));
            Row r2 = new Row(List.of(Value.ofInt(2), Value.ofText(DataType.VARCHAR, "Bob")));
            Row r3 = new Row(List.of(Value.ofInt(3), Value.ofText(DataType.VARCHAR, "Charlie")));

            Rid rid1;
            Rid rid2;
            Rid rid3;

            try (Logger logger = new Logger(walPath);
                    StorageManager sm = Engine.storage(dataDir, 8, logger)) {
                @SuppressWarnings("resource") // closes only its Logger, which the try owns
                FRManager realFrm = new FRManager(logger, sm);
                sm.createTable(schema);

                // Tx1: INSERT r1 -> COMMIT, lalu buat checkpoint selesai
                TxId tx1 = new TxId(101);
                realFrm.beginTransaction(tx1);
                rid1 = sm.reserveRid("accounts", r1);
                byte[] r1Bytes = sm.encodeRow("accounts", r1);
                long lsn1 =
                        realFrm.writeLog(
                                new LogPayload(
                                        tx1,
                                        org.rafendb.common.log.LogType.INSERT,
                                        System.currentTimeMillis(),
                                        Optional.of(
                                                new ChangeRecord(
                                                        ResourceId.ofRow("accounts", rid1),
                                                        Optional.empty(),
                                                        Optional.of(r1Bytes)))));
                sm.insert("accounts", rid1, r1, lsn1);
                realFrm.commitTransaction(tx1);
                realFrm.createCheckpoint(mockCc);

                // Tx2: INSERT r2 lalu ABORT (rollback)
                TxId tx2 = new TxId(102);
                realFrm.beginTransaction(tx2);
                rid2 = sm.reserveRid("accounts", r2);
                byte[] r2Bytes = sm.encodeRow("accounts", r2);
                long lsn2 =
                        realFrm.writeLog(
                                new LogPayload(
                                        tx2,
                                        org.rafendb.common.log.LogType.INSERT,
                                        System.currentTimeMillis(),
                                        Optional.of(
                                                new ChangeRecord(
                                                        ResourceId.ofRow("accounts", rid2),
                                                        Optional.empty(),
                                                        Optional.of(r2Bytes)))));
                sm.insert("accounts", rid2, r2, lsn2);
                realFrm.rollbackTransaction(tx2);

                // Tx3: UPDATE r1 -> r1Updated & COMMIT tanpa mem-flush ke SM (simulasi redo)
                TxId tx3 = new TxId(103);
                realFrm.beginTransaction(tx3);
                byte[] r1UpdBytes = sm.encodeRow("accounts", r1Updated);
                realFrm.writeLog(
                        new LogPayload(
                                tx3,
                                org.rafendb.common.log.LogType.UPDATE,
                                System.currentTimeMillis(),
                                Optional.of(
                                        new ChangeRecord(
                                                ResourceId.ofRow("accounts", rid1),
                                                Optional.of(r1Bytes),
                                                Optional.of(r1UpdBytes)))));
                realFrm.commitTransaction(tx3);

                // Tx4: INSERT r3 ke SM & WAL tanpa COMMIT (simulasi crash uncommitted -> undo)
                TxId tx4 = new TxId(104);
                realFrm.beginTransaction(tx4);
                rid3 = sm.reserveRid("accounts", r3);
                byte[] r3Bytes = sm.encodeRow("accounts", r3);
                long lsn4 =
                        realFrm.writeLog(
                                new LogPayload(
                                        tx4,
                                        org.rafendb.common.log.LogType.INSERT,
                                        System.currentTimeMillis(),
                                        Optional.of(
                                                new ChangeRecord(
                                                        ResourceId.ofRow("accounts", rid3),
                                                        Optional.empty(),
                                                        Optional.of(r3Bytes)))));
                sm.insert("accounts", rid3, r3, lsn4);
            }

            try (Logger restartedLogger = new Logger(walPath);
                    StorageManager restartedSm = Engine.storage(dataDir, 8, restartedLogger)) {
                @SuppressWarnings("resource") // closes only its Logger, which the try owns
                FRManager restartedFrm = new FRManager(restartedLogger, restartedSm);
                boolean recovered = restartedFrm.recoverFromCrash();
                Optional<Row> actualR1 = restartedSm.fetch("accounts", rid1).map(StoredRow::row);
                Optional<Row> actualR2 = restartedSm.fetch("accounts", rid2).map(StoredRow::row);
                Optional<Row> actualR3 = restartedSm.fetch("accounts", rid3).map(StoredRow::row);

                boolean ok =
                        recovered
                                && Optional.of(r1Updated).equals(actualR1)
                                && actualR2.isEmpty()
                                && actualR3.isEmpty();
                out().println(
                                "  [DETAIL] Expected: r1="
                                        + r1Updated
                                        + ", r2=empty, r3=empty | Actual: r1="
                                        + actualR1.orElse(null)
                                        + ", r2="
                                        + actualR2.orElse(null)
                                        + ", r3="
                                        + actualR3.orElse(null));
                return ok;
            }
        } catch (Exception e) {
            e.printStackTrace(out());
            return false;
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * Helper untuk simulasi crash proses OS nyata (FR-08): menulis transaksi committed dan
     * uncommitted ke StorageManager + WAL asli, lalu menunggu dihentikan paksa tanpa graceful
     * shutdown.
     */
    public static void runCrashChildSimulation(Path dataDir, Path walPath) throws Exception {
        Logger logger = new Logger(walPath);
        StorageManager sm = Engine.storage(dataDir, 8, logger);
        @SuppressWarnings("resource") // left open: the process is killed to simulate a crash
        FRManager realFrm = new FRManager(logger, sm);

        sm.createTable(
                new TableSchema(
                        "items",
                        List.of(
                                new Column("id", DataType.INTEGER, 0, false, true),
                                new Column("name", DataType.VARCHAR, 50, true, false))));

        Row committedRow =
                new Row(List.of(Value.ofInt(1), Value.ofText(DataType.VARCHAR, "CommittedItem")));
        Row uncommittedRow =
                new Row(List.of(Value.ofInt(2), Value.ofText(DataType.VARCHAR, "UncommittedItem")));

        // Tx1: COMMIT (harus bertahan pasca-crash)
        TxId tx1 = new TxId(1);
        realFrm.beginTransaction(tx1);
        Rid rid1 = sm.reserveRid("items", committedRow);
        byte[] cBytes = sm.encodeRow("items", committedRow);
        long lsn1 =
                realFrm.writeLog(
                        new LogPayload(
                                tx1,
                                org.rafendb.common.log.LogType.INSERT,
                                System.currentTimeMillis(),
                                Optional.of(
                                        new ChangeRecord(
                                                ResourceId.ofRow("items", rid1),
                                                Optional.empty(),
                                                Optional.of(cBytes)))));
        sm.insert("items", rid1, committedRow, lsn1);
        realFrm.commitTransaction(tx1);

        // Tx2: ACTIVE dan sudah menulis ke disk (flush) tanpa COMMIT -> wajib di-UNDO saat restart
        TxId tx2 = new TxId(2);
        realFrm.beginTransaction(tx2);
        Rid rid2 = sm.reserveRid("items", uncommittedRow);
        byte[] uBytes = sm.encodeRow("items", uncommittedRow);
        long lsn2 =
                realFrm.writeLog(
                        new LogPayload(
                                tx2,
                                org.rafendb.common.log.LogType.INSERT,
                                System.currentTimeMillis(),
                                Optional.of(
                                        new ChangeRecord(
                                                ResourceId.ofRow("items", rid2),
                                                Optional.empty(),
                                                Optional.of(uBytes)))));
        sm.insert("items", rid2, uncommittedRow, lsn2);
        sm.flush();

        System.out.println(
                "READY_FOR_CRASH:"
                        + rid1.block()
                        + ","
                        + rid1.slot()
                        + ";"
                        + rid2.block()
                        + ","
                        + rid2.slot());
        System.out.flush();
        Thread.sleep(60_000L);
    }

    /**
     * FR-08: Menguji crash recovery tanpa checkpoint dan dengan checkpoint yang selesai
     * menggunakan {@link StorageManager} asli berbasis berkas.
     */
    public boolean testRealStorageWithoutAndWithCheckpoint() {
        Path dirA = null;
        Path dirB = null;
        try {
            ConcurrencyControl mockCc = new MockConcurrencyControl();
            TableSchema schema =
                    new TableSchema(
                            "notes",
                            List.of(
                                    new Column("id", DataType.INTEGER, 0, false, true),
                                    new Column("body", DataType.VARCHAR, 50, false, false)));
            Row noCkptCommitted =
                    new Row(
                            List.of(
                                    Value.ofInt(1),
                                    Value.ofText(DataType.VARCHAR, "NoCkpt-Committed")));
            Row noCkptUncommitted =
                    new Row(
                            List.of(
                                    Value.ofInt(2),
                                    Value.ofText(DataType.VARCHAR, "NoCkpt-Uncommitted")));
            Row preCkpt =
                    new Row(
                            List.of(
                                    Value.ofInt(1),
                                    Value.ofText(DataType.VARCHAR, "PreCkpt-Committed")));
            Row postCkptCommitted =
                    new Row(
                            List.of(
                                    Value.ofInt(1),
                                    Value.ofText(DataType.VARCHAR, "PostCkpt-Committed")));
            Row postCkptUncommitted =
                    new Row(
                            List.of(
                                    Value.ofInt(2),
                                    Value.ofText(DataType.VARCHAR, "PostCkpt-Uncommitted")));

            // Skenario A: Tanpa checkpoint
            dirA = Files.createTempDirectory("frm-no-ckpt-");
            Path dataDirA = dirA.resolve("data");
            Path walPathA = dirA.resolve("wal.log");
            Rid ridA1;
            Rid ridA2;
            try (Logger loggerA = new Logger(walPathA);
                    StorageManager smA = Engine.storage(dataDirA, 8, loggerA)) {
                @SuppressWarnings("resource") // closes only its Logger, which the try owns
                FRManager frmA = new FRManager(loggerA, smA);
                smA.createTable(schema);
                TxId tx1 = new TxId(1);
                frmA.beginTransaction(tx1);
                ridA1 = smA.reserveRid("notes", noCkptCommitted);
                frmA.writeLog(
                        new LogPayload(
                                tx1,
                                org.rafendb.common.log.LogType.INSERT,
                                System.currentTimeMillis(),
                                Optional.of(
                                        new ChangeRecord(
                                                ResourceId.ofRow("notes", ridA1),
                                                Optional.empty(),
                                                Optional.of(
                                                        smA.encodeRow(
                                                                "notes", noCkptCommitted))))));
                frmA.commitTransaction(tx1);

                TxId tx2 = new TxId(2);
                frmA.beginTransaction(tx2);
                ridA2 = smA.reserveRid("notes", noCkptUncommitted);
                long lsn2 =
                        frmA.writeLog(
                                new LogPayload(
                                        tx2,
                                        org.rafendb.common.log.LogType.INSERT,
                                        System.currentTimeMillis(),
                                        Optional.of(
                                                new ChangeRecord(
                                                        ResourceId.ofRow("notes", ridA2),
                                                        Optional.empty(),
                                                        Optional.of(
                                                                smA.encodeRow(
                                                                        "notes",
                                                                        noCkptUncommitted))))));
                smA.insert("notes", ridA2, noCkptUncommitted, lsn2);
            }

            Optional<Row> actualA1;
            Optional<Row> actualA2;
            boolean recAOk;
            try (Logger restartedA = new Logger(walPathA);
                    StorageManager smA2 = Engine.storage(dataDirA, 8, restartedA)) {
                @SuppressWarnings("resource") // closes only its Logger, which the try owns
                FRManager frmA2 = new FRManager(restartedA, smA2);
                recAOk = frmA2.recoverFromCrash();
                actualA1 = smA2.fetch("notes", ridA1).map(StoredRow::row);
                actualA2 = smA2.fetch("notes", ridA2).map(StoredRow::row);
            }

            // Skenario B: Dengan checkpoint selesai
            dirB = Files.createTempDirectory("frm-with-ckpt-");
            Path dataDirB = dirB.resolve("data");
            Path walPathB = dirB.resolve("wal.log");
            Rid ridB1;
            Rid ridB2;
            boolean ckptOk;
            try (Logger loggerB = new Logger(walPathB);
                    StorageManager smB = Engine.storage(dataDirB, 8, loggerB)) {
                @SuppressWarnings("resource") // closes only its Logger, which the try owns
                FRManager frmB = new FRManager(loggerB, smB);
                smB.createTable(schema);
                TxId tx1 = new TxId(1);
                frmB.beginTransaction(tx1);
                ridB1 = smB.reserveRid("notes", preCkpt);
                byte[] preBytes = smB.encodeRow("notes", preCkpt);
                long lsn1 =
                        frmB.writeLog(
                                new LogPayload(
                                        tx1,
                                        org.rafendb.common.log.LogType.INSERT,
                                        System.currentTimeMillis(),
                                        Optional.of(
                                                new ChangeRecord(
                                                        ResourceId.ofRow("notes", ridB1),
                                                        Optional.empty(),
                                                        Optional.of(preBytes)))));
                smB.insert("notes", ridB1, preCkpt, lsn1);
                frmB.commitTransaction(tx1);
                ckptOk = frmB.createCheckpoint(mockCc);

                // Post-checkpoint committed update (belum masuk ke SM -> redo)
                TxId tx2 = new TxId(2);
                frmB.beginTransaction(tx2);
                frmB.writeLog(
                        new LogPayload(
                                tx2,
                                org.rafendb.common.log.LogType.UPDATE,
                                System.currentTimeMillis(),
                                Optional.of(
                                        new ChangeRecord(
                                                ResourceId.ofRow("notes", ridB1),
                                                Optional.of(preBytes),
                                                Optional.of(
                                                        smB.encodeRow(
                                                                "notes", postCkptCommitted))))));
                frmB.commitTransaction(tx2);

                // Post-checkpoint uncommitted insert (sudah masuk ke SM -> undo)
                TxId tx3 = new TxId(3);
                frmB.beginTransaction(tx3);
                ridB2 = smB.reserveRid("notes", postCkptUncommitted);
                long lsn3 =
                        frmB.writeLog(
                                new LogPayload(
                                        tx3,
                                        org.rafendb.common.log.LogType.INSERT,
                                        System.currentTimeMillis(),
                                        Optional.of(
                                                new ChangeRecord(
                                                        ResourceId.ofRow("notes", ridB2),
                                                        Optional.empty(),
                                                        Optional.of(
                                                                smB.encodeRow(
                                                                        "notes",
                                                                        postCkptUncommitted))))));
                smB.insert("notes", ridB2, postCkptUncommitted, lsn3);
            }

            Optional<Row> actualB1;
            Optional<Row> actualB2;
            boolean recBOk;
            try (Logger restartedB = new Logger(walPathB);
                    StorageManager smB2 = Engine.storage(dataDirB, 8, restartedB)) {
                @SuppressWarnings("resource") // closes only its Logger, which the try owns
                FRManager frmB2 = new FRManager(restartedB, smB2);
                recBOk = frmB2.recoverFromCrash();
                actualB1 = smB2.fetch("notes", ridB1).map(StoredRow::row);
                actualB2 = smB2.fetch("notes", ridB2).map(StoredRow::row);
            }

            boolean ok =
                    recAOk
                            && Optional.of(noCkptCommitted).equals(actualA1)
                            && actualA2.isEmpty()
                            && ckptOk
                            && recBOk
                            && Optional.of(postCkptCommitted).equals(actualB1)
                            && actualB2.isEmpty();
            out().println(
                            "  [DETAIL] Expected: noCkpt(r1="
                                    + noCkptCommitted
                                    + ", r2=empty) & withCkpt(r1="
                                    + postCkptCommitted
                                    + ", r2=empty) | Actual: noCkpt(r1="
                                    + actualA1.orElse(null)
                                    + ", r2="
                                    + actualA2.orElse(null)
                                    + ") & withCkpt(r1="
                                    + actualB1.orElse(null)
                                    + ", r2="
                                    + actualB2.orElse(null)
                                    + ")");
            return ok;
        } catch (Exception e) {
            e.printStackTrace(out());
            return false;
        } finally {
            deleteRecursively(dirA);
            deleteRecursively(dirB);
        }
    }

    /**
     * Menjalankan seluruh rangkaian tes driver dan menampilkan laporan hasil.
     *
     * @return true jika seluruh pengujian berhasil
     */
    public boolean runAllTests() throws RecoveryInternalException {
        out().println("==================================================");
        out().println("       RUNNING FRM INTEGRATION TEST SUITE         ");
        out().println("==================================================");

        boolean undoPassed = testUndo();
        out().println("[TEST] Undo Mechanism: " + (undoPassed ? "PASSED" : "FAILED"));

        boolean redoPassed = testRedo();
        out().println("[TEST] Redo Mechanism: " + (redoPassed ? "PASSED" : "FAILED"));

        boolean recoveryPassed = testRecovery();
        out().println("[TEST] Crash Recovery: " + (recoveryPassed ? "PASSED" : "FAILED"));

        boolean interleavedPassed = testInterleavedTransactionsRecovery();
        out().println(
                        "[TEST] Interleaved Transactions: "
                                + (interleavedPassed ? "PASSED" : "FAILED"));

        boolean pitrPassed = testPointInTime();
        out().println("[TEST] Point-In-Time Recovery: " + (pitrPassed ? "PASSED" : "FAILED"));

        boolean realStoragePassed = testRealStorageMultiTransactionCrashRecovery();
        out().println(
                        "[TEST] Real Storage Multi-Tx Recovery: "
                                + (realStoragePassed ? "PASSED" : "FAILED"));

        boolean ckptComparePassed = testRealStorageWithoutAndWithCheckpoint();
        out().println(
                        "[TEST] Recovery Without vs With Checkpoint: "
                                + (ckptComparePassed ? "PASSED" : "FAILED"));

        out().println("==================================================");
        boolean allPassed =
                undoPassed
                        && redoPassed
                        && recoveryPassed
                        && interleavedPassed
                        && pitrPassed
                        && realStoragePassed
                        && ckptComparePassed;
        out().println("TOTAL RESULT: " + (allPassed ? "ALL TESTS PASSED!" : "SOME TESTS FAILED"));
        out().println("==================================================");
        return allPassed;
    }

    public FRManager getFRManager() {
        return sessionFrm != null ? sessionFrm : frm;
    }

    public Path getCurrentLogFile() {
        return sessionWalFile != null ? sessionWalFile : currentLogFile;
    }

    public MockStorage getStorage() {
        return sessionFrm != null && mocked(Component.SM) ? sessionMockStorage : storage;
    }
}
