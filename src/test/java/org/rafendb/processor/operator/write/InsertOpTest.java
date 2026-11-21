package org.rafendb.processor.operator.write;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
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
import org.rafendb.api.storage.StorageQuery;
import org.rafendb.api.storage.StorageRecovery;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.TransactionAbortedException;

class InsertOpTest {

    private static final String TABLE = "t";
    private static final TxId TX = new TxId(1);

    private static final Row INSERTED_ROW =
            new Row(
                    List.of(
                            Value.ofInt(1),
                            Value.ofText(DataType.VARCHAR, "alice"),
                            Value.ofFloat(10f)));

    private static final Rid RID = new Rid(7, 50);

    private final List<String> events = new ArrayList<>();

    private final Ccm ccm = new Ccm(events);
    private final Storage storage = new Storage(events);

    private final SessionContext session = new SessionContext(1);

    /** What FRM would use to read logged images back; QP itself only gets StorageQuery. */
    private StorageRecovery recovery;

    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        session.setCurrentTxId(TX);

        Object sm = proxy(storage, StorageQuery.class, StorageRecovery.class);
        recovery = (StorageRecovery) sm;

        ConcurrencyControl cc =
                (ConcurrencyControl) proxy(ccm, ConcurrencyControl.class, ConcurrencyLogging.class);

