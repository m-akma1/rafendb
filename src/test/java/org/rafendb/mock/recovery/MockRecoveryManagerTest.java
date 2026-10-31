package org.rafendb.mock.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.api.recovery.LogPayload;
import org.rafendb.api.recovery.LogType;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.txn.TxStatus;

class MockRecoveryManagerTest {

    private MockRecoveryManager mockFrm;

    @BeforeEach
    void setUp() {
        mockFrm = new MockRecoveryManager();
    }

    @Test
    void testBasicLifecycleAndLogs() {
        assertNull(mockFrm.getTransactionStatus(1));

        assertTrue(mockFrm.beginTransaction(1));
        assertEquals(TxStatus.ACTIVE, mockFrm.getTransactionStatus(1));

        LogPayload payload1 =
                LogPayload.ofDataChange(1, LogType.INSERT, "users", 10, null, "Alice");
        long lsn1 = mockFrm.writeLog(payload1);
        assertTrue(lsn1 > 0);
        assertEquals(1, mockFrm.getWrittenLogs().size());

        assertTrue(mockFrm.commitTransaction(1));
        assertEquals(TxStatus.COMMITTED, mockFrm.getTransactionStatus(1));

        assertTrue(mockFrm.beginTransaction(2));
        assertTrue(mockFrm.rollbackTransaction(2));
        assertEquals(TxStatus.ABORTED, mockFrm.getTransactionStatus(2));
    }

    @Test
    void testWriteLogWithRidAndNullCheck() {
        assertEquals(-1, mockFrm.writeLog(null));

        LogPayload payload =
                LogPayload.ofDataChange(
                        3,
                        LogType.INSERT,
                        "accounts",
                        new Rid(1, 2),
                        Optional.empty(),
                        Optional.empty());
        long lsn = mockFrm.writeLog(payload);
        assertTrue(lsn > 0);
        assertEquals(1, mockFrm.getWrittenLogs().size());
    }

    @Test
    void testFilterLogsByTransaction() {
        LogPayload tx1Log1 =
                LogPayload.ofDataChange(10, LogType.INSERT, "orders", 1, null, "item1");
        LogPayload tx2Log1 =
                LogPayload.ofDataChange(20, LogType.INSERT, "orders", 2, null, "item2");
        LogPayload tx1Log2 =
                LogPayload.ofDataChange(10, LogType.UPDATE, "orders", 1, "item1", "item1_mod");

        mockFrm.writeLog(tx1Log1);
        mockFrm.writeLog(tx2Log1);
        mockFrm.writeLog(tx1Log2);

        List<LogPayload> tx1Logs = mockFrm.getLogsByTransaction(10);
        assertEquals(2, tx1Logs.size());
        assertEquals(1, mockFrm.getLogsByTransaction(20).size());
        assertEquals(0, mockFrm.getLogsByTransaction(99).size());
    }

    @Test
    void testMocksAndForceThrough() {
        assertFalse(mockFrm.isRedoExecuted());
        assertFalse(mockFrm.isUndoExecuted());
        assertFalse(mockFrm.isCheckpointCreated());
        assertFalse(mockFrm.isCrashRecovered());
        assertEquals(0, mockFrm.getLastFlushedLsn());

        assertTrue(mockFrm.createCheckpoint());
        assertTrue(mockFrm.isCheckpointCreated());

        assertTrue(mockFrm.recoverToPointInTime());
        assertTrue(mockFrm.getLastRecoveredTimestamp() > 0);

        assertTrue(mockFrm.recoverToPointInTime(123456789L));
        assertEquals(123456789L, mockFrm.getLastRecoveredTimestamp());

        assertTrue(mockFrm.recoverFromCrash());
        assertTrue(mockFrm.isCrashRecovered());
        assertTrue(mockFrm.isRedoExecuted());
        assertTrue(mockFrm.isUndoExecuted());

        mockFrm.forceThrough(42);
        assertEquals(42, mockFrm.getLastFlushedLsn());

        mockFrm.flushLogUntil(100);
        assertEquals(100, mockFrm.getLastFlushedLsn());
    }

    @Test
    void testClear() {
        mockFrm.beginTransaction(1);
        mockFrm.writeLog(LogPayload.ofDataChange(1, LogType.INSERT, "t", 1, null, "v"));
        mockFrm.forceThrough(50);
        mockFrm.createCheckpoint();
        mockFrm.redo();
        mockFrm.undo();

        assertEquals(1, mockFrm.getWrittenLogs().size());
        assertEquals(50, mockFrm.getLastFlushedLsn());
        assertTrue(mockFrm.isCheckpointCreated());

        mockFrm.clear();
        assertEquals(0, mockFrm.getWrittenLogs().size());
        assertNull(mockFrm.getTransactionStatus(1));
        assertEquals(0, mockFrm.getLastFlushedLsn());
        assertFalse(mockFrm.isCheckpointCreated());
        assertFalse(mockFrm.isCrashRecovered());
        assertFalse(mockFrm.isRedoExecuted());
        assertFalse(mockFrm.isUndoExecuted());
    }
}
