package org.rafendb.processor.operator.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.RowImageCodec;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.mock.processor.MockOperator;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/**
 * Behavior of UpdateOp against fake CCM and SM that record every call in one shared, ordered
 * event list. Event names:
 *
 * <pre>
 * ccm.access WRITE | ccm.log INSERT|UPDATE|DELETE lsn=N | ccm.log (fails) | ccm.abort
 * sm.reserve | sm.cancel | sm.update lsn=N | sm.delete lsn=N | sm.insert lsn=N
 * sm.&lt;method&gt; (fails)  -- an injected failure
 * </pre>
 */
class UpdateOpBehaviorTest {
    private static final String TABLE = "t";
    private static final TableSchema SCHEMA =
            new TableSchema(
                    TABLE,
                    List.of(
                            new Column("id", DataType.INTEGER, 0, false, true),
                            new Column("name", DataType.VARCHAR, 20, true, false),
                            new Column("score", DataType.FLOAT, 0, true, false)),
                    List.of());
    private static final List<ColumnSchema> OP_SCHEMA =
            List.of(
                    new ColumnSchema(new Expr.ColumnRef(TABLE, "id", 0), DataType.INTEGER),
                    new ColumnSchema(new Expr.ColumnRef(TABLE, "name", 1), DataType.VARCHAR),
                    new ColumnSchema(new Expr.ColumnRef(TABLE, "score", 2), DataType.FLOAT));

    private static final Rid R1 = new Rid(1, 0);
    private static final Rid R2 = new Rid(1, 1);
    private static final Rid R3 = new Rid(1, 2);
    private static final TxId TX = new TxId(1);

