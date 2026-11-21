package org.rafendb.processor.operator.write;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.expr.Expr;
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
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.operator.EventLog;
import org.rafendb.processor.operator.FakeCcm;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.operator.FakeStorage;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/**
 * UpdateOp against a fake CCM and SM that share one ordered event log (see FakeCcm/FakeStorage).
 * UpdateOp encodes every log image while collecting, before anything is logged; it calls encodeRow
 * twice per target row (before image, then after image).
 */
class UpdateOpBehaviorTest {
    private static final String TABLE = "t";
    private static final TableSchema SCHEMA =
            new TableSchema(
                    TABLE,
                    List.of(
                            new Column("id", DataType.INTEGER, 0, false, true),
                            new Column("name", DataType.VARCHAR, 20, true, false),
                            new Column("score", DataType.FLOAT, 0, true, false)));
    private static final List<ColumnSchema> OP_SCHEMA =
            List.of(
                    new ColumnSchema(new Expr.ColumnRef(TABLE, "id", 0), DataType.INTEGER),
                    new ColumnSchema(new Expr.ColumnRef(TABLE, "name", 1), DataType.VARCHAR),
                    new ColumnSchema(new Expr.ColumnRef(TABLE, "score", 2), DataType.FLOAT));

    private static final Rid R1 = new Rid(1, 0);
    private static final Rid R2 = new Rid(1, 1);
    private static final Rid R3 = new Rid(1, 2);
    private static final TxId TX = new TxId(1);

    private final EventLog events = new EventLog();
    private final FakeCcm ccm = new FakeCcm(events);
    private final FakeStorage storage = new FakeStorage(SCHEMA, List.of(), events);
    private final SessionContext session = new SessionContext(1);
    private final ExecutionContext ctx = new ExecutionContext(session, ccm, storage, null);

    UpdateOpBehaviorTest() {
        session.setCurrentTxId(TX);
    }

    // in place

    @Test
    void shorterValueIsUpdatedInPlace() {
        Row old = row(1, "alice", 1f);
        storage.rows().put(R1, old);
        FakeChildOp child = child(old, R1);

        assertEquals(1L, setName(text("bob"), child).execute());

        assertEquals(
                List.of("ccm.access WRITE t", "ccm.log UPDATE lsn=100", "sm.update lsn=100"),
                events);
        ChangeRecord record = ccm.logged().getFirst();
        assertEquals(ResourceId.ofRow(TABLE, R1), record.resource());
        assertEquals(old, decode(record.beforeImage().orElseThrow()));
        assertEquals(row(1, "bob", 1f), decode(record.afterImage().orElseThrow()));
        assertEquals(row(1, "bob", 1f), storage.rows().get(R1));
        assertChildClosed(child);
    }

    @Test
    void noMatchingRowsOnlyAsksForWriteAccess() {
        FakeChildOp child = child();

        assertEquals(0L, setName(text("x"), child).execute());

        assertEquals(List.of("ccm.access WRITE t"), events);
        assertChildClosed(child);
    }

    // relocation

    @Test
    void growingValueIsRelocatedAsDeleteThenInsert() {
        Row old = row(1, "al", 1f);
        storage.rows().put(R1, old);
        FakeChildOp child = child(old, R1);

        assertEquals(1L, setName(text("alexander"), child).execute()); // one row, not two writes

        assertEquals(
                List.of(
                        "ccm.access WRITE t",
                        "sm.reserveRid",
                        "ccm.log DELETE lsn=100",
                        "sm.delete lsn=100",
                        "ccm.log INSERT lsn=101",
                        "sm.insert lsn=101"),
                events);
        Rid fresh = storage.reserved().getFirst();
        ChangeRecord delete = ccm.logged().get(0);
        ChangeRecord insert = ccm.logged().get(1);
        assertEquals(ResourceId.ofRow(TABLE, R1), delete.resource());
        assertEquals(old, decode(delete.beforeImage().orElseThrow()));
        assertTrue(delete.afterImage().isEmpty());
        assertEquals(ResourceId.ofRow(TABLE, fresh), insert.resource());
        assertTrue(insert.beforeImage().isEmpty());
        assertEquals(row(1, "alexander", 1f), decode(insert.afterImage().orElseThrow()));
        assertFalse(storage.rows().containsKey(R1), "old Rid must be gone");
        assertEquals(row(1, "alexander", 1f), storage.rows().get(fresh));
        assertChildClosed(child);
    }

