package org.rafendb.recovery.restart;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.common.tuple.Rid;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.log.WALRecord;
import org.rafendb.recovery.rollback.UndoStorage;

class CrashRecoveryEngineTest {

    private static final long NOW = 9_000L;

    private Logger logger;
    private TestStorage storage;
    private CrashRecoveryEngine engine;

    /** Storage palsu yang menyimpan baris per tabel dan Rid penuh. */
    static class TestStorage implements UndoStorage {
        final Map<String, String> data = new HashMap<>();
        final List<Long> appliedLsns = new ArrayList<>();
        final List<Long> generations = new ArrayList<>();
        int failOnCall = -1;
        int calls = 0;

        @Override
        public void applyUndo(String tableName, int idRecord, String image, long lsn) {
            fail("engine must use the Rid-based applyUndo");
        }

        @Override
        public void applyUndo(
                String tableName, long tableGeneration, Rid rid, String image, long lsn) {
            if (calls++ == failOnCall) {
                throw new IllegalStateException("simulated storage failure");
            }
            appliedLsns.add(lsn);
            generations.add(tableGeneration);
            String key = key(tableName, rid);
            if (image == null) {
                data.remove(key);
            } else {
                data.put(key, image);
            }
        }
    }

    static String key(String table, Rid rid) {
        return table + "#" + rid.block() + ":" + rid.slot();
    }

    /** Kunci untuk record yang ditulis dengan id lama {@code block * 1000 + slot}. */
    static String legacyKey(String table, int idRecord) {
        return key(table, new Rid(idRecord / 1000, idRecord % 1000));
    }

    @BeforeEach
    void setUp() {
        logger = new Logger();
        storage = new TestStorage();
        engine = new CrashRecoveryEngine(logger, storage, () -> NOW);
    }

    @Test
    void testDefaultConstructor() {
        CrashRecoveryEngine defaultEngine = new CrashRecoveryEngine();
        assertFalse(defaultEngine.isCrashRecovered());
        assertFalse(defaultEngine.isRedoExecuted());
        assertFalse(defaultEngine.isUndoExecuted());

        assertTrue(defaultEngine.redo());
        assertTrue(defaultEngine.undo());
        assertTrue(defaultEngine.recoverFromCrash());

        assertTrue(defaultEngine.isRedoExecuted());
        assertTrue(defaultEngine.isUndoExecuted());
        assertTrue(defaultEngine.isCrashRecovered());
    }

    @Test
    void testMockRedoAndUndoOperations() {
        logger.logTransactionEvent(1, LogType.BEGIN);
        logger.logDataChange(1, LogType.INSERT, 1000L, "users", 1, null, "Alice");
        logger.logTransactionEvent(1, LogType.COMMIT);

        logger.logTransactionEvent(2, LogType.BEGIN);
        logger.logDataChange(2, LogType.INSERT, 2000L, "users", 2, null, "Bob");

        storage.data.clear(); // cold restart

        assertTrue(engine.recoverFromCrash());
        assertTrue(engine.isRedoExecuted());
        assertTrue(engine.isUndoExecuted());
        assertTrue(engine.isCrashRecovered());

        assertEquals("Alice", storage.data.get(legacyKey("users", 1)));
        assertNull(storage.data.get(legacyKey("users", 2)));
    }

    @Test
    void testStandaloneRedoAndUndo() {
        logger.logTransactionEvent(10, LogType.BEGIN);
        logger.logDataChange(10, LogType.INSERT, 1000L, "orders", 100, null, "Order-10");
        logger.logTransactionEvent(10, LogType.COMMIT);

        assertTrue(engine.redo());
        assertEquals("Order-10", storage.data.get(legacyKey("orders", 100)));

        logger.logTransactionEvent(20, LogType.BEGIN);
        logger.logDataChange(20, LogType.UPDATE, 2000L, "orders", 100, "Order-10", "Order-20-Bad");
        storage.data.put(legacyKey("orders", 100), "Order-20-Bad");

        assertTrue(engine.undo());
        assertEquals("Order-10", storage.data.get(legacyKey("orders", 100)));
    }

