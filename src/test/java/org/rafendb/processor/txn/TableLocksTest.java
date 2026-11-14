package org.rafendb.processor.txn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
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
    void readAllowedReturnsRightAway() {
        TableLocks.acquireRead(context, "dosen");
        assertEquals(List.of("request READ dosen"), ccm.getEvents());
        assertEquals(0, ccm.getAbortCount());
    }

    @Test
    void writeAsksForWriteAccess() {
        TableLocks.acquireWrite(context, "dosen");
        assertEquals(List.of("request WRITE dosen"), ccm.getEvents());
    }

    @Test
    void waitingIsRetriedUntilAllowed() {
        ccm.answerNext(AccessResult.waiting());
        ccm.answerNext(AccessResult.waiting());
        TableLocks.acquireRead(context, "dosen");
        assertEquals(
                List.of("request READ dosen", "request READ dosen", "request READ dosen"),
                ccm.getEvents());
        assertEquals(new TxId(1), session.getCurrentTxId());
    }

    @Test
    void abortedAbortsTransactionAndThrows() {
        ccm.answerNext(AccessResult.aborted("Deadlock"));
        TransactionAbortedException error =
                assertThrows(
                        TransactionAbortedException.class,
                        () -> TableLocks.acquireRead(context, "dosen"));
        assertEquals("Runtime error at Query Processor: Deadlock", error.getMessage());
        assertEquals(1, ccm.getAbortCount());
        assertNull(session.getCurrentTxId());
    }

    @Test
    void noActiveTransactionIsRejected() {
        session.setCurrentTxId(null);
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> TableLocks.acquireRead(context, "dosen"));
        assertEquals(
                "No active transaction: run the statement inside AutoCommit.run(...) or after"
                        + " BEGIN",
                error.getMessage());
        assertTrue(ccm.getEvents().isEmpty());
    }

    @Test
    void invalidSessionIsRejected() {
        session.invalidate();
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> TableLocks.acquireRead(context, "dosen"));
        assertEquals("The session is not valid", error.getMessage());
    }
}
