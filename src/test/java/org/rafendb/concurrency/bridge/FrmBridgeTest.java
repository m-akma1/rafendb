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
import org.rafendb.concurrency.txn.TransactionManager;

class FrmBridgeTest {

    private final List<String> calls = new ArrayList<>();
    private TransactionManager txns;
    private RecordingRecoveryManager frm;
    private FakeProtocol protocol;
    private FrmBridge bridge;

    @BeforeEach
    void setUp() {
        txns = new TransactionManager();
        frm = new RecordingRecoveryManager(calls, txns);
        protocol = new FakeProtocol(calls, txns);
        bridge = new FrmBridge(frm, txns, protocol);
    }

    /** Opens the CCM, begins one transaction, and clears the call list. */
    private TxId activeTransaction() {
        bridge.initializeAfterRecovery(new RecoveryInfo(0, 0));
        TxId tx = txns.begin();
        calls.clear();
        return tx;
    }

    @Test
    void transactionsStayClosedUntilInitialization() {
        assertThrows(IllegalStateException.class, txns::begin);
    }

    @Test
    void initializationResetsProtocolThenOpensAboveLargerHighWaterMark() {
        bridge.initializeAfterRecovery(new RecoveryInfo(100, 120));

        assertEquals(List.of("reset"), calls);
        assertEquals(121, txns.begin().value());
    }

    @Test
    void secondInitializationIsRejectedBeforeAnyReset() {
        bridge.initializeAfterRecovery(new RecoveryInfo(0, 0));
        calls.clear();

        assertThrows(
                IllegalStateException.class,
                () -> bridge.initializeAfterRecovery(new RecoveryInfo(5, 5)));
        assertTrue(calls.isEmpty());
    }

    @Test
    void negativeHighWaterMarkIsRejectedBeforeAnyReset() {
        assertThrows(
                IllegalArgumentException.class,
                () -> bridge.initializeAfterRecovery(new RecoveryInfo(-1, 0)));
        assertTrue(calls.isEmpty());
        assertThrows(IllegalStateException.class, txns::begin);
    }

