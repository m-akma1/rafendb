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
import org.rafendb.api.recovery.LogPayload;
import org.rafendb.common.tuple.Rid;
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
        public void applyUndo(String tableName, int idRecord, String image, int lsn) {
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

        LogPayload payload =
                LogPayload.ofDataChange(1, LogType.INSERT, "customers", 10, null, "Bob");
        assertTrue(frm.writeLog(payload));

        assertTrue(frm.commitTransaction(1));
    }

    @Test
    void testWriteLogWithRidAndRow() {
        assertTrue(frm.beginTransaction(5));

        Rid rid = new Rid(1, 2);
        LogPayload payload =
                LogPayload.ofDataChange(
                        5, LogType.INSERT, "accounts", rid, Optional.empty(), Optional.empty());
        assertTrue(frm.writeLog(payload));
        assertTrue(frm.commitTransaction(5));
    }

    @Test
    void testRollbackTransaction() {
        assertTrue(frm.beginTransaction(2));
        storage.data.put("customers#20", "Charlie-Old");

        LogPayload payload =
                LogPayload.ofDataChange(
                        2, LogType.UPDATE, "customers", 20, "Charlie-Old", "Charlie-New");
        assertTrue(frm.writeLog(payload));
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
        frm.writeLog(LogPayload.ofDataChange(1, LogType.INSERT, "users", 1, null, "Alice"));
        storage.data.put("users#1", "Alice");
        frm.commitTransaction(1);

        long cutoff = System.currentTimeMillis();
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
        }

        frm.beginTransaction(2);
        frm.writeLog(
                LogPayload.ofDataChange(2, LogType.UPDATE, "users", 1, "Alice", "Alice-Modified"));
        storage.data.put("users#1", "Alice-Modified");
        frm.commitTransaction(2);

        assertTrue(frm.recoverToPointInTime(cutoff));
        assertEquals("Alice", storage.data.get("users#1"));
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
        LogPayload payload =
                LogPayload.ofDataChange(
                        1,
                        LogType.INSERT,
                        "products",
                        new Rid(2, 5),
                        Optional.empty(),
                        Optional.empty());
        assertTrue(frm.writeLog(payload));

        assertDoesNotThrow(() -> frm.forceThrough(2));
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
}
