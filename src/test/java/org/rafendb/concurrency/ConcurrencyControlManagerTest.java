package org.rafendb.concurrency;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.protocol.Protocol;
import org.rafendb.concurrency.txn.TransactionManager;
import org.rafendb.mock.recovery.MockRecoveryManager;

class ConcurrencyControlManagerTest {

    private static final ResourceId ROW = ResourceId.ofRow("users", new Rid(0, 1));
    private static final ResourceId TABLE = ResourceId.ofTable("users");

    private final MockRecoveryManager frm = new MockRecoveryManager();
    private final StubProtocol protocol = new StubProtocol();
    private final ConcurrencyControlManager ccm =
            new ConcurrencyControlManager(new TransactionManager(), protocol, frm);

    private TxId begin() {
        ccm.initializeAfterRecovery(new RecoveryInfo(0, 0));
        return ccm.begin();
    }

    @Test
    void beginIsRefusedUntilInitialized() {
        assertThrows(IllegalStateException.class, ccm::begin);
    }

    @Test
    void beginIsLoggedToFrm() {
        TxId tx = begin();

        assertEquals(TxStatus.ACTIVE, frm.getTransactionStatus(tx));
    }

    @Test
    void writeIsLoggedAndCommitIsDurable() throws Exception {
        TxId tx = begin();
        assertEquals(
                AccessResult.Status.ALLOWED, ccm.requestAccess(tx, AccessType.WRITE, ROW).status());

        ChangeRecord insert = new ChangeRecord(ROW, Optional.empty(), Optional.of(new byte[] {1}));
        long lsn = ccm.logChange(tx, insert);
        TxResult result = ccm.commit(tx);

        assertTrue(lsn > 0);
        assertEquals(1, frm.getWrittenLogs().size());
        assertEquals(TxStatus.COMMITTED, result.status());
        assertEquals(TxStatus.COMMITTED, frm.getTransactionStatus(tx));
    }

    @Test
    void userAbortRollsBackAndLaterCommitIsRejected() throws Exception {
        TxId tx = begin();

        assertEquals(TxStatus.ABORTED, ccm.abort(tx).status());
        assertEquals(TxStatus.ABORTED, frm.getTransactionStatus(tx));
        assertEquals(TxStatus.ABORTED, ccm.commit(tx).status());
    }

    @Test
    void timestampViolationRollsBackAndLaterRequestsAreAborted() {
        TxId tx = begin();
        protocol.decision = Protocol.Decision.ABORT_REQUIRED;

        AccessResult result = ccm.requestAccess(tx, AccessType.READ, TABLE);

        assertEquals(AccessResult.Status.ABORTED, result.status());
        assertEquals(TxStatus.ABORTED, frm.getTransactionStatus(tx));
        assertEquals(
                AccessResult.Status.ABORTED,
                ccm.requestAccess(tx, AccessType.READ, TABLE).status());
    }

    private static final class StubProtocol implements Protocol {
        Decision decision = Decision.ALLOWED;

        @Override
        public ProtocolType type() {
            return ProtocolType.LOCK_BASED;
        }

        @Override
        public Decision requestAccess(
                TxId tx, long timestamp, AccessType type, ResourceId resource) {
            return decision;
        }

        @Override
        public void cancelPending(TxId tx) {}

        @Override
        public void release(TxId tx, TxStatus outcome) {}

        @Override
        public Optional<TxId> selectDeadlockVictim() {
            return Optional.empty();
        }

        @Override
        public void reset() {}
    }
}
