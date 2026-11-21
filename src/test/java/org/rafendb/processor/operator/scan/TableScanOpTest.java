package org.rafendb.processor.operator.scan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.error.QueryExecutionException;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.operator.EventLog;
import org.rafendb.processor.operator.FakeCcm;
import org.rafendb.processor.operator.FakeSession;
import org.rafendb.processor.operator.FakeStorage;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

class TableScanOpTest {
    private static final TableSchema DOSEN =
            new TableSchema(
                    "dosen",
                    List.of(
                            new Column("id", DataType.INTEGER, 0, false, true),
                            new Column("nama", DataType.VARCHAR, 20, true, false)));
    private static final StoredRow BUDI = row(0, 1, "Budi");
    private static final StoredRow ANI = row(1, 2, "Ani");

    private final EventLog events = new EventLog();
    private final FakeCcm ccm = new FakeCcm(events);
    private final FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI, ANI), events);
    private final FakeSession session = new FakeSession(ccm, storage);
    private final TableScanOp scan = new TableScanOp(session, "dosen", null);

    private static StoredRow row(int slot, int id, String nama) {
        return new StoredRow(
                new Rid(1, slot),
                new Row(List.of(Value.ofInt(id), Value.ofText(DataType.VARCHAR, nama))));
    }

    private List<ResultRow> readAll() {
        List<ResultRow> rows = new ArrayList<>();
        for (ResultRow row = scan.next(); row != null; row = scan.next()) {
            rows.add(row);
        }
        return rows;
    }

    @Test
    void readsEveryRowWithItsRidAndRescansFromTheStart() {
        List<ResultRow> expected =
                List.of(
                        new ResultRow(BUDI.row().values(), BUDI.rid()),
                        new ResultRow(ANI.row().values(), ANI.rid()));

        scan.open();
        assertEquals(expected, readAll());
        assertNull(scan.next());
        scan.open();
        assertEquals(expected, readAll());
        scan.close();
        scan.open();
        assertEquals(expected, readAll());
        scan.close();
    }

    @Test
    void readLockIsTakenBeforeTheScan() {
        scan.open();
        scan.close();

        assertEquals(List.of("ccm.access READ dosen", "sm.scan"), events);
    }

    @Test
    void schemaFollowsTheCatalogAndUsesTheAliasAsQualifier() {
        for (String alias : new String[] {null, "d"}) {
            String qualifier = alias == null ? "dosen" : alias;
            List<ColumnSchema> expected =
                    List.of(
                            new ColumnSchema(new Expr.ColumnRef(qualifier, "id"), DataType.INTEGER),
                            new ColumnSchema(
                                    new Expr.ColumnRef(qualifier, "nama"), DataType.VARCHAR));
            TableScanOp aliased = new TableScanOp(session, "dosen", alias);

            assertEquals(expected, aliased.schema());
            aliased.open();
            aliased.close();
            assertEquals(expected, aliased.schema());
        }
    }

    @Test
    void cursorLifecycle() {
        assertThrows(IllegalStateException.class, scan::next);
        scan.close();

        scan.open();
        scan.next();
        scan.open();
        assertEquals(2, storage.openedCursors());
        assertEquals(1, storage.openCursors(), "reopen closes the old cursor");
        assertEquals(BUDI.rid(), scan.next().rid());

        scan.close();
        scan.close();
        assertEquals(0, storage.openCursors());
        assertThrows(IllegalStateException.class, scan::next);
    }

    @Test
    void unknownTableIsRejected() {
        assertThrows(StorageRuntimeException.class, () -> new TableScanOp(session, "x", null));
    }

    @Test
    void scanFailureIsWrappedAndOldCursorIsClosed() {
        scan.open();
        storage.failOn("scan", 2, new StorageInternalException("disk read failed"));

        QueryExecutionException error = assertThrows(QueryExecutionException.class, scan::open);

        assertInstanceOf(StorageInternalException.class, error.getCause());
        assertEquals(0, storage.openCursors());
        assertThrows(IllegalStateException.class, scan::next);
    }

    @Test
    void readFailureIsWrapped() {
        storage.failNextRead();
        scan.open();

        QueryExecutionException error = assertThrows(QueryExecutionException.class, scan::next);

        assertInstanceOf(StorageInternalException.class, error.getCause());
        scan.close();
        assertEquals(0, storage.openCursors());
    }

    @Test
    void abortedLockOnReopenClosesTheOldCursorAndOpensNoNewOne() {
        scan.open();
        ccm.answerNext(AccessResult.aborted("deadlock"));

        assertThrows(TransactionAbortedException.class, scan::open);

        assertEquals(1, storage.openedCursors());
        assertEquals(0, storage.openCursors());
        assertThrows(IllegalStateException.class, scan::next);
    }

    @Test
    void noActiveTransactionIsRejectedBeforeTheScan() {
        session.setCurrentTx(null);

        assertThrows(IllegalStateException.class, scan::open);

        assertEquals(0, storage.openedCursors());
    }
}
