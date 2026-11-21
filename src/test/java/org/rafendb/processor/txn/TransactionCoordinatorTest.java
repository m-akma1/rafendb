package org.rafendb.processor.txn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.operator.FakeCcm;

class TransactionCoordinatorTest {
    private static final TxId TX = new TxId(100);

    private final FakeCcm ccm = new FakeCcm();
    private final SessionContext session = new SessionContext(1);
    private final TransactionCoordinator coordinator =
            new TransactionCoordinator(session, ccm, null, null);

    @Test
    void beginStoresTheNewIdInTheSession() throws Exception {
        ccm.beginReturns(TX);

        coordinator.beginTx();

        assertEquals(TX, session.getCurrentTxId());
        assertEquals(List.of("ccm.begin"), ccm.events());
    }

    @Test
    void beginRejectsANullIdFromCcm() {
        ccm.beginReturns(null);

        assertThrows(ConcurrencyInternalException.class, coordinator::beginTx);

        assertNull(session.getCurrentTxId());
    }

    @Test
    void beginAndCommitCheckTheSessionBeforeAskingCcm() {
        assertThrows(IllegalStateException.class, coordinator::commitTx);

        session.setCurrentTxId(TX);
        assertThrows(IllegalStateException.class, coordinator::beginTx);

        assertEquals(List.of(), ccm.events());
    }

    @ParameterizedTest
    @CsvSource({
        "COMMITTED,,false",
        "ABORTED,TransactionAbortedException,false",
        "FAILED,TransactionAbortedException,true",
        "PARTIALLY_COMMITTED,ConcurrencyInternalException,true"
    })
    void commitOutcomeDecidesTheErrorAndWhetherTheSessionKeepsItsTx(
            TxStatus status, String error, boolean keepsTx) {
        session.setCurrentTxId(TX);
        ccm.commitReturns(new TxResult(status, ""));

        assertOutcome(error, coordinator::commitTx);

        assertEquals(List.of("ccm.commit T100"), ccm.events());
        assertEquals(keepsTx ? TX : null, session.getCurrentTxId());
    }

    @ParameterizedTest
    @CsvSource({
        "ABORTED,,false",
        "FAILED,ConcurrencyInternalException,true",
        "COMMITTED,IllegalStateException,false"
    })
    void abortOutcomeDecidesTheErrorAndWhetherTheSessionKeepsItsTx(
            TxStatus status, String error, boolean keepsTx) {
        session.setCurrentTxId(TX);
        ccm.abortReturns(new TxResult(status, ""));

        assertOutcome(error, coordinator::abortTx);

        assertEquals(List.of("ccm.abort T100"), ccm.events());
        assertEquals(keepsTx ? TX : null, session.getCurrentTxId());
    }

    @Test
    void abortWithoutATransactionDoesNothing() throws Exception {
        coordinator.abortTx();

        assertEquals(List.of(), ccm.events());
    }

    private interface TxCall {
        void run() throws Exception;
    }

    /** No error expected when {@code error} is null; otherwise its simple class name. */
    private static void assertOutcome(String error, TxCall call) {
        try {
            call.run();
            assertNull(error, "expected " + error);
        } catch (Exception e) {
            assertEquals(error, e.getClass().getSimpleName());
        }
    }
}
