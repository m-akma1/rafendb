package org.rafendb.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.api.storage.StorageRecovery;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.rollback.UndoStorage;

class FRManagerTest {

    @TempDir Path tempDir;

    private Path logPath;
    private Logger logger;
    private TestStorage storage;
    private FRManager frm;

    static class TestStorage implements UndoStorage {
        final Map<String, String> data = new HashMap<>();

        @Override
        public void applyUndo(String tableName, int idRecord, String image, long lsn) {
            String key = tableName + "#" + idRecord;
            if (image == null) {
                data.remove(key);
            } else {
                data.put(key, image);
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        logPath = tempDir.resolve("frm.log");
        logger = new Logger(logPath);
        storage = new TestStorage();
        frm = new FRManager(logger, storage);
    }

    @AfterEach
    void tearDown() {
        if (frm != null) {
            frm.close();
        }
    }

    @Test
    void testBeginAndWriteLogAndCommit() throws Exception {
        assertTrue(frm.beginTransaction(1));

        long lsn = frm.writeLog(1, LogType.INSERT, "customers", 10, null, "Bob");
        assertTrue(lsn > 0);

        assertTrue(frm.commitTransaction(1));
    }

    @Test
    void testWriteLogWithRidAndRow() throws Exception {
        assertTrue(frm.beginTransaction(5));

        Rid rid = new Rid(1, 2);
        ChangeRecord change =
                new ChangeRecord(
                        ResourceId.ofRow("accounts", rid),
                        Optional.empty(),
                        Optional.of(new byte[] {1, 2, 3}));
        LogPayload payload =
                LogPayload.ofChange(new TxId(5), org.rafendb.common.log.LogType.INSERT, change);
        long lsn = frm.writeLog(payload);
        assertTrue(lsn > 0);
        assertTrue(frm.commitTransaction(5));
    }

    @Test
    void testRollbackTransaction() throws Exception {
        assertTrue(frm.beginTransaction(2));
        storage.data.put("customers#20", "Charlie-Old");

        long lsn = frm.writeLog(2, LogType.UPDATE, "customers", 20, "Charlie-Old", "Charlie-New");
        assertTrue(lsn > 0);
        storage.data.put("customers#20", "Charlie-New");

        assertTrue(frm.rollbackTransaction(2));
        assertEquals("Charlie-Old", storage.data.get("customers#20"));
    }

    @Test
    void testCreateCheckpoint() {
        assertTrue(frm.createCheckpoint());
    }

    @Test
    void testRecoverToPointInTime() throws Exception {
        frm.beginTransaction(1);
        frm.writeLog(1, LogType.INSERT, "users", 1, null, "Alice");
        storage.data.put("users#1", "Alice");
        frm.commitTransaction(1);

        long cutoff = System.currentTimeMillis();
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
        }

        frm.beginTransaction(2);
        frm.writeLog(2, LogType.UPDATE, "users", 1, "Alice", "Alice-Modified");
        storage.data.put("users#1", "Alice-Modified");
        frm.commitTransaction(2);

        assertTrue(frm.recoverToPointInTime(cutoff));
        assertEquals("Alice", storage.data.get("users#1"));
    }

    @Test
    void testPitrExcludesOldChangeFromTransactionCommittedAfterTarget() throws Exception {
        frm.beginTransaction(1);
        frm.writeLog(1, LogType.INSERT, "users", 1, null, "Alice");
        storage.data.put("users#1", "Alice");
        frm.commitTransaction(1);
        long targetTimestamp =
                logger.getLogsByTransaction(1).stream()
                        .filter(record -> record.getLogType() == LogType.COMMIT)
                        .findFirst()
                        .orElseThrow()
                        .getTimestamp();

        // The change itself is timestamped before the target, but its transaction commits later.
        while (System.currentTimeMillis() <= targetTimestamp) {
            Thread.sleep(1);
        }
        frm.beginTransaction(2);
        frm.writeLog(2, LogType.INSERT, targetTimestamp - 1, "users", 2, null, "Bob");
        storage.data.put("users#2", "Bob");
        frm.commitTransaction(2);

        assertTrue(frm.recoverToPointInTime(targetTimestamp));
        assertEquals("Alice", storage.data.get("users#1"));
        assertFalse(storage.data.containsKey("users#2"));
    }

    @Test
    void testCrashRecoveryAndMocks() {
        assertTrue(frm.recoverFromCrash());
        assertTrue(frm.redo());
        assertTrue(frm.undo());
    }

