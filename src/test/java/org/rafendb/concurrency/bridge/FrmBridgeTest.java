package org.rafendb.concurrency.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;

class FrmBridgeTest {

    private static final TxId T1 = new TxId(1);

    private final List<String> calls = new ArrayList<>();
    private RecordingRecoveryManager frm;
    private FakeTransactionStates states;
    private FakeProtocol protocol;
    private FrmBridge bridge;

    @BeforeEach
    void setUp() {
        frm = new RecordingRecoveryManager(calls);
        states = new FakeTransactionStates(calls);
        protocol = new FakeProtocol(calls);
        bridge = new FrmBridge(frm, states, protocol);
    }

    @Test
    void admissionStartsClosed() {
        assertFalse(bridge.isAdmitting());
    }

    @Test
    void initializationResetsAboveLargerHighWaterMarkThenOpensAdmission() {
        bridge.initializeAfterRecovery(new RecoveryInfo(100, 120));

        assertEquals(List.of("resetAbove 120", "reset"), calls);
        assertTrue(bridge.isAdmitting());
    }

    @Test
    void secondInitializationIsRejected() {
        bridge.initializeAfterRecovery(new RecoveryInfo(0, 0));

        assertThrows(
                IllegalStateException.class,
                () -> bridge.initializeAfterRecovery(new RecoveryInfo(5, 5)));
        assertEquals(List.of("resetAbove 0", "reset"), calls);
    }

    @Test
    void negativeHighWaterMarkIsRejectedBeforeAnyReset() {
        assertThrows(
                IllegalArgumentException.class,
                () -> bridge.initializeAfterRecovery(new RecoveryInfo(-1, 0)));
        assertFalse(bridge.isAdmitting());
        assertTrue(calls.isEmpty());
    }

    @Test
    void failedResetLeavesAdmissionClosedAndCanBeRetried() {
        protocol.failReset = true;

        assertThrows(
                IllegalStateException.class,
                () -> bridge.initializeAfterRecovery(new RecoveryInfo(3, 3)));
        assertFalse(bridge.isAdmitting());

        protocol.failReset = false;
        bridge.initializeAfterRecovery(new RecoveryInfo(3, 3));
        assertTrue(bridge.isAdmitting());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> bridge.initializeAfterRecovery(null));
        assertThrows(NullPointerException.class, () -> new FrmBridge(null, states, protocol));
    }

    @Test
    void abortKeepsProtectionUntilRollbackFinishes() throws Exception {
        states.put(T1, TxStatus.ACTIVE);

        TxResult result = bridge.abort(T1);

        assertEquals(TxStatus.ABORTED, result.status());
        assertEquals(
                List.of(
                        "T1 FAILED",
                        "cancelPending T1",
                        "rollback T1",
                        "T1 ABORTED",
                        "release T1 ABORTED"),
                calls);
    }

    @Test
    void abortingAnAbortedTransactionChangesNothing() throws Exception {
        states.put(T1, TxStatus.ACTIVE);
        bridge.abort(T1);
        calls.clear();

        TxResult again = bridge.abort(T1);

        assertEquals(TxStatus.ABORTED, again.status());
        assertTrue(calls.isEmpty());
    }

    @Test
    void failedRollbackKeepsTransactionFailedAndProtected() {
        states.put(T1, TxStatus.ACTIVE);
        frm.rollbackSucceeds = false;

        assertThrows(ConcurrencyInternalException.class, () -> bridge.abort(T1));
        assertEquals(TxStatus.FAILED, states.status(T1));
        assertFalse(calls.contains("release T1 ABORTED"));
    }

    @Test
    void rollbackErrorIsKeptAsCause() {
        states.put(T1, TxStatus.ACTIVE);
        IllegalStateException diskFull = new IllegalStateException("disk full");
        frm.rollbackError = diskFull;

        ConcurrencyInternalException e =
                assertThrows(ConcurrencyInternalException.class, () -> bridge.abort(T1));
        assertSame(diskFull, e.getCause());
        assertEquals(TxStatus.FAILED, states.status(T1));
    }

    @Test
    void abortCanBeRetriedAfterFailedRollback() throws Exception {
        states.put(T1, TxStatus.ACTIVE);
        frm.rollbackSucceeds = false;
        assertThrows(ConcurrencyInternalException.class, () -> bridge.abort(T1));
        frm.rollbackSucceeds = true;
        calls.clear();

        TxResult result = bridge.abort(T1);

        assertEquals(TxStatus.ABORTED, result.status());
        assertEquals(
                List.of("cancelPending T1", "rollback T1", "T1 ABORTED", "release T1 ABORTED"),
                calls);
    }

    @Test
    void committedTransactionCannotBeAborted() throws Exception {
        states.put(T1, TxStatus.COMMITTED);

        TxResult result = bridge.abort(T1);

        assertEquals(TxStatus.COMMITTED, result.status());
        assertFalse(result.message().isEmpty());
        assertTrue(calls.isEmpty());
    }
}