    @Test
    void testRecoveryHandlesAbortedAndLoserTransactions() {
        logger.logTransactionEvent(1, LogType.BEGIN);
        logger.logDataChange(1, LogType.INSERT, 1000L, "users", 1, null, "Alice");
        logger.logTransactionEvent(1, LogType.COMMIT);

        logger.logTransactionEvent(2, LogType.BEGIN);
        logger.logDataChange(2, LogType.INSERT, 1500L, "users", 2, null, "Bob");
        logger.logTransactionEvent(2, LogType.ABORT);

        logger.logTransactionEvent(3, LogType.BEGIN);
        logger.logDataChange(3, LogType.INSERT, 2000L, "users", 3, null, "Charlie");
        storage.data.put(legacyKey("users", 3), "Charlie");

        storage.data.remove(legacyKey("users", 1));

        assertTrue(engine.recoverFromCrash());
        assertEquals("Alice", storage.data.get(legacyKey("users", 1)));
        assertNull(storage.data.get(legacyKey("users", 3)));
        assertTrue(engine.getLoserTransactions().contains(3));
        assertFalse(engine.getLoserTransactions().contains(2));
        assertTrue(engine.getCommittedTransactions().contains(1));
    }

    @Test
    void fullRidAndGenerationArePreservedThroughRedoAndUndo() {
        // Slot >= 1000 dan block besar tidak muat di encoding lama block * 1000 + slot.
        Rid wide = new Rid(5_000_000L, 1234);
        Rid other = new Rid(7L, 1000);
        logger.logTransactionEvent(1, LogType.BEGIN);
        logger.logDataChange(1, LogType.INSERT, 1000L, "users", 3L, wide, null, "Alice");
        logger.logTransactionEvent(1, LogType.COMMIT);
        logger.logTransactionEvent(2, LogType.BEGIN);
        logger.logDataChange(2, LogType.INSERT, 2000L, "users", 4L, other, null, "Bob");

        assertTrue(engine.recoverFromCrash());

        assertEquals(Map.of(key("users", wide), "Alice"), storage.data);
        WALRecord clr = lastDataChange(2);
        assertEquals(other, clr.getRid());
        assertEquals(4L, clr.getTableGeneration());
        assertTrue(storage.generations.containsAll(List.of(3L, 4L)));
    }

    @Test
    void undoWritesCompensationBeforeApplyingAndThenAbort() {
        Rid rid = new Rid(1L, 2);
        logger.logTransactionEvent(1, LogType.BEGIN);
        logger.logDataChange(1, LogType.INSERT, 1000L, "t", 1L, rid, null, "v1");
        logger.logTransactionEvent(1, LogType.COMMIT);
        logger.logTransactionEvent(2, LogType.BEGIN);
        logger.logDataChange(2, LogType.UPDATE, 2000L, "t", 1L, rid, "v1", "v2");
        logger.logDataChange(2, LogType.DELETE, 3000L, "t", 1L, rid, "v2", null);

        assertTrue(engine.recoverFromCrash());
        assertEquals(2, engine.getUndoCount());
        assertEquals("v1", storage.data.get(key("t", rid)));

        List<WALRecord> tx2 = logger.getLogsByTransaction(2);
        // BEGIN, UPDATE, DELETE, CLR(INSERT v2), CLR(UPDATE v2->v1), ABORT
        assertEquals(6, tx2.size());
        WALRecord undoDelete = tx2.get(3);
        assertEquals(LogType.INSERT, undoDelete.getLogType());
        assertNull(undoDelete.getBeforeValue());
        assertEquals("v2", undoDelete.getAfterValue());
        assertEquals(NOW, undoDelete.getTimestamp());
        WALRecord undoUpdate = tx2.get(4);
        assertEquals(LogType.UPDATE, undoUpdate.getLogType());
        assertEquals("v2", undoUpdate.getBeforeValue());
        assertEquals("v1", undoUpdate.getAfterValue());
        assertEquals(LogType.ABORT, tx2.get(5).getLogType());

        // Storage menerima LSN record kompensasi, bukan LSN record asal.
        List<Long> lsns = storage.appliedLsns;
        assertEquals(
                List.of(undoDelete.getLSN(), undoUpdate.getLSN()),
                lsns.subList(lsns.size() - 2, lsns.size()));

        assertTrue(engine.analyze().loserTransactions().isEmpty());
    }