    private final List<String> events = new ArrayList<>();
    private final Ccm ccm = new Ccm(events);
    private final Storage storage = new Storage(events);
    private final SessionContext session = new SessionContext(1);
    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        session.setCurrentTxId(TX);
        StorageManager sm = (StorageManager) proxy(storage, StorageManager.class);
        ConcurrencyControl cc =
                (ConcurrencyControl) proxy(ccm, ConcurrencyControl.class, ConcurrencyLogging.class);
        ctx = new ExecutionContext(session, cc, sm, null);
    }

    // ================================================================ in place

    @Test
    void shorterValueIsUpdatedInPlace() {
        Row old = row(1, "alice", 1f);
        storage.rows.put(R1, old);
        TrackedOperator child = child(old, R1);

        long n = setName(text("bob"), child).execute();

        assertEquals(1L, n);
        assertEquals(
                List.of("ccm.access WRITE", "ccm.log UPDATE lsn=100", "sm.update lsn=100"), events);
        ChangeRecord record = ccm.logged.get(0);
        assertEquals(ResourceId.ofRow(TABLE, R1), record.resource());
        assertEquals(old.values(), decode(record.beforeImage()).values());
        assertEquals(row(1, "bob", 1f).values(), decode(record.afterImage()).values());
        assertEquals(row(1, "bob", 1f).values(), storage.rows.get(R1).values());
        assertChildClosed(child);
    }

    @Test
    void noMatchingRowsOnlyAsksForWriteAccess() {
        TrackedOperator child = child();

        assertEquals(0L, setName(text("x"), child).execute());

        assertEquals(List.of("ccm.access WRITE"), events);
        assertChildClosed(child);
    }

    @Test
    void waitsWhileCcmSaysWaiting() {
        storage.rows.put(R1, row(1, "alice", 1f));
        ccm.accessScript.add(AccessResult.waiting());
        ccm.accessScript.add(AccessResult.waiting());

        assertEquals(1L, setName(text("bob"), child(row(1, "alice", 1f), R1)).execute());

        assertEquals(3, count("ccm.access"));
        assertTrue(first("ccm.log") > last("ccm.access"));
    }

    // =========================================================== relocation

    @Test
    void growingValueIsRelocatedAsDeleteThenInsert() {
        Row old = row(1, "al", 1f);
        storage.rows.put(R1, old);
        TrackedOperator child = child(old, R1);

        long n = setName(text("alexander"), child).execute();

        assertEquals(1L, n); // one row, not two writes
        assertEquals(
                List.of(
                        "ccm.access WRITE",
                        "sm.reserve",
                        "ccm.log DELETE lsn=100",
                        "sm.delete lsn=100",
                        "ccm.log INSERT lsn=101",
                        "sm.insert lsn=101"),
                events);

        Rid fresh = storage.reserved.get(0);
        ChangeRecord delete = ccm.logged.get(0);
        ChangeRecord insert = ccm.logged.get(1);
        assertEquals(ResourceId.ofRow(TABLE, R1), delete.resource());
        assertEquals(old.values(), decode(delete.beforeImage()).values());
        assertTrue(delete.afterImage().isEmpty());
        assertEquals(ResourceId.ofRow(TABLE, fresh), insert.resource());
        assertTrue(insert.beforeImage().isEmpty());
        assertEquals(row(1, "alexander", 1f).values(), decode(insert.afterImage()).values());

        assertTrue(!storage.rows.containsKey(R1), "old Rid must be gone");
        assertEquals(row(1, "alexander", 1f).values(), storage.rows.get(fresh).values());
        assertChildClosed(child);
    }

    @Test
    void everySlotIsReservedBeforeTheFirstLogRecord() {
        storage.rows.put(R1, row(1, "a", 1f));
        storage.rows.put(R2, row(2, "b", 2f));
        TrackedOperator child = child(row(1, "a", 1f), R1, row(2, "b", 2f), R2);

        assertEquals(2L, setName(text("alexander"), child).execute());

        assertEquals(2, count("sm.reserve"));
        assertTrue(last("sm.reserve") < first("ccm.log"), events.toString());
    }

    @Test
    void mixedStatementRelocatesOnlyTheGrowingRow() {
        storage.rows.put(R1, row(1, "al", 1f));
        storage.rows.put(R2, row(2, "alexander12", 2f));
        TrackedOperator child = child(row(1, "al", 1f), R1, row(2, "alexander12", 2f), R2);

        assertEquals(2L, setName(text("alexander"), child).execute());

        assertEquals(
                List.of(
                        "ccm.access WRITE",
                        "sm.reserve",
                        "ccm.log DELETE lsn=100",
                        "sm.delete lsn=100",
                        "ccm.log INSERT lsn=101",
                        "sm.insert lsn=101",
                        "ccm.log UPDATE lsn=102",
                        "sm.update lsn=102"),
                events);
        assertEquals(row(2, "alexander", 2f).values(), storage.rows.get(R2).values());
    }

    // ================================ failures we can foresee: nothing is logged

    @Test
    void nullInNotNullColumnIsRejectedBeforeAnyLog() {
        storage.rows.put(R1, row(1, "alice", 1f));
        TrackedOperator child = child(row(1, "alice", 1f), R1);
        UpdateOp op = update("id", 0, new Expr.Literal(Value.nullOf(DataType.INTEGER)), child);

        assertThrows(StorageRuntimeException.class, op::execute);

        assertNothingChanged();
        assertChildClosed(child);
    }

    @Test
    void textLongerThanTheColumnIsRejectedBeforeAnyLogOrReservation() {
        storage.rows.put(R1, row(1, "alice", 1f));
        TrackedOperator child = child(row(1, "alice", 1f), R1);

        assertThrows(
                StorageRuntimeException.class,
                () -> setName(text("x".repeat(21)), child).execute()); // VARCHAR(20)

        assertNothingChanged();
        assertChildClosed(child);
    }

    @Test
    void evaluationErrorOnALaterRowLeavesEverythingUntouched() {
        storage.rows.put(R1, row(1, "a", 1f));
        storage.rows.put(R2, row(0, "b", 2f));
        TrackedOperator child = child(row(1, "a", 1f), R1, row(0, "b", 2f), R2);
        // SET score = 10 / id; the second row has id 0
        Expr tenOverId =
                new Expr.Binary(
                        Expr.Op.DIV,
                        new Expr.Literal(Value.ofInt(10)),
                        new Expr.ColumnRef(TABLE, "id", 0));
        UpdateOp op = update("score", 2, tenOverId, child);

        assertThrows(ArithmeticException.class, op::execute);

        assertNothingChanged();
        assertEquals(row(1, "a", 1f).values(), storage.rows.get(R1).values());
        assertChildClosed(child);
    }

    @Test
    void rowTooLargeToReserveReleasesEarlierReservationsAndKeepsTheTransaction() {
        storage.rows.put(R1, row(1, "a", 1f));
        storage.rows.put(R2, row(2, "b", 2f));
        storage.failOn(
                "reserveRid", 2, new StorageRuntimeException("row is too large for one block"));
        TrackedOperator child = child(row(1, "a", 1f), R1, row(2, "b", 2f), R2);

        assertThrows(
                StorageRuntimeException.class, () -> setName(text("alexander"), child).execute());

        assertEquals(List.of(storage.reserved.get(0)), storage.cancelled);
        assertEquals(0, count("ccm.log"));
        assertEquals(0, count("ccm.abort"));
        assertEquals(TX, session.getCurrentTxId(), "transaction must stay usable");
        assertChildClosed(child);
    }

    @Test
    void storageFullOnReservationIsReportedAndKeepsTheTransaction() {
        storage.rows.put(R1, row(1, "a", 1f));
        storage.failOn("reserveRid", 1, new StorageInternalException("no space left on device"));
        TrackedOperator child = child(row(1, "a", 1f), R1);

        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> setName(text("alexander"), child).execute());

        assertTrue(e.getCause() instanceof StorageInternalException);
        assertEquals(0, count("ccm.log"));
        assertEquals(0, count("ccm.abort"));
        assertEquals(TX, session.getCurrentTxId());
    }

    // ============================== failures after a record is logged: abort

    @Test
    void updateFailingAfterLogAbortsTheTransaction() {
        storage.rows.put(R1, row(1, "alice", 1f));
        storage.rows.put(R2, row(2, "bobby", 2f));
        storage.rows.put(R3, row(3, "carol", 3f));
        storage.failOn("update", 2, new StorageInternalException("cannot write slot"));
        TrackedOperator child =
                child(row(1, "alice", 1f), R1, row(2, "bobby", 2f), R2, row(3, "carol", 3f), R3);

        TransactionAbortedException e =
                assertThrows(
                        TransactionAbortedException.class,
                        () -> setName(text("x"), child).execute());

        assertTrue(e.getCause() instanceof StorageInternalException);
        assertEquals(
                List.of(
                        "ccm.access WRITE",
                        "ccm.log UPDATE lsn=100",
                        "sm.update lsn=100",
                        "ccm.log UPDATE lsn=101",
                        "sm.update (fails)",
                        "ccm.abort"),
                events);
        assertEquals(2, ccm.logged.size(), "the third row must never be logged");
        assertEquals(null, session.getCurrentTxId());
        assertChildClosed(child);
    }

    @Test
    void insertFailingDuringRelocationFreesTheSlotBeforeAborting() {
        storage.rows.put(R1, row(1, "al", 1f));
        storage.failOn("insert", 1, new StorageInternalException("cannot write block"));
        TrackedOperator child = child(row(1, "al", 1f), R1);

        assertThrows(
                TransactionAbortedException.class,
                () -> setName(text("alexander"), child).execute());

        assertTrue(first("sm.cancel") != -1, events.toString());
        assertTrue(first("sm.cancel") < first("ccm.abort"), "cancel before abort: " + events);
        assertEquals(null, session.getCurrentTxId());
        // cancelReservation is idempotent, so a second cancel in the finally block is fine
        assertTrue(storage.cancelled.stream().allMatch(r -> r.equals(storage.reserved.get(0))));
    }

    @Test
    void deleteFindingNoLiveRecordAbortsAndNeverLogsTheInsert() {
        storage.rows.put(R1, row(1, "al", 1f));
        storage.deleteFindsNothing = true;
        TrackedOperator child = child(row(1, "al", 1f), R1);

        assertThrows(
                TransactionAbortedException.class,
                () -> setName(text("alexander"), child).execute());

        assertEquals(0, count("ccm.log INSERT"));
        assertTrue(first("sm.cancel") < first("ccm.abort"), events.toString());
        assertEquals(null, session.getCurrentTxId());
    }

    @Test
    void logFailureAbortsAndReleasesTheReservations() {
        storage.rows.put(R1, row(1, "al", 1f));
        ccm.failLogAt = 1;
        TrackedOperator child = child(row(1, "al", 1f), R1);

        assertThrows(
                TransactionAbortedException.class,
                () -> setName(text("alexander"), child).execute());

        assertEquals(0, count("sm.delete"));
        assertEquals(List.of(storage.reserved.get(0)), storage.cancelled);
        assertEquals(1, count("ccm.abort"));
        assertEquals(null, session.getCurrentTxId());
    }

    // ============================================================ CCM / session

    @Test
    void abortedByCcmClearsTheSessionAndTouchesNothing() {
        ccm.accessScript.add(AccessResult.aborted("deadlock victim"));
        TrackedOperator child = child(row(1, "alice", 1f), R1);

        assertThrows(TransactionAbortedException.class, () -> setName(text("x"), child).execute());

        assertEquals(List.of("ccm.access WRITE", "ccm.abort"), events);
        assertEquals(null, session.getCurrentTxId());
        assertEquals(0, child.opens, "must not read after losing the transaction");
    }

    @Test
    void refusesToRunWithoutATransaction() {
        session.setCurrentTxId(null);

        assertThrows(
                IllegalStateException.class,
                () -> setName(text("x"), child(row(1, "a", 1f), R1)).execute());

        assertEquals(List.of(), events);
    }

    // ===================================================================== helpers

    private void assertNothingChanged() {
        assertEquals(List.of("ccm.access WRITE"), events, "only the access request may happen");
        assertEquals(TX, session.getCurrentTxId(), "the transaction must stay usable");
    }

    private static void assertChildClosed(TrackedOperator child) {
        assertEquals(
                child.opens,
                child.closes,
                "child opened " + child.opens + " closed " + child.closes);
    }

    private int first(String prefix) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }

    private int last(String prefix) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }

    private int count(String prefix) {
        return (int) events.stream().filter(e -> e.startsWith(prefix)).count();
    }

    private static Row row(int id, String name, float score) {
        return new Row(
                List.of(
                        Value.ofInt(id),
                        Value.ofText(DataType.VARCHAR, name),
                        Value.ofFloat(score)));
    }

    private static Row decode(Optional<byte[]> image) {
        return RowImageCodec.DEFAULT.decode(image.orElseThrow());
    }

    private static Expr text(String s) {
        return new Expr.Literal(Value.ofText(DataType.VARCHAR, s));
    }

    /** Alternating {@code row, rid, row, rid, ...}. */
    private static TrackedOperator child(Object... rowsAndRids) {
        List<ResultRow> rows = new ArrayList<>();
        for (int i = 0; i < rowsAndRids.length; i += 2) {
            rows.add(new ResultRow(((Row) rowsAndRids[i]).values(), (Rid) rowsAndRids[i + 1]));
        }
        return new TrackedOperator(new MockOperator(OP_SCHEMA, rows));
    }

    private UpdateOp setName(Expr value, Operator child) {
        return update("name", 1, value, child);
    }

    private UpdateOp update(String column, int offset, Expr value, Operator child) {
        return new UpdateOp(
                TABLE,
                new Expr.ColumnRef(TABLE, column, offset),
                value,
                child,
                new DefaultExpressionEvaluator(),
                ctx);
    }

    private static Object proxy(InvocationHandler handler, Class<?>... interfaces) {
        return Proxy.newProxyInstance(
                UpdateOpBehaviorTest.class.getClassLoader(), interfaces, handler);
    }

    private static String kind(ChangeRecord c) {
        if (c.beforeImage().isEmpty()) return "INSERT";
        if (c.afterImage().isEmpty()) return "DELETE";
        return "UPDATE";
    }

    // ================================================================= test doubles

    /** Counts open and close so tests can prove the child is always closed. */
    private static final class TrackedOperator implements Operator {
        private final MockOperator delegate;
        int opens;
        int closes;

        TrackedOperator(MockOperator delegate) {
            this.delegate = delegate;
        }

        @Override
        public void open() {
            opens++;
            delegate.open();
        }

        @Override
        public List<ColumnSchema> schema() {
            return delegate.schema();
        }

        @Override
        public ResultRow next() {
            return delegate.next();
        }

        @Override
        public void close() {
            closes++;
            delegate.close();
        }
    }

    private abstract static class Fake implements InvocationHandler {
        @Override
        public final Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> getClass().getSimpleName();
                };
            }
            return handle(m.getName(), args == null ? new Object[0] : args);
        }

        abstract Object handle(String name, Object[] args) throws Throwable;
    }

    /** CCM + ConcurrencyLogging. begin/commit are errors: UpdateOp must never call them. */
    private static final class Ccm extends Fake {
        final List<String> events;
        final Deque<AccessResult> accessScript = new ArrayDeque<>();
        final List<ChangeRecord> logged = new ArrayList<>();
        long nextLsn = 100;
        int logCalls;
        int failLogAt = -1; // 1-based index of the logChange call that fails

        Ccm(List<String> events) {
            this.events = events;
        }

        @Override
        Object handle(String name, Object[] a) throws Throwable {
            switch (name) {
                case "requestAccess":
                    events.add("ccm.access " + a[1]);
                    return accessScript.isEmpty() ? AccessResult.allowed() : accessScript.poll();
                case "logChange":
                    ChangeRecord c = (ChangeRecord) a[1];
                    if (++logCalls == failLogAt) {
                        events.add("ccm.log (fails)");
                        throw new ConcurrencyInternalException("log failed");
                    }
                    logged.add(c);
                    long lsn = nextLsn++;
                    events.add("ccm.log " + kind(c) + " lsn=" + lsn);
                    return lsn;
                case "abort":
                    events.add("ccm.abort");
                    return new TxResult(TxStatus.ABORTED, "");
                default:
                    throw new AssertionError("UpdateOp must not call ccm." + name);
            }
        }
    }

    /** In-memory storage; any method can be made to fail on its n-th call. */
    private static final class Storage extends Fake {
        private record Failure(int nth, Throwable error) {}

        final List<String> events;
        final Map<Rid, Row> rows = new LinkedHashMap<>();
        final List<Rid> reserved = new ArrayList<>();
        final List<Rid> cancelled = new ArrayList<>();
        final Map<String, Integer> calls = new HashMap<>();
        final Map<String, Failure> failures = new HashMap<>();
        boolean deleteFindsNothing;
        int nextSlot = 50;

        Storage(List<String> events) {
            this.events = events;
        }

        /** @param method SM method name, e.g. "reserveRid", "update", "delete", "insert" */
        void failOn(String method, int nth, Throwable error) {
            failures.put(method, new Failure(nth, error));
        }

        @Override
        Object handle(String name, Object[] a) throws Throwable {
            int n = calls.merge(name, 1, Integer::sum);
            Failure f = failures.get(name);
            if (f != null && f.nth() == n) {
                events.add("sm." + name + " (fails)");
                throw f.error();
            }
            switch (name) {
                case "getSchema":
                    return SCHEMA;
                case "reserveRid":
                    Rid fresh = new Rid(7, nextSlot++);
                    reserved.add(fresh);
                    events.add("sm.reserve");
                    return fresh;
                case "cancelReservation":
                    cancelled.add((Rid) a[1]);
                    events.add("sm.cancel");
                    return null;
                case "update":
                    rows.put((Rid) a[1], (Row) a[2]);
                    events.add("sm.update lsn=" + a[3]);
                    return null;
                case "delete":
                    events.add("sm.delete lsn=" + a[2]);
                    if (deleteFindsNothing) return false;
                    return rows.remove((Rid) a[1]) != null;
                case "insert":
                    rows.put((Rid) a[1], (Row) a[2]);
                    events.add("sm.insert lsn=" + a[3]);
                    return a[1];
                default:
                    throw new AssertionError("UpdateOp must not call sm." + name);
            }
        }
    }
}