        ctx = new ExecutionContext(session, cc, (StorageQuery) sm, null);
    }

    // ============================================================
    // Successful execution
    // ============================================================

    @Test
    void insertsRowSuccessfully() {
        InsertOp op = new InsertOp(TABLE, INSERTED_ROW.values(), ctx);

        Long result = op.execute();

        assertEquals(1L, result);

        assertEquals(
                List.of(
                        "ccm.access WRITE",
                        "sm.reserve",
                        "ccm.log INSERT lsn=100",
                        "sm.insert lsn=100"),
                events);

        assertEquals(1, ccm.logged.size());

        ChangeRecord record = ccm.logged.get(0);

        assertEquals(ResourceId.ofRow(TABLE, RID), record.resource());

        assertTrue(record.beforeImage().isEmpty());
        assertTrue(record.afterImage().isPresent());

        assertEquals(INSERTED_ROW.values(), decode(record.afterImage()).values());

        assertEquals(INSERTED_ROW.values(), storage.rows.get(RID).values());

        assertTrue(storage.cancelled.isEmpty());
        assertEquals(TX, session.getCurrentTxId());
    }

    @Test
    void waitsUntilWriteAccessIsGranted() {
        ccm.accessScript.add(AccessResult.waiting());
        ccm.accessScript.add(AccessResult.waiting());

        InsertOp op = new InsertOp(TABLE, INSERTED_ROW.values(), ctx);

        assertEquals(1L, op.execute());

        assertEquals(3, events.stream().filter(e -> e.equals("ccm.access WRITE")).count());

        assertTrue(first("sm.reserve") > last("ccm.access"), events.toString());
    }

    // ============================================================
    // Transaction validation
    // ============================================================

    @Test
    void refusesToInsertWithoutActiveTransaction() {
        session.setCurrentTxId(null);

        InsertOp op = new InsertOp(TABLE, INSERTED_ROW.values(), ctx);

        assertThrows(IllegalStateException.class, op::execute);

        assertTrue(events.isEmpty());
        assertTrue(storage.rows.isEmpty());
        assertTrue(storage.reserved.isEmpty());
        assertTrue(ccm.logged.isEmpty());
    }

    @Test
    void abortsWhenCcmRejectsWriteAccess() {
        ccm.accessScript.add(AccessResult.aborted("deadlock victim"));

        InsertOp op = new InsertOp(TABLE, INSERTED_ROW.values(), ctx);

        assertThrows(TransactionAbortedException.class, op::execute);

        assertEquals(List.of("ccm.access WRITE", "ccm.abort"), events);

        assertNull(session.getCurrentTxId());

        assertTrue(storage.reserved.isEmpty());
        assertTrue(storage.rows.isEmpty());
        assertTrue(ccm.logged.isEmpty());
    }

    // ============================================================
    // Reservation failures
    // ============================================================

    @Test
    void reservationFailureDoesNotLogOrAbortTransaction() {
        storage.failOn("reserveRid", 1, new StorageInternalException("no space left"));

        InsertOp op = new InsertOp(TABLE, INSERTED_ROW.values(), ctx);

        IllegalStateException exception = assertThrows(IllegalStateException.class, op::execute);

        assertInstanceOf(StorageInternalException.class, exception.getCause());

        assertEquals(List.of("ccm.access WRITE", "sm.reserveRid (fails)"), events);

        assertTrue(ccm.logged.isEmpty());
        assertTrue(storage.rows.isEmpty());
        assertTrue(storage.cancelled.isEmpty());

        assertEquals(TX, session.getCurrentTxId());
    }

    @Test
    void rowValidationFailureKeepsTransactionActive() {
        storage.failOn("reserveRid", 1, new StorageRuntimeException("row is too large"));

        InsertOp op = new InsertOp(TABLE, INSERTED_ROW.values(), ctx);

        assertThrows(StorageRuntimeException.class, op::execute);

        assertEquals(0, ccm.logged.size());
        assertTrue(storage.rows.isEmpty());
        assertTrue(storage.cancelled.isEmpty());

        assertEquals(TX, session.getCurrentTxId());
    }

    // ============================================================
    // Encoding and logging failures
    // ============================================================

    @Test
    void encodeFailureCancelsReservationWithoutAborting() {
        storage.failOn("encodeRow", 1, new StorageRuntimeException("cannot encode row"));

        InsertOp op = new InsertOp(TABLE, INSERTED_ROW.values(), ctx);

        assertThrows(StorageRuntimeException.class, op::execute);

        assertEquals(
                List.of("ccm.access WRITE", "sm.reserve", "sm.encodeRow (fails)", "sm.cancel"),
                events);

        assertEquals(List.of(RID), storage.reserved);
        assertEquals(List.of(RID), storage.cancelled);

        assertTrue(ccm.logged.isEmpty());
        assertTrue(storage.rows.isEmpty());

        assertEquals(TX, session.getCurrentTxId());
    }

    @Test
    void logFailureCancelsReservationAndAbortsTransaction() {
        ccm.failLogAt = 1;

        InsertOp op = new InsertOp(TABLE, INSERTED_ROW.values(), ctx);

        assertThrows(TransactionAbortedException.class, op::execute);

        assertEquals(
                List.of(
                        "ccm.access WRITE",
                        "sm.reserve",
                        "ccm.log (fails)",
                        "ccm.abort",
                        "sm.cancel"),
                events);

        assertEquals(List.of(RID), storage.reserved);
        assertEquals(List.of(RID), storage.cancelled);

        assertTrue(storage.rows.isEmpty());
        assertTrue(ccm.logged.isEmpty());

        assertNull(session.getCurrentTxId());
    }

    // ============================================================
    // Storage insertion failure
    // ============================================================

    @Test
    void storageInsertFailureCancelsReservationAndAbortsTransaction() {
        storage.failOn("insert", 1, new StorageInternalException("cannot write block"));

        InsertOp op = new InsertOp(TABLE, INSERTED_ROW.values(), ctx);

        TransactionAbortedException exception =
                assertThrows(TransactionAbortedException.class, op::execute);

        assertInstanceOf(StorageInternalException.class, exception.getCause());

        assertEquals(
                List.of(
                        "ccm.access WRITE",
                        "sm.reserve",
                        "ccm.log INSERT lsn=100",
                        "sm.insert (fails)",
                        "sm.cancel",
                        "ccm.abort"),
                events);

        assertEquals(1, ccm.logged.size());

        assertEquals(List.of(RID), storage.reserved);
        assertEquals(List.of(RID), storage.cancelled);

        assertTrue(storage.rows.isEmpty());

        assertNull(session.getCurrentTxId());
    }

    // ============================================================
    // Helpers
    // ============================================================

    private int first(String prefix) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private int last(String prefix) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private Row decode(Optional<byte[]> image) {
        try {
            return recovery.decodeRow(TABLE, image.orElseThrow());
        } catch (StorageInternalException e) {
            throw new AssertionError(e);
        }
    }

    private static String kind(ChangeRecord record) {
        if (record.beforeImage().isEmpty()) {
            return "INSERT";
        }
        if (record.afterImage().isEmpty()) {
            return "DELETE";
        }
        return "UPDATE";
    }

    private static Object proxy(InvocationHandler handler, Class<?>... interfaces) {

        return Proxy.newProxyInstance(InsertOpTest.class.getClassLoader(), interfaces, handler);
    }

    // ============================================================
    // Fake invocation handler
    // ============================================================

    private abstract static class Fake implements InvocationHandler {

        @Override
        public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> getClass().getSimpleName();
                };
            }

            return handle(method.getName(), args == null ? new Object[0] : args);
        }

        abstract Object handle(String name, Object[] args) throws Throwable;
    }

    // ============================================================
    // Fake CCM
    // ============================================================

    private static final class Ccm extends Fake {

        final List<String> events;

        final Deque<AccessResult> accessScript = new ArrayDeque<>();

        final List<ChangeRecord> logged = new ArrayList<>();

        long nextLsn = 100;

        int logCalls;

        int failLogAt = -1;

        Ccm(List<String> events) {
            this.events = events;
        }

        @Override
        Object handle(String name, Object[] args) throws ConcurrencyInternalException {

            switch (name) {
                case "requestAccess" -> {
                    events.add("ccm.access " + args[1]);

                    return accessScript.isEmpty() ? AccessResult.allowed() : accessScript.poll();
                }

                case "logChange" -> {
                    ChangeRecord record = (ChangeRecord) args[1];

                    if (++logCalls == failLogAt) {
                        events.add("ccm.log (fails)");

                        throw new ConcurrencyInternalException("log failed");
                    }

                    logged.add(record);

                    long lsn = nextLsn++;

                    events.add("ccm.log " + kind(record) + " lsn=" + lsn);

                    return lsn;
                }

                case "abort" -> {
                    events.add("ccm.abort");

                    return new TxResult(TxStatus.ABORTED, "");
                }

                default -> throw new AssertionError("Unexpected CCM method: " + name);
            }
        }
    }

    // ============================================================
    // Fake storage (StorageQuery, plus decodeRow for reading the log)
    // ============================================================

    private static final class Storage extends Fake {

        private record Failure(int nth, Throwable error) {}

        final List<String> events;

        final Map<Rid, Row> rows = new LinkedHashMap<>();

        final List<Rid> reserved = new ArrayList<>();

        final List<Rid> cancelled = new ArrayList<>();

        final Map<String, Row> images = new HashMap<>();

        final Map<String, Integer> calls = new HashMap<>();

        final Map<String, Failure> failures = new HashMap<>();

        int nextSlot = 50;

        Storage(List<String> events) {
            this.events = events;
        }

        void failOn(String method, int nth, Throwable error) {

            failures.put(method, new Failure(nth, error));
        }

        @Override
        Object handle(String name, Object[] args) throws Throwable {

            int callNumber = calls.merge(name, 1, Integer::sum);

            Failure failure = failures.get(name);

            if (failure != null && failure.nth() == callNumber) {

                events.add("sm." + name + " (fails)");

                throw failure.error();
            }

            switch (name) {
                case "reserveRid" -> {
                    Rid rid = new Rid(7, nextSlot++);

                    reserved.add(rid);

                    events.add("sm.reserve");

                    return rid;
                }

                case "cancelReservation" -> {
                    cancelled.add((Rid) args[1]);

                    events.add("sm.cancel");

                    return null;
                }

                case "encodeRow" -> {
                    String key = "image-" + images.size();

                    images.put(key, (Row) args[1]);

                    return key.getBytes(StandardCharsets.UTF_8);
                }

                case "decodeRow" -> {
                    String key = new String((byte[]) args[1], StandardCharsets.UTF_8);

                    Row row = images.get(key);

                    if (row == null) {
                        throw new StorageInternalException("Invalid image");
                    }

                    return row;
                }

                case "insert" -> {
                    Rid rid = (Rid) args[1];

                    Row row = (Row) args[2];

                    long lsn = (long) args[3];

                    rows.put(rid, row);

                    events.add("sm.insert lsn=" + lsn);

                    return rid;
                }

                default -> throw new AssertionError("Unexpected storage method: " + name);
            }
        }
    }
}
