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
    void setUp() {
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
    void testBeginAndWriteLogAndCommit() {
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
    void testRollbackTransaction() {
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
    void testRecoverToPointInTime() {
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
    void testPitrExcludesOldChangeFromTransactionCommittedAfterTarget()
            throws InterruptedException {
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
    void testWalSyncForceThrough() {
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
            org.rafendb.api.storage.StorageManager countingSm =
                    (org.rafendb.api.storage.StorageManager)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    org.rafendb.api.storage.StorageManager.class.getClassLoader(),
                                    new Class<?>[] {org.rafendb.api.storage.StorageManager.class},
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
}
