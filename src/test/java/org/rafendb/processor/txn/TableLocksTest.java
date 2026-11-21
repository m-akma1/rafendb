package org.rafendb.processor.txn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.TxId;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.operator.FakeCcm;

class TableLocksTest {
    private final FakeCcm ccm = new FakeCcm();
    private final SessionContext session = new SessionContext(1);
    private final ExecutionContext context = new ExecutionContext(session, ccm, null, null);

    TableLocksTest() {
        session.setCurrentTxId(new TxId(1));
    }

    @Test
    void asksForTheRequestedAccessOnTheTable() {
        TableLocks.acquireRead(context, "dosen");
        TableLocks.acquireWrite(context, "prodi");

        assertEquals(List.of("ccm.access READ dosen", "ccm.access WRITE prodi"), ccm.events());
    }

    @Test
    void waitingIsRetriedUntilAllowed() {
        ccm.answerNext(AccessResult.waiting());
        ccm.answerNext(AccessResult.waiting());

        TableLocks.acquireRead(context, "dosen");

        assertEquals(3, ccm.events().count("ccm.access READ dosen"));
        assertEquals(new TxId(1), session.getCurrentTxId());
    }

    @Test
    void abortedAbortsTransactionAndSurfacesCcmReason() {
        ccm.answerNext(AccessResult.aborted("Deadlock"));

        TransactionAbortedException error =
                assertThrows(
                        TransactionAbortedException.class,
                        () -> TableLocks.acquireRead(context, "dosen"));

        assertTrue(error.getMessage().contains("Deadlock"));
        assertEquals(List.of("ccm.access READ dosen", "ccm.abort T1"), ccm.events());
        assertNull(session.getCurrentTxId());
    }

    @Test
    void failedAbortIsSuppressedAndTxIdIsKept() {
        ccm.answerNext(AccessResult.aborted("Deadlock"));
        ccm.failNextAbort();

        TransactionAbortedException error =
                assertThrows(
                        TransactionAbortedException.class,
                        () -> TableLocks.acquireRead(context, "dosen"));

        assertInstanceOf(ConcurrencyInternalException.class, error.getSuppressed()[0]);
        assertEquals(new TxId(1), session.getCurrentTxId());
    }

    @Test
    void noActiveTransactionOrInvalidSessionAsksNothing() {
        session.setCurrentTxId(null);
        assertThrows(IllegalStateException.class, () -> TableLocks.acquireRead(context, "dosen"));

        session.setCurrentTxId(new TxId(1));
        session.invalidate();
        assertThrows(IllegalStateException.class, () -> TableLocks.acquireRead(context, "dosen"));

        assertEquals(List.of(), ccm.events());
    }
}
