package org.rafendb.processor.operator.write;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.operator.EventLog;
import org.rafendb.processor.operator.FakeCcm;
import org.rafendb.processor.operator.FakeStorage;

/** InsertOp against a fake CCM and SM that share one ordered event log (see FakeCcm/FakeStorage). */
class InsertOpTest {
    private static final TableSchema SCHEMA =
            new TableSchema(
                    "t",
                    List.of(
                            new Column("id", DataType.INTEGER, 0, false, true),
                            new Column("name", DataType.VARCHAR, 20, true, false),
                            new Column("score", DataType.FLOAT, 0, true, false)));
    private static final Row ROW =
            new Row(
                    List.of(
                            Value.ofInt(1),
                            Value.ofText(DataType.VARCHAR, "alice"),
                            Value.ofFloat(10f)));
    private static final Rid RID = new Rid(7, 50);
    private static final TxId TX = new TxId(1);

    private final EventLog events = new EventLog();
    private final FakeCcm ccm = new FakeCcm(events);
    private final FakeStorage storage = new FakeStorage(SCHEMA, List.of(), events);
    private final SessionContext session = new SessionContext(1);
    private final InsertOp insert =
            new InsertOp("t", ROW.values(), new ExecutionContext(session, ccm, storage, null));

    InsertOpTest() {
        session.setCurrentTxId(TX);
    }

    @Test
    void locksReservesLogsThenInserts() {
        assertEquals(1L, insert.execute());

        assertEquals(
                List.of(
                        "ccm.access WRITE t",
                        "sm.reserveRid",
                        "ccm.log INSERT lsn=100",
                        "sm.insert lsn=100"),
                events);
        ChangeRecord record = ccm.logged().getFirst();
        assertEquals(ResourceId.ofRow("t", RID), record.resource());
        assertTrue(record.beforeImage().isEmpty());
        assertEquals(ROW, storage.decodeRow(record.afterImage().orElseThrow()));
        assertEquals(ROW, storage.rows().get(RID));
        assertEquals(TX, session.getCurrentTxId());
    }

    @Test
    void refusesToRunWithoutATransaction() {
        session.setCurrentTxId(null);

        assertThrows(IllegalStateException.class, insert::execute);

        assertEquals(List.of(), events);
    }

    @Test
    void abortedByCcmClearsTheSessionAndTouchesNothing() {
        ccm.answerNext(AccessResult.aborted("deadlock victim"));

        assertThrows(TransactionAbortedException.class, insert::execute);

        assertEquals(List.of("ccm.access WRITE t", "ccm.abort T1"), events);
        assertNull(session.getCurrentTxId());
    }

    @Test
    void storageFullOnReservationKeepsTheTransaction() {
        storage.failOn("reserveRid", 1, new StorageInternalException("no space left"));

        IllegalStateException e = assertThrows(IllegalStateException.class, insert::execute);

        assertInstanceOf(StorageInternalException.class, e.getCause());
        assertEquals(List.of("ccm.access WRITE t", "sm.reserveRid (fails)"), events);
        assertEquals(TX, session.getCurrentTxId());
    }

    @Test
    void rowRejectedBySchemaKeepsTheTransaction() {
        storage.failOn("reserveRid", 1, new StorageRuntimeException("row is too large"));

        assertThrows(StorageRuntimeException.class, insert::execute);

        assertEquals(List.of("ccm.access WRITE t", "sm.reserveRid (fails)"), events);
        assertEquals(TX, session.getCurrentTxId());
    }

    @Test
    void encodeFailureReleasesTheSlotWithoutAborting() {
        storage.failOn("encodeRow", 1, new StorageRuntimeException("cannot encode row"));

        assertThrows(StorageRuntimeException.class, insert::execute);

        assertEquals(
                List.of(
                        "ccm.access WRITE t",
                        "sm.reserveRid",
                        "sm.encodeRow (fails)",
                        "sm.cancelReservation"),
                events);
        assertEquals(List.of(RID), storage.cancelled());
        assertEquals(TX, session.getCurrentTxId());
    }

    @Test
    void logFailureAbortsThenReleasesTheSlot() {
        ccm.failNextLog();

        assertThrows(TransactionAbortedException.class, insert::execute);

        assertEquals(
                List.of(
                        "ccm.access WRITE t",
                        "sm.reserveRid",
                        "ccm.log (fails)",
                        "ccm.abort T1",
                        "sm.cancelReservation"),
                events);
        assertTrue(storage.rows().isEmpty());
        assertNull(session.getCurrentTxId());
    }

    @Test
    void insertFailingAfterLogReleasesTheSlotThenAborts() {
        storage.failOn("insert", 1, new StorageInternalException("cannot write block"));

        TransactionAbortedException e =
                assertThrows(TransactionAbortedException.class, insert::execute);

        assertInstanceOf(StorageInternalException.class, e.getCause());
        assertEquals(
                List.of(
                        "ccm.access WRITE t",
                        "sm.reserveRid",
                        "ccm.log INSERT lsn=100",
                        "sm.insert (fails)",
                        "sm.cancelReservation",
                        "ccm.abort T1"),
                events);
        assertNull(session.getCurrentTxId());
    }
}