    @Test
    void everySlotIsReservedBeforeTheFirstLogRecord() {
        storage.rows().put(R1, row(1, "a", 1f));
        storage.rows().put(R2, row(2, "b", 2f));
        FakeChildOp child = child(row(1, "a", 1f), R1, row(2, "b", 2f), R2);

        assertEquals(2L, setName(text("alexander"), child).execute());

        assertEquals(2, events.count("sm.reserveRid"));
        assertTrue(events.last("sm.reserveRid") < events.first("ccm.log"), events.toString());
    }

    @Test
    void mixedStatementRelocatesOnlyTheGrowingRow() {
        storage.rows().put(R1, row(1, "al", 1f));
        storage.rows().put(R2, row(2, "alexander12", 2f));
        FakeChildOp child = child(row(1, "al", 1f), R1, row(2, "alexander12", 2f), R2);

        assertEquals(2L, setName(text("alexander"), child).execute());

        assertEquals(
                List.of(
                        "ccm.access WRITE t",
                        "sm.reserveRid",
                        "ccm.log DELETE lsn=100",
                        "sm.delete lsn=100",
                        "ccm.log INSERT lsn=101",
                        "sm.insert lsn=101",
                        "ccm.log UPDATE lsn=102",
                        "sm.update lsn=102"),
                events);
        assertEquals(row(2, "alexander", 2f), storage.rows().get(R2));
    }

    // failures we can foresee: nothing is logged and the transaction stays usable

    @Test
    void nullInNotNullColumnIsRejectedBeforeAnyLog() {
        storage.rows().put(R1, row(1, "alice", 1f));
        FakeChildOp child = child(row(1, "alice", 1f), R1);
        UpdateOp op = update("id", 0, new Expr.Literal(Value.nullOf(DataType.INTEGER)), child);

        assertThrows(StorageRuntimeException.class, op::execute);

        assertNothingChanged();
        assertChildClosed(child);
    }

    @Test
    void textLongerThanTheColumnIsRejectedBeforeAnyLogOrReservation() {
        storage.rows().put(R1, row(1, "alice", 1f));
        FakeChildOp child = child(row(1, "alice", 1f), R1);

        assertThrows(
                StorageRuntimeException.class,
                () -> setName(text("x".repeat(21)), child).execute()); // VARCHAR(20)

        assertNothingChanged();
        assertChildClosed(child);
    }

    @Test
    void evaluationErrorOnALaterRowLeavesEverythingUntouched() {
        storage.rows().put(R1, row(1, "a", 1f));
        storage.rows().put(R2, row(0, "b", 2f));
        FakeChildOp child = child(row(1, "a", 1f), R1, row(0, "b", 2f), R2);
        // SET score = 10 / id; the second row has id 0
        Expr tenOverId =
                new Expr.Binary(
                        Expr.Op.DIV,
                        new Expr.Literal(Value.ofInt(10)),
                        new Expr.ColumnRef(TABLE, "id", 0));

        assertThrows(ArithmeticException.class, update("score", 2, tenOverId, child)::execute);

        assertNothingChanged();
        assertEquals(row(1, "a", 1f), storage.rows().get(R1));
        assertChildClosed(child);
    }

    @Test
    void rowTooLargeToReserveReleasesEarlierReservations() {
        storage.rows().put(R1, row(1, "a", 1f));
        storage.rows().put(R2, row(2, "b", 2f));
        storage.failOn("reserveRid", 2, new StorageRuntimeException("row too large for a block"));
        FakeChildOp child = child(row(1, "a", 1f), R1, row(2, "b", 2f), R2);

        assertThrows(
                StorageRuntimeException.class, () -> setName(text("alexander"), child).execute());

        assertEquals(List.of(storage.reserved().getFirst()), storage.cancelled());
        assertNothingLoggedOrAborted();
        assertChildClosed(child);
    }

    @Test
    void storageFullOnReservationIsReportedAsInternal() {
        storage.rows().put(R1, row(1, "a", 1f));
        storage.failOn("reserveRid", 1, new StorageInternalException("no space left on device"));
        FakeChildOp child = child(row(1, "a", 1f), R1);

        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> setName(text("alexander"), child).execute());