    @Test
    void redoRepeatsAbortedTransactionAndItsCompensation() {
        Rid rid = new Rid(0L, 9);
        logger.logTransactionEvent(1, LogType.BEGIN);
        logger.logDataChange(1, LogType.INSERT, 1000L, "t", 0L, rid, null, "keep");
        logger.logTransactionEvent(1, LogType.COMMIT);
        // Tx 2 di-rollback sebelum crash: perubahan, kompensasi, lalu ABORT (gaya RollbackManager).
        logger.logTransactionEvent(2, LogType.BEGIN);
        logger.logDataChange(2, LogType.UPDATE, 2000L, "t", 0L, rid, "keep", "bad");
        logger.logDataChange(2, LogType.UPDATE, 2100L, "t", 0L, rid, "bad", "keep");
        logger.logTransactionEvent(2, LogType.ABORT);

        storage.data.put(key("t", rid), "bad"); // halaman kotor tx 2 sempat ke disk

        assertTrue(engine.recoverFromCrash());
        assertEquals("keep", storage.data.get(key("t", rid)));
        assertEquals(3, engine.getRedoCount());
        assertEquals(0, engine.getUndoCount());
    }

    @Test
    void crashDuringUndoCanBeRecoveredAgain() {
        Rid a = new Rid(0L, 1);
        Rid b = new Rid(0L, 2);
        logger.logTransactionEvent(1, LogType.BEGIN);
        logger.logDataChange(1, LogType.INSERT, 1000L, "t", 0L, a, null, "a1");
        logger.logDataChange(1, LogType.UPDATE, 1100L, "t", 0L, a, "a1", "a2");
        logger.logDataChange(1, LogType.INSERT, 1200L, "t", 0L, b, null, "b1");

        // Redo = 3 panggilan, undo pertama = panggilan ke-4 (berhasil), kedua gagal.
        storage.failOnCall = 4;
        assertFalse(engine.recoverFromCrash());
        assertTrue(engine.analyze().loserTransactions().contains(1));

        storage.failOnCall = -1;
        storage.data.clear(); // restart lagi
        CrashRecoveryEngine restarted = new CrashRecoveryEngine(logger, storage, () -> NOW);
        assertTrue(restarted.recoverFromCrash());

        assertTrue(storage.data.isEmpty());
        assertTrue(restarted.analyze().loserTransactions().isEmpty());
    }

    @Test
    void recoveryResultSurvivesRestartFromWalFile(@TempDir Path dir) throws Exception {
        Path wal = dir.resolve("rafendb.wal");
        Rid rid = new Rid(42L, 1500);
        Logger first = new Logger(wal);
        first.logTransactionEvent(1, LogType.BEGIN);
        first.logDataChange(1, LogType.INSERT, 1000L, "t", 2L, rid, null, "committed");
        first.logTransactionEvent(1, LogType.COMMIT);
        first.logTransactionEvent(2, LogType.BEGIN);
        first.logDataChange(2, LogType.UPDATE, 2000L, "t", 2L, rid, "committed", "loser");
        first.flushAll();
        first.close(); // crash

        Logger second = new Logger(wal);
        TestStorage disk = new TestStorage();
        assertTrue(new CrashRecoveryEngine(second, disk, () -> NOW).recoverFromCrash());
        assertEquals("committed", disk.data.get(key("t", rid)));
        second.close();

        // Restart berikutnya: kompensasi dan ABORT sudah permanen, tx 2 bukan loser lagi.
        Logger third = new Logger(wal);
        TestStorage cold = new TestStorage();
        CrashRecoveryEngine again = new CrashRecoveryEngine(third, cold, () -> NOW);
        assertTrue(again.recoverFromCrash());
        assertEquals("committed", cold.data.get(key("t", rid)));
        assertEquals(0, again.getUndoCount());
        WALRecord clr = third.getLogsByTransaction(2).get(2);
        assertEquals(rid, clr.getRid());
        assertEquals(2L, clr.getTableGeneration());
        third.close();
    }

    @Test
    void defaultUndoStorageBridgeUsesLegacyId() {
        List<Integer> ids = new ArrayList<>();
        UndoStorage legacy = (table, idRecord, image, lsn) -> ids.add(idRecord);
        legacy.applyUndo("t", 0L, new Rid(3L, 7), "x", 1L);
        legacy.applyUndo("t", 0L, null, null, 2L);
        assertEquals(List.of(3007, -1), ids);
    }

    private WALRecord lastDataChange(int tx) {
        List<WALRecord> logs = logger.getLogsByTransaction(tx);
        for (int i = logs.size() - 1; i >= 0; i--) {
            if (logs.get(i).getLogType().isDataChange()) {
                return logs.get(i);
            }
        }
        throw new AssertionError("no data change for tx " + tx);
    }
}