    @Test
    void testWalSyncForceThrough() throws Exception {
        frm.beginTransaction(1);
        long lsn = frm.writeLog(1, LogType.INSERT, "products", 2005, null, "Item1");
        assertTrue(lsn > 0);

        assertDoesNotThrow(() -> frm.forceThrough(lsn));
    }

    @Test
    void testTransactionStatus() {
        assertNull(frm.getTransactionStatus(999));

        frm.beginTransaction(300);
        assertEquals(TxStatus.ACTIVE, frm.getTransactionStatus(300));

        frm.commitTransaction(300);
        assertEquals(TxStatus.COMMITTED, frm.getTransactionStatus(300));

        frm.beginTransaction(400);
        frm.rollbackTransaction(400);
        assertEquals(TxStatus.ABORTED, frm.getTransactionStatus(400));
    }

    @Test
    void testConnectedCheckpointFlushesStorageAndPreservesFullRid() throws Exception {
        var mockCc = new org.rafendb.mock.concurrency.MockConcurrencyControl();
        int[] flushCount = {0};

        try (Logger connectedLogger = new Logger(tempDir.resolve("connected.log"));
                org.rafendb.api.storage.StorageManager realSm =
                        org.rafendb.engine.Engine.storage(
                                tempDir.resolve("data"), 4, connectedLogger)) {
            StorageRecovery countingSm =
                    (StorageRecovery)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    StorageRecovery.class.getClassLoader(),
                                    new Class<?>[] {StorageRecovery.class},
                                    (proxy, method, args) -> {
                                        if ("flush".equals(method.getName())) {
                                            flushCount[0]++;
                                        }
                                        return method.invoke(realSm, args);
                                    });

            try (FRManager connectedFrm = new FRManager(connectedLogger, countingSm, mockCc)) {
                Rid largeSlotRid = new Rid(3L, 1500);
                ChangeRecord change =
                        new ChangeRecord(
                                new ResourceId("users", largeSlotRid),
                                Optional.empty(),
                                Optional.of(
                                        "row-bytes"
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
                long lsn =
                        connectedFrm.writeLog(
                                new LogPayload(
                                        new TxId(10),
                                        org.rafendb.common.log.LogType.INSERT,
                                        System.currentTimeMillis(),
                                        Optional.of(change)));
                assertEquals(1L, lsn);
                assertEquals(largeSlotRid, connectedLogger.getLogsSorted().getFirst().getRid());

                assertTrue(connectedFrm.createCheckpoint());
                assertEquals(1, flushCount[0]);
            }
        }
    }

    @Test
    void testRealStorageRollbackAndCrashRecoveryDecodeRow() throws Exception {
        var mockCc = new org.rafendb.mock.concurrency.MockConcurrencyControl();
        Path walPath = tempDir.resolve("real-wal.log");
        Path dataDir = tempDir.resolve("real-data");

        org.rafendb.common.tuple.Row beforeRow =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "Original")));
        org.rafendb.common.tuple.Row modifiedRow =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "Modified")));
        org.rafendb.common.tuple.Row redoneRow =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "Redone")));

        Rid rid;
        try (Logger realLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager realSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, realLogger);
                FRManager realFrm = new FRManager(realLogger, realSm, mockCc)) {
            realSm.createTable(
                    new org.rafendb.common.schema.TableSchema(
                            "items",
                            java.util.List.of(
                                    new org.rafendb.common.schema.Column(
                                            "id",
                                            org.rafendb.common.type.DataType.INTEGER,
                                            0,
                                            false,
                                            true),
                                    new org.rafendb.common.schema.Column(
                                            "name",
                                            org.rafendb.common.type.DataType.VARCHAR,
                                            50,
                                            true,
                                            false))));

            // Tx 1: reserve -> writeLog -> insert & commit
            TxId tx1 = new TxId(1);
            assertTrue(realFrm.beginTransaction(tx1));
            rid = realSm.reserveRid("items", beforeRow);
            byte[] beforeBytes = realSm.encodeRow("items", beforeRow);
            long lsn1 =
                    realFrm.writeLog(
                            new LogPayload(
                                    tx1,
                                    org.rafendb.common.log.LogType.INSERT,
                                    System.currentTimeMillis(),
                                    Optional.of(
                                            new ChangeRecord(
                                                    new ResourceId("items", rid),
                                                    Optional.empty(),
                                                    Optional.of(beforeBytes)))));
            realSm.insert("items", rid, beforeRow, lsn1);
            assertTrue(realFrm.commitTransaction(tx1));

            // Tx 2: update lalu rollback -> harus memulihkan beforeRow via decodeRow
            TxId tx2 = new TxId(2);
            assertTrue(realFrm.beginTransaction(tx2));
            byte[] modifiedBytes = realSm.encodeRow("items", modifiedRow);
            long lsn2 =
                    realFrm.writeLog(
                            new LogPayload(
                                    tx2,
                                    org.rafendb.common.log.LogType.UPDATE,
                                    System.currentTimeMillis(),
                                    Optional.of(
                                            new ChangeRecord(
                                                    new ResourceId("items", rid),
                                                    Optional.of(beforeBytes),
                                                    Optional.of(modifiedBytes)))));
            realSm.update("items", rid, modifiedRow, lsn2);
            assertEquals(
                    Optional.of(modifiedRow),
                    realSm.fetch("items", rid).map(org.rafendb.common.tuple.StoredRow::row));

            assertTrue(realFrm.rollbackTransaction(tx2));
            assertEquals(
                    Optional.of(beforeRow),
                    realSm.fetch("items", rid).map(org.rafendb.common.tuple.StoredRow::row));

            // Tx 3: commit update di WAL tanpa menulis ke SM (simulasi crash sebelum flush)
            TxId tx3 = new TxId(3);
            assertTrue(realFrm.beginTransaction(tx3));
            byte[] redoneBytes = realSm.encodeRow("items", redoneRow);
            realFrm.writeLog(
                    new LogPayload(
                            tx3,
                            org.rafendb.common.log.LogType.UPDATE,
                            System.currentTimeMillis(),
                            Optional.of(
                                    new ChangeRecord(
                                            new ResourceId("items", rid),
                                            Optional.of(beforeBytes),
                                            Optional.of(redoneBytes)))));
            assertTrue(realFrm.commitTransaction(tx3));
        }

        // Restart: CrashRecoveryEngine harus me-redo Tx 3 via decodeRow
        try (Logger restartedLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager restartedSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, restartedLogger);
                FRManager restartedFrm = new FRManager(restartedLogger, restartedSm, mockCc)) {
            assertTrue(restartedFrm.recoverFromCrash());
            assertEquals(
                    Optional.of(redoneRow),
                    restartedSm.fetch("items", rid).map(org.rafendb.common.tuple.StoredRow::row));
        }
    }

    @Test
    void testNullAttributeDistinctFromMissingRecord() throws Exception {
        var mockCc = new org.rafendb.mock.concurrency.MockConcurrencyControl();
        Path walPath = tempDir.resolve("null-attr-wal.log");
        Path dataDir = tempDir.resolve("null-attr-data");

        org.rafendb.common.tuple.Row nullNameRow =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(10),
                                org.rafendb.common.type.Value.nullOf(
                                        org.rafendb.common.type.DataType.VARCHAR)));
        org.rafendb.common.tuple.Row nonNullRow =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(10),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "Present")));

        Rid rid;
        try (Logger logger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager sm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, logger);
                FRManager realFrm = new FRManager(logger, sm, mockCc)) {
            sm.createTable(
                    new org.rafendb.common.schema.TableSchema(
                            "items",
                            java.util.List.of(
                                    new org.rafendb.common.schema.Column(
                                            "id",
                                            org.rafendb.common.type.DataType.INTEGER,
                                            0,
                                            false,
                                            true),
                                    new org.rafendb.common.schema.Column(
                                            "name",
                                            org.rafendb.common.type.DataType.VARCHAR,
                                            50,
                                            true,
                                            false))));

            TxId tx1 = new TxId(1);
            assertTrue(realFrm.beginTransaction(tx1));
            rid = sm.reserveRid("items", nullNameRow);
            byte[] nullNameBytes = sm.encodeRow("items", nullNameRow);
            long lsn1 =
                    realFrm.writeLog(
                            new LogPayload(
                                    tx1,
                                    org.rafendb.common.log.LogType.INSERT,
                                    System.currentTimeMillis(),
                                    Optional.of(
                                            new ChangeRecord(
                                                    new ResourceId("items", rid),
                                                    Optional.empty(),
                                                    Optional.of(nullNameBytes)))));
            sm.insert("items", rid, nullNameRow, lsn1);
            assertTrue(realFrm.commitTransaction(tx1));

            // Record INSERT memiliki beforeValue == null (tidak ada), sedangkan afterValue != null
            // meski atribut name bernilai NULL.
            var insertRec = logger.getLogsSorted().get(1);
            assertNull(insertRec.getBeforeValue());
            assertNotNull(insertRec.getAfterValue());

            // Tx2 mengubah NULL -> "Present", lalu di-rollback -> harus kembali ke row dengan
            // atribut NULL, bukan terhapus.
            TxId tx2 = new TxId(2);
            assertTrue(realFrm.beginTransaction(tx2));
            byte[] nonNullBytes = sm.encodeRow("items", nonNullRow);
            long lsn2 =
                    realFrm.writeLog(
                            new LogPayload(
                                    tx2,
                                    org.rafendb.common.log.LogType.UPDATE,
                                    System.currentTimeMillis(),
                                    Optional.of(
                                            new ChangeRecord(
                                                    new ResourceId("items", rid),
                                                    Optional.of(nullNameBytes),
                                                    Optional.of(nonNullBytes)))));
            sm.update("items", rid, nonNullRow, lsn2);
            assertTrue(realFrm.rollbackTransaction(tx2));
            assertEquals(
                    Optional.of(nullNameRow),
                    sm.fetch("items", rid).map(org.rafendb.common.tuple.StoredRow::row));
        }
    }

    @Test
    void testRealProcessCrashRecoverySimulation() throws Exception {
        Path walPath = tempDir.resolve("proc-crash-wal.log");
        Path dataDir = tempDir.resolve("proc-crash-data");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb =
                new ProcessBuilder(
                        javaBin,
                        "-cp",
                        classpath,
                        org.rafendb.recovery.driver.FRManagerDriver.class.getName(),
                        "--simulate-crash-child",
                        dataDir.toString(),
                        walPath.toString());
        pb.redirectErrorStream(true);
        Process child = pb.start();

        String readyLine = null;
        try (var reader =
                new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("READY_FOR_CRASH:")) {
                    readyLine = line;
                    break;
                }
            }
        } finally {
            child.destroyForcibly();
            child.waitFor();
        }
        assertNotNull(readyLine, "Child process must reach READY_FOR_CRASH before forcible kill");

        String[] parts = readyLine.substring("READY_FOR_CRASH:".length()).split(";");
        String[] r1Parts = parts[0].split(",");
        String[] r2Parts = parts[1].split(",");
        Rid committedRid = new Rid(Long.parseLong(r1Parts[0]), Integer.parseInt(r1Parts[1]));
        Rid uncommittedRid = new Rid(Long.parseLong(r2Parts[0]), Integer.parseInt(r2Parts[1]));

        var mockCc = new org.rafendb.mock.concurrency.MockConcurrencyControl();
        try (Logger restartedLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager restartedSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, restartedLogger);
                FRManager restartedFrm = new FRManager(restartedLogger, restartedSm, mockCc)) {
            assertTrue(restartedFrm.recoverFromCrash());
            assertTrue(restartedSm.fetch("items", committedRid).isPresent());
            assertTrue(restartedSm.fetch("items", uncommittedRid).isEmpty());
        }
    }

    @Test
    void testDropAndRecreateTableSkipsOldGenerationOnRecovery() throws Exception {
        var mockCc = new org.rafendb.mock.concurrency.MockConcurrencyControl();
        Path walPath = tempDir.resolve("gen-wal.log");
        Path dataDir = tempDir.resolve("gen-data");

        org.rafendb.common.tuple.Row oldRow =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "OldGen")));
        org.rafendb.common.tuple.Row newRow =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(99),
                                org.rafendb.common.type.Value.ofFloat(3.5f)));

        Rid newRid;
        try (Logger logger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager sm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, logger);
                FRManager realFrm = new FRManager(logger, sm, mockCc)) {
            sm.createTable(
                    new org.rafendb.common.schema.TableSchema(
                            "events",
                            java.util.List.of(
                                    new org.rafendb.common.schema.Column(
                                            "id",
                                            org.rafendb.common.type.DataType.INTEGER,
                                            0,
                                            false,
                                            true),
                                    new org.rafendb.common.schema.Column(
                                            "label",
                                            org.rafendb.common.type.DataType.VARCHAR,
                                            30,
                                            true,
                                            false))));

            TxId tx1 = new TxId(1);
            assertTrue(realFrm.beginTransaction(tx1));
            Rid oldRid = sm.reserveRid("events", oldRow);
            long lsn1 =
                    realFrm.writeLog(
                            new LogPayload(
                                    tx1,
                                    org.rafendb.common.log.LogType.INSERT,
                                    System.currentTimeMillis(),
                                    Optional.of(
                                            new ChangeRecord(
                                                    new ResourceId("events", oldRid),
                                                    Optional.empty(),
                                                    Optional.of(sm.encodeRow("events", oldRow))))));
            sm.insert("events", oldRid, oldRow, lsn1);
            assertTrue(realFrm.commitTransaction(tx1));
            assertNotEquals(0L, logger.getLogsSorted().get(1).getTableGeneration());

            // DROP lalu CREATE ulang tabel dengan nama sama tetapi skema berbeda
            sm.dropTable("events", false);
            sm.createTable(
                    new org.rafendb.common.schema.TableSchema(
                            "events",
                            java.util.List.of(
                                    new org.rafendb.common.schema.Column(
                                            "id",
                                            org.rafendb.common.type.DataType.INTEGER,
                                            0,
                                            false,
                                            true),
                                    new org.rafendb.common.schema.Column(
                                            "score",
                                            org.rafendb.common.type.DataType.FLOAT,
                                            0,
                                            false,
                                            false))));

            TxId tx2 = new TxId(2);
            assertTrue(realFrm.beginTransaction(tx2));
            newRid = sm.reserveRid("events", newRow);
            long lsn2 =
                    realFrm.writeLog(
                            new LogPayload(
                                    tx2,
                                    org.rafendb.common.log.LogType.INSERT,
                                    System.currentTimeMillis(),
                                    Optional.of(
                                            new ChangeRecord(
                                                    new ResourceId("events", newRid),
                                                    Optional.empty(),
                                                    Optional.of(sm.encodeRow("events", newRow))))));
            sm.insert("events", newRid, newRow, lsn2);
            assertTrue(realFrm.commitTransaction(tx2));
        }

        try (Logger restartedLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager restartedSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, restartedLogger);
                FRManager restartedFrm = new FRManager(restartedLogger, restartedSm, mockCc)) {
            assertTrue(restartedFrm.recoverFromCrash());
            assertEquals(
                    Optional.of(newRow),
                    restartedSm
                            .fetch("events", newRid)
                            .map(org.rafendb.common.tuple.StoredRow::row));
            int count = 0;
            try (var cursor = restartedSm.scan("events")) {
                while (cursor.next() != null) {
                    count++;
                }
            }
            assertEquals(1, count);
        }
    }

    @Test
    void testLoadSampleDatabaseWithFrmLoggingCallbackAndRecovery() throws Exception {
        var mockCc = new org.rafendb.mock.concurrency.MockConcurrencyControl();
        Path walPath = tempDir.resolve("sample-wal.log");
        Path dataDir = tempDir.resolve("sample-data");

        long customerCountBefore;
        try (Logger logger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager sm =
                        org.rafendb.engine.Engine.storage(dataDir, 16, logger);
                FRManager realFrm = new FRManager(logger, sm, mockCc)) {
            TxId tx1 = new TxId(1);
            assertTrue(realFrm.beginTransaction(tx1));
            sm.loadSampleDatabase(
                    change -> {
                        try {
                            return realFrm.writeLog(
                                    new LogPayload(
                                            tx1,
                                            org.rafendb.common.log.LogType.INSERT,
                                            System.currentTimeMillis(),
                                            Optional.of(change)));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            assertTrue(realFrm.commitTransaction(tx1));

            assertTrue(
                    logger.getLogsSorted().stream()
                            .anyMatch(
                                    r ->
                                            r.getLogType() == LogType.INSERT
                                                    && r.getTableGeneration() != 0L));

            customerCountBefore = 0;
            try (var cursor = sm.scan("customers")) {
                while (cursor.next() != null) {
                    customerCountBefore++;
                }
            }
            assertTrue(customerCountBefore >= 50);
        }

        try (Logger restartedLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager restartedSm =
                        org.rafendb.engine.Engine.storage(dataDir, 16, restartedLogger);
                FRManager restartedFrm = new FRManager(restartedLogger, restartedSm, mockCc)) {
            assertTrue(restartedFrm.recoverFromCrash());
            long customerCountAfter = 0;
            try (var cursor = restartedSm.scan("customers")) {
                while (cursor.next() != null) {
                    customerCountAfter++;
                }
            }
            assertEquals(customerCountBefore, customerCountAfter);
        }
    }

    @Test
    void testRecoverToTransactionFacade() throws Exception {
        frm.beginTransaction(1);
        frm.writeLog(1, LogType.INSERT, "users", 1, null, "V1");
        storage.data.put("users#1", "V1");
        frm.commitTransaction(1);

        frm.beginTransaction(2);
        frm.writeLog(2, LogType.UPDATE, "users", 1, "V1", "V2");
        storage.data.put("users#1", "V2");
        frm.commitTransaction(2);

        assertTrue(frm.recoverToTransaction(new TxId(1)));
        assertEquals("V1", storage.data.get("users#1"));
        assertFalse(frm.recoverToTransaction(9999));
    }
}
