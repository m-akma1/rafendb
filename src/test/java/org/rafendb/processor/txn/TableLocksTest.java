package org.rafendb.processor.txn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.TxId;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.operator.FakeCcm;
import org.rafendb.processor.operator.FakeSession;

class TableLocksTest {
    private final FakeCcm ccm = new FakeCcm();
    private final FakeSession session = new FakeSession(ccm, null);

    @Test
    void asksForTheRequestedAccessOnTheTable() {
        TableLocks.acquireRead(session, "dosen");
        TableLocks.acquireWrite(session, "prodi");

        assertEquals(List.of("ccm.access READ dosen", "ccm.access WRITE prodi"), ccm.events());
    }

    @Test
    void waitingIsRetriedUntilAllowed() {
        ccm.answerNext(AccessResult.waiting());
        ccm.answerNext(AccessResult.waiting());

        TableLocks.acquireRead(session, "dosen");

        assertEquals(3, ccm.events().count("ccm.access READ dosen"));
    }

    @Test
    void abortedAbortsTransactionAndSurfacesCcmReason() {
        ccm.answerNext(AccessResult.aborted("Deadlock"));

        TransactionAbortedException error =
                assertThrows(
                        TransactionAbortedException.class,
                        () -> TableLocks.acquireRead(session, "dosen"));

        assertTrue(error.getMessage().contains("Deadlock"));
        assertEquals(List.of("ccm.access READ dosen", "ccm.abort T1"), ccm.events());
    }

    @Test
    void failedAbortIsSuppressed() {
        ccm.answerNext(AccessResult.aborted("Deadlock"));
        ccm.failNextAbort();

        TransactionAbortedException error =
                assertThrows(
                        TransactionAbortedException.class,
                        () -> TableLocks.acquireRead(session, "dosen"));

        assertInstanceOf(ConcurrencyInternalException.class, error.getSuppressed()[0]);
    }

    @Test
    void noActiveTransactionOrInvalidSessionAsksNothing() {
        session.setCurrentTx(null);
        assertThrows(IllegalStateException.class, () -> TableLocks.acquireRead(session, "dosen"));

        session.setCurrentTx(new TxId(1));
        session.close();
        assertThrows(IllegalStateException.class, () -> TableLocks.acquireRead(session, "dosen"));

        assertEquals(List.of(), ccm.events());
    }
}
