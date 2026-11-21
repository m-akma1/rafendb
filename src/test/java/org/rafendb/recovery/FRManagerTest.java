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
        assertTrue(frm.createCheckpoint(new org.rafendb.mock.concurrency.MockConcurrencyControl()));
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

            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager connectedFrm = new FRManager(connectedLogger, countingSm);
            Rid largeSlotRid = new Rid(3L, 1500);
            ChangeRecord change =
                    new ChangeRecord(
                            new ResourceId("users", largeSlotRid),
                            Optional.empty(),
                            Optional.of(
                                    "row-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            long lsn =
                    connectedFrm.writeLog(
                            new LogPayload(
                                    new TxId(10),
                                    org.rafendb.common.log.LogType.INSERT,
                                    System.currentTimeMillis(),
                                    Optional.of(change)));
            assertEquals(1L, lsn);
            assertEquals(largeSlotRid, connectedLogger.getLogsSorted().getFirst().getRid());

            assertTrue(connectedFrm.createCheckpoint(mockCc));
            assertEquals(1, flushCount[0]);
        }
    }

    @Test
    void testRealStorageRollbackAndCrashRecoveryDecodeRow() throws Exception {
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
                        org.rafendb.engine.Engine.storage(dataDir, 8, realLogger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager realFrm = new FRManager(realLogger, realSm);
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
                        org.rafendb.engine.Engine.storage(dataDir, 8, restartedLogger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager restartedFrm = new FRManager(restartedLogger, restartedSm);
            assertTrue(restartedFrm.recoverFromCrash());
            assertEquals(
                    Optional.of(redoneRow),
                    restartedSm.fetch("items", rid).map(org.rafendb.common.tuple.StoredRow::row));
        }
    }

    @Test
    void testNullAttributeDistinctFromMissingRecord() throws Exception {
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
                        org.rafendb.engine.Engine.storage(dataDir, 8, logger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager realFrm = new FRManager(logger, sm);
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
                        org.rafendb.recovery.driver.RecoveryDriver.class.getName(),
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

        try (Logger restartedLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager restartedSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, restartedLogger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager restartedFrm = new FRManager(restartedLogger, restartedSm);
            assertTrue(restartedFrm.recoverFromCrash());
            assertTrue(restartedSm.fetch("items", committedRid).isPresent());
            assertTrue(restartedSm.fetch("items", uncommittedRid).isEmpty());
        }
    }

    @Test
    void testDropAndRecreateTableSkipsOldGenerationOnRecovery() throws Exception {
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
                        org.rafendb.engine.Engine.storage(dataDir, 8, logger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager realFrm = new FRManager(logger, sm);
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
                        org.rafendb.engine.Engine.storage(dataDir, 8, restartedLogger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager restartedFrm = new FRManager(restartedLogger, restartedSm);
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
        Path walPath = tempDir.resolve("sample-wal.log");
        Path dataDir = tempDir.resolve("sample-data");

        long customerCountBefore;
        try (Logger logger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager sm =
                        org.rafendb.engine.Engine.storage(dataDir, 16, logger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager realFrm = new FRManager(logger, sm);
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
                        org.rafendb.engine.Engine.storage(dataDir, 16, restartedLogger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager restartedFrm = new FRManager(restartedLogger, restartedSm);
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

    @Test
    void testIndexConsistencyAfterCrashRecoveryWithRealStorage() throws Exception {
        Path walPath = tempDir.resolve("idx-wal.log");
        Path dataDir = tempDir.resolve("idx-data");

        org.rafendb.common.tuple.Row row1 =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "SKU-A"),
                                org.rafendb.common.type.Value.ofInt(100)));
        org.rafendb.common.tuple.Row row1Redone =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "SKU-A"),
                                org.rafendb.common.type.Value.ofInt(150)));
        org.rafendb.common.tuple.Row row2Uncommitted =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(2),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "SKU-B"),
                                org.rafendb.common.type.Value.ofInt(250)));

        Rid rid1;
        try (Logger logger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager sm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, logger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager realFrm = new FRManager(logger, sm);
            sm.createTable(
                    new org.rafendb.common.schema.TableSchema(
                            "products",
                            java.util.List.of(
                                    new org.rafendb.common.schema.Column(
                                            "id",
                                            org.rafendb.common.type.DataType.INTEGER,
                                            0,
                                            false,
                                            true),
                                    new org.rafendb.common.schema.Column(
                                            "sku",
                                            org.rafendb.common.type.DataType.VARCHAR,
                                            20,
                                            false,
                                            false),
                                    new org.rafendb.common.schema.Column(
                                            "price",
                                            org.rafendb.common.type.DataType.INTEGER,
                                            0,
                                            false,
                                            false))));
            sm.createIndex(
                    new org.rafendb.common.schema.IndexInfo(
                            "idx_sku",
                            "products",
                            "sku",
                            org.rafendb.common.schema.IndexType.HASH));
            sm.createIndex(
                    new org.rafendb.common.schema.IndexInfo(
                            "idx_price",
                            "products",
                            "price",
                            org.rafendb.common.schema.IndexType.B_PLUS_TREE));

            // Tx1: INSERT row1 -> SM + WAL & COMMIT
            TxId tx1 = new TxId(1);
            assertTrue(realFrm.beginTransaction(tx1));
            rid1 = sm.reserveRid("products", row1);
            byte[] row1Bytes = sm.encodeRow("products", row1);
            long lsn1 =
                    realFrm.writeLog(
                            new LogPayload(
                                    tx1,
                                    org.rafendb.common.log.LogType.INSERT,
                                    System.currentTimeMillis(),
                                    Optional.of(
                                            new ChangeRecord(
                                                    new ResourceId("products", rid1),
                                                    Optional.empty(),
                                                    Optional.of(row1Bytes)))));
            sm.insert("products", rid1, row1, lsn1);
            assertTrue(realFrm.commitTransaction(tx1));

            // Tx2: UPDATE harga 100 -> 150 di WAL & COMMIT tanpa menulis ke SM (REDO)
            TxId tx2 = new TxId(2);
            assertTrue(realFrm.beginTransaction(tx2));
            realFrm.writeLog(
                    new LogPayload(
                            tx2,
                            org.rafendb.common.log.LogType.UPDATE,
                            System.currentTimeMillis(),
                            Optional.of(
                                    new ChangeRecord(
                                            new ResourceId("products", rid1),
                                            Optional.of(row1Bytes),
                                            Optional.of(sm.encodeRow("products", row1Redone))))));
            assertTrue(realFrm.commitTransaction(tx2));

            // Tx3: INSERT row2Uncommitted ke SM & WAL tanpa COMMIT (UNDO)
            TxId tx3 = new TxId(3);
            assertTrue(realFrm.beginTransaction(tx3));
            Rid rid2 = sm.reserveRid("products", row2Uncommitted);
            long lsn3 =
                    realFrm.writeLog(
                            new LogPayload(
                                    tx3,
                                    org.rafendb.common.log.LogType.INSERT,
                                    System.currentTimeMillis(),
                                    Optional.of(
                                            new ChangeRecord(
                                                    new ResourceId("products", rid2),
                                                    Optional.empty(),
                                                    Optional.of(
                                                            sm.encodeRow(
                                                                    "products",
                                                                    row2Uncommitted))))));
            sm.insert("products", rid2, row2Uncommitted, lsn3);
        }

        try (Logger restartedLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager restartedSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, restartedLogger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager restartedFrm = new FRManager(restartedLogger, restartedSm);
            assertTrue(restartedFrm.recoverFromCrash());
            assertEquals(
                    java.util.List.of(rid1),
                    restartedSm.lookupIndex(
                            "idx_sku",
                            org.rafendb.common.type.Value.ofText(
                                    org.rafendb.common.type.DataType.VARCHAR, "SKU-A")));
            assertTrue(
                    restartedSm
                            .lookupIndex(
                                    "idx_sku",
                                    org.rafendb.common.type.Value.ofText(
                                            org.rafendb.common.type.DataType.VARCHAR, "SKU-B"))
                            .isEmpty());
            assertEquals(
                    java.util.List.of(rid1),
                    restartedSm.rangeIndex(
                            "idx_price",
                            org.rafendb.common.type.Value.ofInt(140),
                            true,
                            org.rafendb.common.type.Value.ofInt(160),
                            true));
            assertTrue(
                    restartedSm
                            .rangeIndex(
                                    "idx_price",
                                    org.rafendb.common.type.Value.ofInt(90),
                                    true,
                                    org.rafendb.common.type.Value.ofInt(110),
                                    true)
                            .isEmpty());
            assertTrue(
                    restartedSm
                            .rangeIndex(
                                    "idx_price",
                                    org.rafendb.common.type.Value.ofInt(240),
                                    true,
                                    org.rafendb.common.type.Value.ofInt(260),
                                    true)
                            .isEmpty());
        }
    }

    @Test
    void testPitrWithRealStorageSurvivesRestart() throws Exception {
        Path walPath = tempDir.resolve("pitr-real-wal.log");
        Path dataDir = tempDir.resolve("pitr-real-data");

        org.rafendb.common.tuple.Row v1Row =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "V1")));
        org.rafendb.common.tuple.Row v2Row =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "V2")));
        org.rafendb.common.tuple.Row v3Row =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(2),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "V3")));

        Rid rid1;
        Rid rid2;
        try (Logger logger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager sm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, logger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager realFrm = new FRManager(logger, sm);
            sm.createTable(
                    new org.rafendb.common.schema.TableSchema(
                            "docs",
                            java.util.List.of(
                                    new org.rafendb.common.schema.Column(
                                            "id",
                                            org.rafendb.common.type.DataType.INTEGER,
                                            0,
                                            false,
                                            true),
                                    new org.rafendb.common.schema.Column(
                                            "title",
                                            org.rafendb.common.type.DataType.VARCHAR,
                                            30,
                                            false,
                                            false))));

            TxId tx1 = new TxId(1);
            assertTrue(realFrm.beginTransaction(tx1));
            rid1 = sm.reserveRid("docs", v1Row);
            byte[] v1Bytes = sm.encodeRow("docs", v1Row);
            long lsn1 =
                    realFrm.writeLog(
                            new LogPayload(
                                    tx1,
                                    org.rafendb.common.log.LogType.INSERT,
                                    System.currentTimeMillis(),
                                    Optional.of(
                                            new ChangeRecord(
                                                    new ResourceId("docs", rid1),
                                                    Optional.empty(),
                                                    Optional.of(v1Bytes)))));
            sm.insert("docs", rid1, v1Row, lsn1);
            assertTrue(realFrm.commitTransaction(tx1));

            Thread.sleep(15);

            TxId tx2 = new TxId(2);
            assertTrue(realFrm.beginTransaction(tx2));
            long lsn2 =
                    realFrm.writeLog(
                            new LogPayload(
                                    tx2,
                                    org.rafendb.common.log.LogType.UPDATE,
                                    System.currentTimeMillis(),
                                    Optional.of(
                                            new ChangeRecord(
                                                    new ResourceId("docs", rid1),
                                                    Optional.of(v1Bytes),
                                                    Optional.of(sm.encodeRow("docs", v2Row))))));
            sm.update("docs", rid1, v2Row, lsn2);
            assertTrue(realFrm.commitTransaction(tx2));

            Thread.sleep(15);

            TxId tx3 = new TxId(3);
            assertTrue(realFrm.beginTransaction(tx3));
            rid2 = sm.reserveRid("docs", v3Row);
            long lsn3 =
                    realFrm.writeLog(
                            new LogPayload(
                                    tx3,
                                    org.rafendb.common.log.LogType.INSERT,
                                    System.currentTimeMillis(),
                                    Optional.of(
                                            new ChangeRecord(
                                                    new ResourceId("docs", rid2),
                                                    Optional.empty(),
                                                    Optional.of(sm.encodeRow("docs", v3Row))))));
            sm.insert("docs", rid2, v3Row, lsn3);
            assertTrue(realFrm.commitTransaction(tx3));

            assertFalse(realFrm.recoverToTransaction(9999));
            assertEquals(
                    Optional.of(v2Row),
                    sm.fetch("docs", rid1).map(org.rafendb.common.tuple.StoredRow::row));
            assertEquals(
                    Optional.of(v3Row),
                    sm.fetch("docs", rid2).map(org.rafendb.common.tuple.StoredRow::row));

            assertTrue(realFrm.recoverToTransaction(tx1));
            assertEquals(
                    Optional.of(v1Row),
                    sm.fetch("docs", rid1).map(org.rafendb.common.tuple.StoredRow::row));
            assertTrue(sm.fetch("docs", rid2).isEmpty());
        }

        try (Logger restartedLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager restartedSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, restartedLogger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager restartedFrm = new FRManager(restartedLogger, restartedSm);
            assertTrue(restartedFrm.recoverFromCrash());
            assertEquals(
                    Optional.of(v1Row),
                    restartedSm.fetch("docs", rid1).map(org.rafendb.common.tuple.StoredRow::row));
            assertTrue(restartedSm.fetch("docs", rid2).isEmpty());
        }
    }

    @Test
    void testRealThreeWayIntegrationWithCcmAndStorage() throws Exception {
        Path walPath = tempDir.resolve("three-way-wal.log");
        Path dataDir = tempDir.resolve("three-way-data");

        org.rafendb.common.tuple.Row row1 =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "Committed")));
        org.rafendb.common.tuple.Row row2 =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(2),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "Aborted")));

        try (Logger logger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager sm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, logger)) {
            FRManager realFrm = new FRManager(logger, sm);
            assertTrue(realFrm.recoverFromCrash());

            var ccm =
                    new org.rafendb.concurrency.ConcurrencyControlManager(
                            new org.rafendb.concurrency.txn.TransactionManager(),
                            new org.rafendb.concurrency.protocol.TimestampBasedProtocol(),
                            realFrm);
            ccm.initializeAfterRecovery(realFrm.getRecoveryInfo());

            sm.createTable(
                    new org.rafendb.common.schema.TableSchema(
                            "accounts",
                            java.util.List.of(
                                    new org.rafendb.common.schema.Column(
                                            "id",
                                            org.rafendb.common.type.DataType.INTEGER,
                                            0,
                                            false,
                                            true),
                                    new org.rafendb.common.schema.Column(
                                            "owner",
                                            org.rafendb.common.type.DataType.VARCHAR,
                                            40,
                                            false,
                                            false))));

            // 1. Alur COMMIT melalui CCM -> FrmBridge -> FRManager -> StorageManager
            TxId t1 = ccm.begin();
            assertEquals(
                    org.rafendb.common.txn.AccessResult.Status.ALLOWED,
                    ccm.requestAccess(
                                    t1,
                                    org.rafendb.common.txn.AccessType.WRITE,
                                    ResourceId.ofTable("accounts"))
                            .status());
            Rid rid1 = sm.reserveRid("accounts", row1);
            long lsn1 =
                    ccm.logChange(
                            t1,
                            new ChangeRecord(
                                    ResourceId.ofRow("accounts", rid1),
                                    Optional.empty(),
                                    Optional.of(sm.encodeRow("accounts", row1))));
            sm.insert("accounts", rid1, row1, lsn1);
            assertEquals(TxStatus.COMMITTED, ccm.commit(t1).status());

            // 2a. Explicit abort lewat CCM -> FrmBridge.abort -> FRManager.rollback ->
            // SM.applyRecovery
            TxId t2 = ccm.begin();
            assertEquals(
                    org.rafendb.common.txn.AccessResult.Status.ALLOWED,
                    ccm.requestAccess(
                                    t2,
                                    org.rafendb.common.txn.AccessType.WRITE,
                                    ResourceId.ofTable("accounts"))
                            .status());
            Rid rid2 = sm.reserveRid("accounts", row2);
            long lsn2 =
                    ccm.logChange(
                            t2,
                            new ChangeRecord(
                                    ResourceId.ofRow("accounts", rid2),
                                    Optional.empty(),
                                    Optional.of(sm.encodeRow("accounts", row2))));
            sm.insert("accounts", rid2, row2, lsn2);
            assertEquals(TxStatus.ABORTED, ccm.abort(t2).status());
            assertTrue(sm.fetch("accounts", rid2).isEmpty());

            // 2b. Konflik timestamp-order -> StrictTimestampProtocol menolak & membatalkan tOld
            TxId tOld = ccm.begin();
            TxId tNew = ccm.begin();
            assertEquals(
                    org.rafendb.common.txn.AccessResult.Status.ALLOWED,
                    ccm.requestAccess(
                                    tNew,
                                    org.rafendb.common.txn.AccessType.WRITE,
                                    ResourceId.ofTable("accounts"))
                            .status());
            assertEquals(TxStatus.COMMITTED, ccm.commit(tNew).status());
            assertEquals(
                    org.rafendb.common.txn.AccessResult.Status.ABORTED,
                    ccm.requestAccess(
                                    tOld,
                                    org.rafendb.common.txn.AccessType.WRITE,
                                    ResourceId.ofTable("accounts"))
                            .status());

            // 3. Quiescent checkpoint dengan meneruskan CCM ke createCheckpoint + SM.flush()
            assertTrue(realFrm.createCheckpoint(ccm));
            assertTrue(
                    logger.getLogsSorted().stream()
                            .anyMatch(r -> r.getLogType() == LogType.CHECKPOINT_BEGIN));
            assertTrue(
                    logger.getLogsSorted().stream()
                            .anyMatch(r -> r.getLogType() == LogType.CHECKPOINT_END));
            assertEquals(
                    Optional.of(row1),
                    sm.fetch("accounts", rid1).map(org.rafendb.common.tuple.StoredRow::row));
        }
    }

    @Test
    void testRecoveryInfoLogicalHighWaterAfterRestartWithCcm() throws Exception {
        Path walPath = tempDir.resolve("recovery-info-wal.log");
        Path dataDir = tempDir.resolve("recovery-info-data");

        org.rafendb.common.tuple.Row row1 =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "One")));
        org.rafendb.common.tuple.Row row2 =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(2),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "Two")));
        org.rafendb.common.tuple.Row row3Uncommitted =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(3),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "Three")));

        Rid rid1;
        Rid rid2;
        try (Logger logger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager sm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, logger)) {
            FRManager realFrm = new FRManager(logger, sm);
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
                                            "label",
                                            org.rafendb.common.type.DataType.VARCHAR,
                                            40,
                                            false,
                                            false))));

            var ccm1 =
                    new org.rafendb.concurrency.ConcurrencyControlManager(
                            new org.rafendb.concurrency.txn.TransactionManager(),
                            new org.rafendb.concurrency.protocol.TimestampBasedProtocol(),
                            realFrm);
            ccm1.initializeAfterRecovery(realFrm.getRecoveryInfo());

            TxId t1 = ccm1.begin();
            assertEquals(1L, t1.value());
            assertEquals(
                    org.rafendb.common.txn.AccessResult.Status.ALLOWED,
                    ccm1.requestAccess(
                                    t1,
                                    org.rafendb.common.txn.AccessType.WRITE,
                                    ResourceId.ofTable("items"))
                            .status());
            rid1 = sm.reserveRid("items", row1);
            long lsn1 =
                    ccm1.logChange(
                            t1,
                            new ChangeRecord(
                                    ResourceId.ofRow("items", rid1),
                                    Optional.empty(),
                                    Optional.of(sm.encodeRow("items", row1))));
            sm.insert("items", rid1, row1, lsn1);
            assertEquals(TxStatus.COMMITTED, ccm1.commit(t1).status());

            TxId t2 = ccm1.begin();
            assertEquals(2L, t2.value());
            assertEquals(
                    org.rafendb.common.txn.AccessResult.Status.ALLOWED,
                    ccm1.requestAccess(
                                    t2,
                                    org.rafendb.common.txn.AccessType.WRITE,
                                    ResourceId.ofTable("items"))
                            .status());
            rid2 = sm.reserveRid("items", row2);
            long lsn2 =
                    ccm1.logChange(
                            t2,
                            new ChangeRecord(
                                    ResourceId.ofRow("items", rid2),
                                    Optional.empty(),
                                    Optional.of(sm.encodeRow("items", row2))));
            sm.insert("items", rid2, row2, lsn2);
            assertEquals(TxStatus.COMMITTED, ccm1.commit(t2).status());
        }

        Rid rid3;
        try (Logger restartedLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager restartedSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, restartedLogger)) {
            FRManager restartedFrm = new FRManager(restartedLogger, restartedSm);
            assertTrue(restartedFrm.recoverFromCrash());

            var info = restartedFrm.getRecoveryInfo();
            assertEquals(2L, info.maxTransactionId());
            assertEquals(2L, info.maxTimestamp());

            var ccm2 =
                    new org.rafendb.concurrency.ConcurrencyControlManager(
                            new org.rafendb.concurrency.txn.TransactionManager(),
                            new org.rafendb.concurrency.protocol.TimestampBasedProtocol(),
                            restartedFrm);
            ccm2.initializeAfterRecovery(info);

            TxId t3 = ccm2.begin();
            assertEquals(3L, t3.value());
            assertEquals(
                    org.rafendb.common.txn.AccessResult.Status.ALLOWED,
                    ccm2.requestAccess(
                                    t3,
                                    org.rafendb.common.txn.AccessType.WRITE,
                                    ResourceId.ofTable("items"))
                            .status());
            rid3 = restartedSm.reserveRid("items", row3Uncommitted);
            long lsn3 =
                    ccm2.logChange(
                            t3,
                            new ChangeRecord(
                                    ResourceId.ofRow("items", rid3),
                                    Optional.empty(),
                                    Optional.of(restartedSm.encodeRow("items", row3Uncommitted))));
            restartedSm.insert("items", rid3, row3Uncommitted, lsn3);
        }

        try (Logger finalLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager finalSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, finalLogger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager finalFrm = new FRManager(finalLogger, finalSm);
            assertTrue(finalFrm.recoverFromCrash());
            assertEquals(
                    Optional.of(row1),
                    finalSm.fetch("items", rid1).map(org.rafendb.common.tuple.StoredRow::row));
            assertEquals(
                    Optional.of(row2),
                    finalSm.fetch("items", rid2).map(org.rafendb.common.tuple.StoredRow::row));
            assertTrue(finalSm.fetch("items", rid3).isEmpty());
            assertEquals(TxStatus.ABORTED, finalFrm.getTransactionStatus(new TxId(3)));
        }
    }

    @Test
    void testOverflowTxIdRejected() {
        TxId overflow = new TxId((long) Integer.MAX_VALUE + 1L);
        assertThrows(IllegalArgumentException.class, () -> frm.beginTransaction(overflow));
        assertThrows(IllegalArgumentException.class, () -> frm.commitTransaction(overflow));
        assertThrows(IllegalArgumentException.class, () -> frm.rollbackTransaction(overflow));
        assertThrows(IllegalArgumentException.class, () -> frm.getTransactionStatus(overflow));
        assertThrows(IllegalArgumentException.class, () -> frm.recoverToTransaction(overflow));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        frm.writeLog(
                                LogPayload.ofEvent(
                                        overflow, org.rafendb.common.log.LogType.BEGIN)));
    }

    @Test
    void testTransactionStatusUpdatedAfterCrashUndoAndWriteLogEvent() throws Exception {
        assertTrue(frm.beginTransaction(10));
        assertEquals(TxStatus.ACTIVE, frm.getTransactionStatus(10));

        assertTrue(frm.recoverFromCrash());
        assertEquals(TxStatus.ABORTED, frm.getTransactionStatus(10));

        long customTs = 123456789L;
        TxId tx11 = new TxId(11);
        long beginLsn =
                frm.writeLog(
                        new LogPayload(
                                tx11,
                                org.rafendb.common.log.LogType.BEGIN,
                                customTs,
                                Optional.empty()));
        assertEquals(TxStatus.ACTIVE, frm.getTransactionStatus(tx11));

        long commitLsn =
                frm.writeLog(
                        new LogPayload(
                                tx11,
                                org.rafendb.common.log.LogType.COMMIT,
                                customTs + 5,
                                Optional.empty()));
        assertTrue(commitLsn > beginLsn);
        assertEquals(TxStatus.COMMITTED, frm.getTransactionStatus(tx11));

        var tx11Logs = logger.getLogsByTransaction(11);
        assertEquals(2, tx11Logs.size());
        assertEquals(customTs, tx11Logs.get(0).getTimestamp());
        assertEquals(customTs + 5, tx11Logs.get(1).getTimestamp());
    }

    @Test
    void testConcurrentWriteLogEventReturnsUniqueExactLsns() throws Exception {
        Path walPath = tempDir.resolve("concurrent-event.log");
        int threads = 8;
        int perThread = 20;
        java.util.Set<Long> lsns = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (Logger concurrentLogger = new Logger(walPath);
                FRManager concurrentFrm = new FRManager(concurrentLogger, new TestStorage())) {
            java.util.concurrent.CountDownLatch startGate =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneGate =
                    new java.util.concurrent.CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final int baseTx = (t + 1) * 1000;
                new Thread(
                                () -> {
                                    try {
                                        startGate.await();
                                        for (int i = 0; i < perThread; i++) {
                                            long lsn =
                                                    concurrentFrm.writeLog(
                                                            LogPayload.ofEvent(
                                                                    new TxId(baseTx + i),
                                                                    org.rafendb.common.log.LogType
                                                                            .BEGIN));
                                            lsns.add(lsn);
                                        }
                                    } catch (Throwable ex) {
                                        errors.add(ex);
                                    } finally {
                                        doneGate.countDown();
                                    }
                                })
                        .start();
            }

            startGate.countDown();
            assertTrue(doneGate.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(errors.isEmpty(), () -> "Unexpected errors: " + errors);
            assertEquals(threads * perThread, lsns.size());
            assertEquals(threads * perThread, concurrentLogger.getLogsSorted().size());
        }
    }

    @Test
    void testUndoStorageConstructorAutoDetectsStorageManagerAdapter() throws Exception {
        Path walPath = tempDir.resolve("adapter-ctor-wal.log");
        Path dataDir = tempDir.resolve("adapter-ctor-data");

        org.rafendb.common.tuple.Row r1 =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "Keep")));
        org.rafendb.common.tuple.Row r2 =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(2),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "Rollback")));

        Rid rid1;
        Rid rid2;
        try (Logger realLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager realSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, realLogger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager adapterFrm = new FRManager(realLogger, FRManager.storageAdapter(realSm));
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
                                            30,
                                            false,
                                            false))));

            TxId tx1 = new TxId(1);
            assertTrue(adapterFrm.beginTransaction(tx1));
            rid1 = realSm.reserveRid("items", r1);
            adapterFrm.writeLog(
                    LogPayload.ofChange(
                            tx1,
                            org.rafendb.common.log.LogType.INSERT,
                            new ChangeRecord(
                                    ResourceId.ofRow("items", rid1),
                                    Optional.empty(),
                                    Optional.of(realSm.encodeRow("items", r1)))));
            assertTrue(adapterFrm.commitTransaction(tx1));

            assertNotEquals(0L, realLogger.getLogsSorted().get(1).getTableGeneration());

            TxId tx2 = new TxId(2);
            assertTrue(adapterFrm.beginTransaction(tx2));
            rid2 = realSm.reserveRid("items", r2);
            long lsn2 =
                    adapterFrm.writeLog(
                            LogPayload.ofChange(
                                    tx2,
                                    org.rafendb.common.log.LogType.INSERT,
                                    new ChangeRecord(
                                            ResourceId.ofRow("items", rid2),
                                            Optional.empty(),
                                            Optional.of(realSm.encodeRow("items", r2)))));
            realSm.insert("items", rid2, r2, lsn2);
            assertTrue(adapterFrm.rollbackTransaction(tx2));
            assertTrue(realSm.fetch("items", rid2).isEmpty());
        }

        try (Logger restartedLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager restartedSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, restartedLogger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager restartedFrm =
                    new FRManager(restartedLogger, FRManager.storageAdapter(restartedSm));
            assertTrue(restartedFrm.recoverFromCrash());
            assertEquals(
                    Optional.of(r1),
                    restartedSm.fetch("items", rid1).map(org.rafendb.common.tuple.StoredRow::row));
            assertTrue(restartedSm.fetch("items", rid2).isEmpty());
        }
    }

    @Test
    void testUnnormalizedTableNameResolvesCanonicalTableAndRecovers() throws Exception {
        Path walPath = tempDir.resolve("case-wal.log");
        Path dataDir = tempDir.resolve("case-data");

        org.rafendb.common.tuple.Row row =
                new org.rafendb.common.tuple.Row(
                        java.util.List.of(
                                org.rafendb.common.type.Value.ofInt(1),
                                org.rafendb.common.type.Value.ofText(
                                        org.rafendb.common.type.DataType.VARCHAR, "MixedCase")));

        Rid rid;
        try (Logger realLogger = new Logger(walPath);
                org.rafendb.api.storage.StorageManager realSm =
                        org.rafendb.engine.Engine.storage(dataDir, 8, realLogger)) {
            @SuppressWarnings("resource") // closes only its Logger, which the try owns
            FRManager realFrm = new FRManager(realLogger, realSm);
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
                                            30,
                                            false,
                                            false))));

            TxId tx1 = new TxId(1);
            assertTrue(realFrm.beginTransaction(tx1));
            rid = realSm.reserveRid("items", row);
            byte[] encoded = realSm.encodeRow("items", row);
            long lsn1 =
                    realFrm.writeLog(
                            LogPayload.ofChange(
                                    tx1,
                                    org.rafendb.common.log.LogType.INSERT,
                                    new ChangeRecord(
                                            ResourceId.ofRow(" Items ", rid),
                                            Optional.empty(),
                                            Optional.of(encoded))));
            assertTrue(lsn1 > 0);
            assertEquals("items", realLogger.getLogsSorted().get(1).getTableName());
            assertNotEquals(0L, realLogger.getLogsSorted().get(1).getTableGeneration());
            realSm.insert("items", rid, row, lsn1);
            assertTrue(realFrm.rollbackTransaction(tx1));
            assertTrue(realSm.fetch("items", rid).isEmpty());
        }
    }

    @Test
    void testWriteLogRejectsNullRidAndUnwrappedNpeOnInvalidChange() {
        LogPayload tableChangePayload =
                LogPayload.ofChange(
                        new TxId(1),
                        org.rafendb.common.log.LogType.INSERT,
                        new ChangeRecord(
                                ResourceId.ofTable("items"),
                                Optional.empty(),
                                Optional.of(new byte[] {1})));
        assertThrows(IllegalArgumentException.class, () -> frm.writeLog(tableChangePayload));

        LogPayload nullResourcePayload =
                LogPayload.ofChange(
                        new TxId(1),
                        org.rafendb.common.log.LogType.INSERT,
                        new ChangeRecord(null, Optional.empty(), Optional.of(new byte[] {1})));
        assertThrows(NullPointerException.class, () -> frm.writeLog(nullResourcePayload));
        assertThrows(
                IllegalArgumentException.class,
                () -> frm.writeLog(1, LogType.INSERT, "items", -1, null, "x"));
    }

    @Test
    void testLifecycleValidationAndCheckpointTxZeroIsolation() throws Exception {
        assertFalse(frm.beginTransaction(0));
        assertFalse(frm.beginTransaction(-1));
        assertFalse(frm.commitTransaction(999));

        assertTrue(frm.beginTransaction(10));
        assertFalse(frm.beginTransaction(10));
        assertTrue(frm.commitTransaction(10));
        assertFalse(frm.commitTransaction(10));

        assertTrue(frm.createCheckpoint(new org.rafendb.mock.concurrency.MockConcurrencyControl()));
        assertNull(frm.getTransactionStatus(0));

        long lsn =
                frm.writeLog(
                        LogPayload.ofEvent(
                                new TxId(77), org.rafendb.common.log.LogType.CHECKPOINT_END));
        assertTrue(lsn > 0);
        assertTrue(frm.recoverFromCrash());
        assertNull(frm.getTransactionStatus(77));
    }

    @Test
    void testZeroArgRecoverToPointInTimeRestoresLatestCommittedState() throws Exception {
        frm.beginTransaction(1);
        frm.writeLog(1, LogType.INSERT, "users", 1, null, "Committed");
        frm.commitTransaction(1);

        frm.beginTransaction(2);
        frm.writeLog(2, LogType.INSERT, "users", 2, null, "Uncommitted");
        storage.data.put("users#2", "Uncommitted");

        assertTrue(frm.recoverToPointInTime());
        assertEquals("Committed", storage.data.get("users#1"));
        assertFalse(storage.data.containsKey("users#2"));
        assertEquals(TxStatus.COMMITTED, frm.getTransactionStatus(1));
        assertEquals(TxStatus.ABORTED, frm.getTransactionStatus(2));
    }
}
