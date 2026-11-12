package org.rafendb.processor.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.TransactionAbortedException;

class TransactionCoordinatorTest {

    private FakeCCM ccm;
    private SessionContext session;
    private TransactionCoordinator coordinator;

    private static final TxId TX_ID = new TxId(100);

    @BeforeEach
    void setUp() {
        ccm = new FakeCCM();
        session = new SessionContext(1);
        coordinator = new TransactionCoordinator(session, ccm, null, null);
    }

    @Test
    void beginsTransactionAndStoresReturnedIdInSession() throws Exception {
        ccm.beginResult = TX_ID;

        coordinator.beginTx();

        assertEquals(TX_ID, session.getCurrentTxId());
        assertEquals(1, ccm.beginCalls);
    }

    @Test
    void rejectsBeginWhenCcmReturnsNullId() {
        ccm.beginResult = null;

        ConcurrencyInternalException e =
                assertThrows(ConcurrencyInternalException.class, () -> coordinator.beginTx());

        System.out.println(e.getMessage());

        assertNull(session.getCurrentTxId());
    }

    @Test
    void rejectsBeginWhenSessionAlreadyHasTransaction() {
        session.setCurrentTxId(TX_ID);

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> coordinator.beginTx());

        System.out.println(e.getMessage());

        assertEquals(0, ccm.beginCalls);
    }

    @Test
    void commitsTransactionAndClearsSessionId() throws Exception {
        session.setCurrentTxId(TX_ID);
        ccm.commitResult = new TxResult(TxStatus.COMMITTED, "");

        coordinator.commitTx();

        assertNull(session.getCurrentTxId());
        assertEquals(TX_ID, ccm.lastCommitId);
        assertEquals(1, ccm.commitCalls);
    }

    @Test
    void clearsSessionIdAndThrowsWhenCommitReturnsAborted() {
        session.setCurrentTxId(TX_ID);
        ccm.commitResult = new TxResult(TxStatus.ABORTED, "Transaction was aborted");

        TransactionAbortedException e =
                assertThrows(TransactionAbortedException.class, () -> coordinator.commitTx());

        System.out.println(e.getMessage());

        assertNull(session.getCurrentTxId());
    }

    @Test
    void preservesSessionIdWhenCommitReturnsFailed() {
        session.setCurrentTxId(TX_ID);
        ccm.commitResult = new TxResult(TxStatus.FAILED, "Rollback is not complete");

        TransactionAbortedException e =
                assertThrows(TransactionAbortedException.class, () -> coordinator.commitTx());

        System.out.println(e.getMessage());

        assertEquals(TX_ID, session.getCurrentTxId());
    }

    @Test
    void preservesSessionIdWhenCommitReturnsPartiallyCommitted() {
        session.setCurrentTxId(TX_ID);
        ccm.commitResult = new TxResult(TxStatus.PARTIALLY_COMMITTED, "Commit is incomplete");

        ConcurrencyInternalException e =
                assertThrows(ConcurrencyInternalException.class, () -> coordinator.commitTx());

        System.out.println(e.getMessage());

        assertEquals(TX_ID, session.getCurrentTxId());
        assertEquals(1, ccm.commitCalls);
    }

    @Test
    void rejectsCommitWhenSessionHasNoTransaction() {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> coordinator.commitTx());

        System.out.println(e.getMessage());

        assertEquals(0, ccm.commitCalls);
    }

    @Test
    void abortsTransactionAndClearsSessionId() throws Exception {
        session.setCurrentTxId(TX_ID);
        ccm.abortResult = new TxResult(TxStatus.ABORTED, "");

        coordinator.abortTx();

        assertNull(session.getCurrentTxId());
        assertEquals(TX_ID, ccm.lastAbortId);
        assertEquals(1, ccm.abortCalls);
    }

    @Test
    void preservesSessionIdWhenAbortReturnsFailed() {
        session.setCurrentTxId(TX_ID);
        ccm.abortResult = new TxResult(TxStatus.FAILED, "Rollback failed");

        ConcurrencyInternalException e =
                assertThrows(ConcurrencyInternalException.class, () -> coordinator.abortTx());

        System.out.println(e.getMessage());

        assertEquals(TX_ID, session.getCurrentTxId());
    }

    @Test
    void clearsSessionIdAndRejectsAbortWhenAlreadyCommitted() {
        session.setCurrentTxId(TX_ID);
        ccm.abortResult = new TxResult(TxStatus.COMMITTED, "Transaction already committed");

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> coordinator.abortTx());

        System.out.println(e.getMessage());

        assertNull(session.getCurrentTxId());
    }

    @Test
    void doesNothingWhenAbortingWithoutActiveTransaction() throws Exception {
        coordinator.abortTx();

        assertEquals(0, ccm.abortCalls);
        assertNull(session.getCurrentTxId());
    }

    /**
     * Configurable fake CCM.
     * Returns predefined results and records method invocations.
     */
    private static final class FakeCCM implements ConcurrencyControl {

        private TxId beginResult = TX_ID;

        private TxResult commitResult = new TxResult(TxStatus.COMMITTED, "");

        private TxResult abortResult = new TxResult(TxStatus.ABORTED, "");

        private TxId lastCommitId;
        private TxId lastAbortId;

        private int beginCalls;
        private int commitCalls;
        private int abortCalls;

        @Override
        public TxId begin() {
            beginCalls++;
            return beginResult;
        }

        @Override
        public TxResult commit(TxId txId) throws ConcurrencyInternalException {
            commitCalls++;
            lastCommitId = txId;
            return commitResult;
        }

        @Override
        public TxResult abort(TxId txId) throws ConcurrencyInternalException {
            abortCalls++;
            lastAbortId = txId;
            return abortResult;
        }

        @Override
        public AccessResult requestAccess(TxId tx, AccessType type, ResourceId resource) {
            return null;
        }

        @Override
        public void pauseNewTransactions() {}

        @Override
        public boolean awaitNoActive(Duration timeout) throws InterruptedException {
            return false;
        }

        @Override
        public void resumeNewTransactions() {}

        @Override
        public ProtocolType activeProtocol() {
            return null;
        }
    }
}
