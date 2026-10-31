package org.rafendb.mock.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.api.recovery.LogPayload;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.recovery.log.LogType;

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
    void testMocksAndForceThrough() {
        assertFalse(mockFrm.isRedoExecuted());
        assertFalse(mockFrm.isUndoExecuted());

        assertTrue(mockFrm.createCheckpoint());
        assertTrue(mockFrm.recoverToPointInTime());
        assertTrue(mockFrm.recoverToPointInTime(123456789L));
        assertTrue(mockFrm.recoverFromCrash());
        assertTrue(mockFrm.redo());
        assertTrue(mockFrm.undo());

        assertTrue(mockFrm.isRedoExecuted());
        assertTrue(mockFrm.isUndoExecuted());

        assertDoesNotThrow(() -> mockFrm.forceThrough(42));
    }

    @Test
    void testClear() {
        mockFrm.beginTransaction(1);
        mockFrm.writeLog(LogPayload.ofDataChange(1, LogType.INSERT, "t", 1, null, "v"));
        assertEquals(1, mockFrm.getWrittenLogs().size());

        mockFrm.clear();
        assertEquals(0, mockFrm.getWrittenLogs().size());
        assertNull(mockFrm.getTransactionStatus(1));
    }
}
