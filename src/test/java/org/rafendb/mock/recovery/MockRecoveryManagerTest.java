package org.rafendb.mock.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.log.LogType;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;

class MockRecoveryManagerTest {

    private static final TxId TX1 = new TxId(1);
    private static final TxId TX2 = new TxId(2);

    private MockRecoveryManager mockFrm;

    @BeforeEach
    void setUp() {
        mockFrm = new MockRecoveryManager();
    }

    private static ChangeRecord insert(String table, long block, int slot, int after) {
        return new ChangeRecord(
                ResourceId.ofRow(table, new Rid(block, slot)),
                Optional.empty(),
                Optional.of(new byte[] {(byte) after}));
    }

    private static ChangeRecord update(
            String table, long block, int slot, byte[] before, byte[] after) {
        return new ChangeRecord(
                ResourceId.ofRow(table, new Rid(block, slot)),
                Optional.of(before),
                Optional.of(after));
    }

    @Test
    void testBasicLifecycleAndLogs() {
        assertNull(mockFrm.getTransactionStatus(TX1));

        assertTrue(mockFrm.beginTransaction(TX1));
        assertEquals(TxStatus.ACTIVE, mockFrm.getTransactionStatus(TX1));

        LogPayload payload1 = LogPayload.ofChange(TX1, LogType.INSERT, insert("users", 0, 10, 1));
        long lsn1 = mockFrm.writeLog(payload1);
        assertTrue(lsn1 > 0);
        assertEquals(1, mockFrm.getWrittenLogs().size());

        assertTrue(mockFrm.commitTransaction(TX1));
        assertEquals(TxStatus.COMMITTED, mockFrm.getTransactionStatus(TX1));

        assertTrue(mockFrm.beginTransaction(TX2));
        assertTrue(mockFrm.rollbackTransaction(TX2));
        assertEquals(TxStatus.ABORTED, mockFrm.getTransactionStatus(TX2));
    }

    @Test
    void testWriteLogWithRidAndNullCheck() {
        assertThrows(NullPointerException.class, () -> mockFrm.writeLog(null));

        LogPayload payload =
                LogPayload.ofChange(new TxId(3), LogType.INSERT, insert("accounts", 1, 2, 7));
        long lsn = mockFrm.writeLog(payload);
        assertTrue(lsn > 0);
        assertEquals(1, mockFrm.getWrittenLogs().size());
        assertEquals(
                new Rid(1, 2), mockFrm.getWrittenLogs().get(0).change().get().resource().rid());
    }

    @Test
    void testFilterLogsByTransaction() {
        TxId tx10 = new TxId(10);
        TxId tx20 = new TxId(20);
        LogPayload tx1Log1 = LogPayload.ofChange(tx10, LogType.INSERT, insert("orders", 0, 1, 1));
        LogPayload tx2Log1 = LogPayload.ofChange(tx20, LogType.INSERT, insert("orders", 0, 2, 2));
        LogPayload tx1Log2 =
                LogPayload.ofChange(
                        tx10,
                        LogType.UPDATE,
                        update("orders", 0, 1, new byte[] {1}, new byte[] {3}));

        mockFrm.writeLog(tx1Log1);
        mockFrm.writeLog(tx2Log1);
        mockFrm.writeLog(tx1Log2);

        List<LogPayload> tx1Logs = mockFrm.getLogsByTransaction(tx10);
        assertEquals(2, tx1Logs.size());
        assertEquals(1, mockFrm.getLogsByTransaction(tx20).size());
        assertEquals(0, mockFrm.getLogsByTransaction(new TxId(99)).size());
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

        mockFrm.forceThrough(10);
        assertEquals(42, mockFrm.getLastFlushedLsn());
    }

    @Test
    void testClear() {
        mockFrm.beginTransaction(TX1);
        mockFrm.writeLog(LogPayload.ofChange(TX1, LogType.INSERT, insert("t", 0, 1, 5)));
        mockFrm.forceThrough(50);
        mockFrm.createCheckpoint();
        mockFrm.redo();
        mockFrm.undo();

        assertEquals(1, mockFrm.getWrittenLogs().size());
        assertEquals(50, mockFrm.getLastFlushedLsn());
        assertTrue(mockFrm.isCheckpointCreated());

        mockFrm.clear();
        assertEquals(0, mockFrm.getWrittenLogs().size());
        assertNull(mockFrm.getTransactionStatus(TX1));
        assertEquals(0, mockFrm.getLastFlushedLsn());
        assertFalse(mockFrm.isCheckpointCreated());
        assertFalse(mockFrm.isCrashRecovered());
        assertFalse(mockFrm.isRedoExecuted());
        assertFalse(mockFrm.isUndoExecuted());
    }
}