        assertInstanceOf(StorageInternalException.class, e.getCause());
        assertNothingLoggedOrAborted();
    }

    @Test
    void encodeFailureOnAnInPlaceRowIsReportedBeforeAnyLog() {
        Row old = row(1, "alice", 1f);
        storage.rows().put(R1, old);
        storage.failOn("encodeRow", 1, new StorageRuntimeException("cannot encode row"));
        FakeChildOp child = child(old, R1);

        assertThrows(StorageRuntimeException.class, () -> setName(text("bob"), child).execute());

        assertEquals(List.of("ccm.access WRITE t", "sm.encodeRow (fails)"), events);
        assertEquals(TX, session.getCurrentTxId());
        assertEquals(old, storage.rows().get(R1));
        assertChildClosed(child);
    }

    @Test
    void encodeFailureOnARelocatedRowReleasesItsReservationBeforeAnyLog() {
        Row old = row(1, "al", 1f);
        storage.rows().put(R1, old);
        // 1st call encodes the DELETE's before image, 2nd the INSERT's after image
        storage.failOn("encodeRow", 2, new StorageRuntimeException("cannot encode row"));
        FakeChildOp child = child(old, R1);

        assertThrows(
                StorageRuntimeException.class, () -> setName(text("alexander"), child).execute());

        assertEquals(
                List.of(
                        "ccm.access WRITE t",
                        "sm.reserveRid",
                        "sm.encodeRow (fails)",
                        "sm.cancelReservation"),
                events);
        assertEquals(TX, session.getCurrentTxId());
        assertEquals(old, storage.rows().get(R1));
        assertChildClosed(child);
    }

    @Test
    void encodeFailureOnALaterRowReleasesEveryReservation() {
        storage.rows().put(R1, row(1, "a", 1f));
        storage.rows().put(R2, row(2, "b", 2f));
        // calls 1-2 belong to the first row; call 3 is the second row's before image
        storage.failOn("encodeRow", 3, new StorageRuntimeException("cannot encode row"));
        FakeChildOp child = child(row(1, "a", 1f), R1, row(2, "b", 2f), R2);

        assertThrows(
                StorageRuntimeException.class, () -> setName(text("alexander"), child).execute());

        assertEquals(2, storage.reserved().size(), "both rows reserved a slot");
        assertEquals(Set.copyOf(storage.reserved()), Set.copyOf(storage.cancelled()));
        assertNothingLoggedOrAborted();
        assertChildClosed(child);
    }

    // failures after a record is logged: abort

    @Test
    void updateFailingAfterLogAbortsTheTransaction() {
        storage.rows().put(R1, row(1, "alice", 1f));
        storage.rows().put(R2, row(2, "bobby", 2f));
        storage.rows().put(R3, row(3, "carol", 3f));
        storage.failOn("update", 2, new StorageInternalException("cannot write slot"));
        FakeChildOp child =
                child(row(1, "alice", 1f), R1, row(2, "bobby", 2f), R2, row(3, "carol", 3f), R3);

        TransactionAbortedException e =
                assertThrows(
                        TransactionAbortedException.class,
                        () -> setName(text("x"), child).execute());

        assertInstanceOf(StorageInternalException.class, e.getCause());
        assertEquals(
                List.of(
                        "ccm.access WRITE t",
                        "ccm.log UPDATE lsn=100",
                        "sm.update lsn=100",
                        "ccm.log UPDATE lsn=101",
                        "sm.update (fails)",
                        "ccm.abort T1"),
                events);
        assertNull(session.getCurrentTxId());
        assertChildClosed(child);
    }

    @Test
    void insertFailingDuringRelocationFreesTheSlotBeforeAborting() {
        storage.rows().put(R1, row(1, "al", 1f));
        storage.failOn("insert", 1, new StorageInternalException("cannot write block"));
        FakeChildOp child = child(row(1, "al", 1f), R1);

        assertThrows(
                TransactionAbortedException.class,
                () -> setName(text("alexander"), child).execute());

        assertNotEquals(-1, events.first("sm.cancelReservation"), events.toString());
        assertTrue(
                events.first("sm.cancelReservation") < events.first("ccm.abort"),
                events.toString());
        assertNull(session.getCurrentTxId());
        // cancelReservation is idempotent, so a second cancel in the finally block is fine
        assertTrue(storage.cancelled().stream().allMatch(storage.reserved().getFirst()::equals));
    }

    @Test
    void deleteFindingNoLiveRecordAbortsAndNeverLogsTheInsert() {
        // the child still sees R1, but it is gone from storage
        FakeChildOp child = child(row(1, "al", 1f), R1);

        assertThrows(
                TransactionAbortedException.class,
                () -> setName(text("alexander"), child).execute());

        assertEquals(0, events.count("ccm.log INSERT"));
        assertTrue(
                events.first("sm.cancelReservation") < events.first("ccm.abort"),
                events.toString());
        assertNull(session.getCurrentTxId());
    }

    @Test
    void logFailureAbortsAndReleasesTheReservations() {
        storage.rows().put(R1, row(1, "al", 1f));
        ccm.failNextLog();
        FakeChildOp child = child(row(1, "al", 1f), R1);

        assertThrows(
                TransactionAbortedException.class,
                () -> setName(text("alexander"), child).execute());

        assertEquals(0, events.count("sm.delete"));
        assertEquals(List.of(storage.reserved().getFirst()), storage.cancelled());
        assertEquals(1, events.count("ccm.abort"));
        assertNull(session.getCurrentTxId());
    }

    // CCM / session

    @Test
    void abortedByCcmClearsTheSessionAndTouchesNothing() {
        ccm.answerNext(AccessResult.aborted("deadlock victim"));
        FakeChildOp child = child(row(1, "alice", 1f), R1);

        assertThrows(TransactionAbortedException.class, () -> setName(text("x"), child).execute());

        assertEquals(List.of("ccm.access WRITE t", "ccm.abort T1"), events);
        assertNull(session.getCurrentTxId());
        assertEquals(0, child.getOpenCount(), "must not read after losing the transaction");
    }

    @Test
    void refusesToRunWithoutATransaction() {
        session.setCurrentTxId(null);

        assertThrows(
                IllegalStateException.class,
                () -> setName(text("x"), child(row(1, "a", 1f), R1)).execute());

        assertEquals(List.of(), events);
    }

    // helpers

    private void assertNothingChanged() {
        assertEquals(List.of("ccm.access WRITE t"), events, "only the access request may happen");
        assertEquals(TX, session.getCurrentTxId(), "the transaction must stay usable");
    }

    private void assertNothingLoggedOrAborted() {
        assertEquals(0, events.count("ccm.log"));
        assertEquals(0, events.count("ccm.abort"));
        assertEquals(TX, session.getCurrentTxId(), "the transaction must stay usable");
    }

    private static void assertChildClosed(FakeChildOp child) {
        assertEquals(child.getOpenCount(), child.getCloseCount(), "every open needs a close");
    }

    private Row decode(byte[] image) {
        return storage.decodeRow(image);
    }

    private static Row row(int id, String name, float score) {
        return new Row(
                List.of(
                        Value.ofInt(id),
                        Value.ofText(DataType.VARCHAR, name),
                        Value.ofFloat(score)));
    }

    private static Expr text(String s) {
        return new Expr.Literal(Value.ofText(DataType.VARCHAR, s));
    }

    /** Alternating {@code row, rid, row, rid, ...}. */
    private static FakeChildOp child(Object... rowsAndRids) {
        List<ResultRow> rows = new ArrayList<>();
        for (int i = 0; i < rowsAndRids.length; i += 2) {
            rows.add(new ResultRow(((Row) rowsAndRids[i]).values(), (Rid) rowsAndRids[i + 1]));
        }
        return new FakeChildOp(OP_SCHEMA, rows);
    }

    private UpdateOp setName(Expr value, FakeChildOp child) {
        return update("name", 1, value, child);
    }

    private UpdateOp update(String column, int offset, Expr value, FakeChildOp child) {
        return new UpdateOp(
                TABLE,
                new Expr.ColumnRef(TABLE, column, offset),
                value,
                child,
                new DefaultExpressionEvaluator(),
                ctx);
    }
}