    @Test
    void failedResetLeavesTransactionsClosedAndCanBeRetried() {
        protocol.failReset = true;

        assertThrows(
                IllegalStateException.class,
                () -> bridge.initializeAfterRecovery(new RecoveryInfo(3, 3)));
        assertThrows(IllegalStateException.class, txns::begin);

        protocol.failReset = false;
        bridge.initializeAfterRecovery(new RecoveryInfo(3, 3));
        assertEquals(4, txns.begin().value());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> bridge.initializeAfterRecovery(null));
        assertThrows(NullPointerException.class, () -> new FrmBridge(null, txns, protocol));
    }

    @Test
    void beginIsLogged() {
        TxId tx = activeTransaction();

        bridge.logBegin(tx);

        assertEquals(List.of("begin T1 [ACTIVE]"), calls);
    }

    @Test
    void failedBeginLogIsRejected() {
        TxId tx = activeTransaction();
        frm.beginSucceeds = false;

        assertThrows(IllegalStateException.class, () -> bridge.logBegin(tx));
    }

    @Test
    void commitLogsCommitBeforeReleasingProtection() throws Exception {
        TxId tx = activeTransaction();

        TxResult result = bridge.commit(tx);

        assertEquals(TxStatus.COMMITTED, result.status());
        assertEquals(
                List.of("commit T1 [PARTIALLY_COMMITTED]", "release T1 COMMITTED [COMMITTED]"),
                calls);
    }

    @Test
    void failedCommitLogAbortsTransaction() {
        TxId tx = activeTransaction();
        frm.commitSucceeds = false;

        assertThrows(ConcurrencyInternalException.class, () -> bridge.commit(tx));
        assertEquals(TxStatus.ABORTED, txns.status(tx));
        assertEquals(
                List.of(
                        "commit T1 [PARTIALLY_COMMITTED]",
                        "cancelPending T1 [FAILED]",
                        "rollback T1 [FAILED]",
                        "release T1 ABORTED [ABORTED]"),
                calls);
    }

    @Test
    void commitErrorIsKeptAsCause() {
        TxId tx = activeTransaction();
        IllegalStateException fsyncFailed = new IllegalStateException("fsync failed");
        frm.commitError = fsyncFailed;

        ConcurrencyInternalException e =
                assertThrows(ConcurrencyInternalException.class, () -> bridge.commit(tx));
        assertSame(fsyncFailed, e.getCause());
    }

    @Test
    void failedRollbackAfterFailedCommitKeepsTransactionFailed() {
        TxId tx = activeTransaction();
        frm.commitSucceeds = false;
        frm.rollbackSucceeds = false;

        ConcurrencyInternalException e =
                assertThrows(ConcurrencyInternalException.class, () -> bridge.commit(tx));
        assertEquals(1, e.getSuppressed().length);
        assertEquals(TxStatus.FAILED, txns.status(tx));
        assertFalse(calls.stream().anyMatch(call -> call.startsWith("release")));
    }

    @Test
    void commitOnAbortedTransactionIsRejectedWithoutLogging() throws Exception {
        TxId tx = activeTransaction();
        txns.transition(tx, TxStatus.FAILED);
        txns.transition(tx, TxStatus.ABORTED);

        TxResult result = bridge.commit(tx);

        assertEquals(TxStatus.ABORTED, result.status());
        assertFalse(result.message().isEmpty());
        assertTrue(calls.isEmpty());
    }

    @Test
    void abortKeepsProtectionUntilRollbackFinishes() throws Exception {
        TxId tx = activeTransaction();

        TxResult result = bridge.abort(tx);

        assertEquals(TxStatus.ABORTED, result.status());
        assertEquals(
                List.of(
                        "cancelPending T1 [FAILED]",
                        "rollback T1 [FAILED]",
                        "release T1 ABORTED [ABORTED]"),
                calls);
    }

    @Test
    void abortingAnAbortedTransactionChangesNothing() throws Exception {
        TxId tx = activeTransaction();
        bridge.abort(tx);
        calls.clear();

        TxResult again = bridge.abort(tx);

        assertEquals(TxStatus.ABORTED, again.status());
        assertTrue(calls.isEmpty());
    }

    @Test
    void failedRollbackKeepsTransactionFailedAndProtected() {
        TxId tx = activeTransaction();
        frm.rollbackSucceeds = false;

        assertThrows(ConcurrencyInternalException.class, () -> bridge.abort(tx));
        assertEquals(TxStatus.FAILED, txns.status(tx));
        assertFalse(calls.stream().anyMatch(call -> call.startsWith("release")));
    }

    @Test
    void rollbackErrorIsKeptAsCause() {
        TxId tx = activeTransaction();
        IllegalStateException diskFull = new IllegalStateException("disk full");
        frm.rollbackError = diskFull;

        ConcurrencyInternalException e =
                assertThrows(ConcurrencyInternalException.class, () -> bridge.abort(tx));
        assertSame(diskFull, e.getCause());
        assertEquals(TxStatus.FAILED, txns.status(tx));
    }

    @Test
    void abortCanBeRetriedAfterFailedRollback() throws Exception {
        TxId tx = activeTransaction();
        frm.rollbackSucceeds = false;
        assertThrows(ConcurrencyInternalException.class, () -> bridge.abort(tx));
        frm.rollbackSucceeds = true;
        calls.clear();

        TxResult result = bridge.abort(tx);

        assertEquals(TxStatus.ABORTED, result.status());
        assertEquals(
                List.of(
                        "cancelPending T1 [FAILED]",
                        "rollback T1 [FAILED]",
                        "release T1 ABORTED [ABORTED]"),
                calls);
    }

    @Test
    void committedTransactionCannotBeAborted() throws Exception {
        TxId tx = activeTransaction();
        txns.transition(tx, TxStatus.PARTIALLY_COMMITTED);
        txns.transition(tx, TxStatus.COMMITTED);

        TxResult result = bridge.abort(tx);

        assertEquals(TxStatus.COMMITTED, result.status());
        assertFalse(result.message().isEmpty());
        assertTrue(calls.isEmpty());
    }
}
