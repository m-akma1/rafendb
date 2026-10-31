package org.rafendb.recovery.restart;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.rollback.UndoStorage;

class CrashRecoveryEngineTest {

    private Logger logger;
    private TestStorage storage;
    private CrashRecoveryEngine engine;

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
        logger = new Logger();
        storage = new TestStorage();
        engine = new CrashRecoveryEngine(logger, storage);
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
        // Tx 1: Committed transaction
        logger.logTransactionEvent(1, LogType.BEGIN);
        logger.logDataChange(1, LogType.INSERT, 1000L, "users", 1, null, "Alice");
        logger.logTransactionEvent(1, LogType.COMMIT);

        // Tx 2: Uncommitted transaction (simulating crash before commit)
        logger.logTransactionEvent(2, LogType.BEGIN);
        logger.logDataChange(2, LogType.INSERT, 2000L, "users", 2, null, "Bob");
        storage.data.put("users#2", "Bob");

        // Clear storage to simulate cold restart after crash
        storage.data.clear();

        // Run crash recovery
        assertTrue(engine.recoverFromCrash());
        assertTrue(engine.isRedoExecuted());
        assertTrue(engine.isUndoExecuted());
        assertTrue(engine.isCrashRecovered());

        // Tx 1 must be redone (Alice exists)
        assertEquals("Alice", storage.data.get("users#1"));
        // Tx 2 must be undone (Bob was cleared)
        assertNull(storage.data.get("users#2"));
    }

    @Test
    void testStandaloneRedoAndUndo() {
        logger.logTransactionEvent(10, LogType.BEGIN);
        logger.logDataChange(10, LogType.INSERT, 1000L, "orders", 100, null, "Order-10");
        logger.logTransactionEvent(10, LogType.COMMIT);

        assertTrue(engine.redo());
        assertEquals("Order-10", storage.data.get("orders#100"));

        logger.logTransactionEvent(20, LogType.BEGIN);
        logger.logDataChange(20, LogType.UPDATE, 2000L, "orders", 100, "Order-10", "Order-20-Bad");
        storage.data.put("orders#100", "Order-20-Bad");

        assertTrue(engine.undo());
        assertEquals("Order-10", storage.data.get("orders#100"));
    }
}
