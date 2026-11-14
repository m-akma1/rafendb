package org.rafendb.processor.operator.scan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.QueryExecutionException;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.operator.FakeCcm;
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
    private static final StoredRow BUDI =
            new StoredRow(
                    new Rid(1, 0),
                    new Row(List.of(Value.ofInt(1), Value.ofText(DataType.VARCHAR, "Budi"))));

    private final MockStorageManager mock = new MockStorageManager();
    private final FakeCcm ccm = new FakeCcm();
    private final SessionContext session = new SessionContext(1);

    TableScanOpTest() {
        session.setCurrentTxId(new TxId(1));
    }

    private ExecutionContext contextWith(StorageManager storage) {
        return new ExecutionContext(session, ccm, storage, null);
    }

    private static List<ResultRow> readAll(TableScanOp scan) {
        List<ResultRow> rows = new ArrayList<>();
        ResultRow row = scan.next();
        while (row != null) {
            rows.add(row);
            row = scan.next();
        }
        return rows;
    }

    @Test
    void readsAllRowsWithRid() {
        StoredRow ani =
                new StoredRow(
                        new Rid(1, 1),
                        new Row(List.of(Value.ofInt(2), Value.ofText(DataType.VARCHAR, "Ani"))));
        FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI, ani));
        TableScanOp scan = new TableScanOp(contextWith(storage), "dosen", null);
        List<ResultRow> expected =
                List.of(
                        new ResultRow(BUDI.row().values(), BUDI.rid()),
                        new ResultRow(ani.row().values(), ani.rid()));
        scan.open();
        assertEquals(expected, readAll(scan));
        assertNull(scan.next());
    }

    @Test
    void schemaFollowsCatalogOrder() {
        TableScanOp scan = new TableScanOp(contextWith(mock), "mahasiswa", null);
        List<ColumnSchema> expected =
                List.of(
                        new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nim"), DataType.INTEGER),
                        new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nama"), DataType.VARCHAR),
                        new ColumnSchema(new Expr.ColumnRef("mahasiswa", "ipk"), DataType.FLOAT),
                        new ColumnSchema(
                                new Expr.ColumnRef("mahasiswa", "kode_prodi"), DataType.CHAR));
        assertEquals(expected, scan.schema());
    }

    @Test
    void aliasBecomesQualifier() {
        TableScanOp scan = new TableScanOp(contextWith(mock), "mahasiswa", "m");
        assertEquals(4, scan.schema().size());
        for (ColumnSchema column : scan.schema()) {
            assertEquals("m", column.reference().table());
        }
    }

    @Test
    void emptyTableStillHasHeader() {
        FakeStorage storage = new FakeStorage(DOSEN, List.of());
        TableScanOp scan = new TableScanOp(contextWith(storage), "dosen", null);
        List<ColumnSchema> expected =
                List.of(
                        new ColumnSchema(new Expr.ColumnRef("dosen", "id"), DataType.INTEGER),
                        new ColumnSchema(new Expr.ColumnRef("dosen", "nama"), DataType.VARCHAR));
        scan.open();
        assertNull(scan.next());
        assertEquals(expected, scan.schema());
    }

    @Test
    void rescanGivesSameRows() {
        TableScanOp scan = new TableScanOp(contextWith(mock), "prodi", null);
        scan.open();
        List<ResultRow> first = readAll(scan);
        scan.open();
        List<ResultRow> second = readAll(scan);
        scan.close();
        scan.open();
        List<ResultRow> third = readAll(scan);
        assertEquals(3, first.size());
        assertEquals(first, second);
        assertEquals(first, third);
    }

    @Test
    void reopenClosesOldCursor() {
        FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI));
        TableScanOp scan = new TableScanOp(contextWith(storage), "dosen", null);
        scan.open();
        scan.next();
        scan.open();
        assertEquals(2, storage.getOpenedCursorCount());
        assertEquals(1, storage.getOpenCursorCount());
        assertEquals(BUDI.rid(), scan.next().rid());
    }

    @Test
    void closeClosesCursorAndIsSafeToRepeat() {
        FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI));
        TableScanOp scan = new TableScanOp(contextWith(storage), "dosen", null);
        scan.close();
        scan.open();
        scan.close();
        assertEquals(0, storage.getOpenCursorCount());
        scan.close();
        assertEquals(1, storage.getOpenedCursorCount());
    }

    @Test
    void nextThrowsWhenNotOpen() {
        TableScanOp scan = new TableScanOp(contextWith(mock), "prodi", null);
        assertThrows(IllegalStateException.class, scan::next);

        scan.open();
        scan.close();
        IllegalStateException error = assertThrows(IllegalStateException.class, scan::next);
        assertEquals("TableScanOp is not open", error.getMessage());
    }

    @Test
    void unknownTableIsRejected() {
        ExecutionContext context = contextWith(mock);
        StorageRuntimeException error =
                assertThrows(
                        StorageRuntimeException.class,
                        () -> new TableScanOp(context, "dosen", null));
        assertTrue(error.getMessage().contains("dosen"));
    }

    @Test
    void scanFailureIsWrappedAndOldCursorIsClosed() {
        FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI));
        TableScanOp scan = new TableScanOp(contextWith(storage), "dosen", null);
        scan.open();
        storage.failNextScan();
        QueryExecutionException error = assertThrows(QueryExecutionException.class, scan::open);
        assertEquals(
                "Runtime error at Query Processor: Failed to scan table dosen", error.getMessage());
        assertInstanceOf(StorageInternalException.class, error.getCause());
        assertEquals(0, storage.getOpenCursorCount());
        assertThrows(IllegalStateException.class, scan::next);
    }

    @Test
    void readFailureIsWrapped() {
        FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI));
        TableScanOp scan = new TableScanOp(contextWith(storage), "dosen", null);
        storage.failNextRead();
        scan.open();
        QueryExecutionException error = assertThrows(QueryExecutionException.class, scan::next);
        assertEquals(
                "Runtime error at Query Processor: Failed to read table dosen", error.getMessage());
        assertInstanceOf(StorageInternalException.class, error.getCause());
        scan.close();
        assertEquals(0, storage.getOpenCursorCount());
    }

    @Test
    void nullArgumentsAreRejected() {
        ExecutionContext context = contextWith(mock);
        ExecutionContext noStorage = contextWith(null);
        assertThrows(NullPointerException.class, () -> new TableScanOp(null, "prodi", null));
        assertThrows(NullPointerException.class, () -> new TableScanOp(context, null, null));
        assertThrows(NullPointerException.class, () -> new TableScanOp(noStorage, "prodi", null));
    }

    @Test
    void schemaIsAlwaysAvailable() {
        TableScanOp scan = new TableScanOp(contextWith(mock), "prodi", null);
        List<ColumnSchema> expected =
                List.of(
                        new ColumnSchema(new Expr.ColumnRef("prodi", "kode"), DataType.CHAR),
                        new ColumnSchema(new Expr.ColumnRef("prodi", "nama"), DataType.VARCHAR));
        assertEquals(expected, scan.schema());
        scan.open();
        scan.close();
        assertEquals(expected, scan.schema());
    }

    @Test
    void readLockIsRequestedBeforeScan() {
        FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI));
        storage.recordEventsTo(ccm.getEvents());
        TableScanOp scan = new TableScanOp(contextWith(storage), "dosen", null);
        scan.open();
        assertEquals(List.of("request READ dosen", "scan dosen"), ccm.getEvents());
    }

    @Test
    void abortedLockDoesNotOpenCursor() {
        FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI));
        TableScanOp scan = new TableScanOp(contextWith(storage), "dosen", null);
        ccm.answerNext(AccessResult.aborted("Deadlock"));
        assertThrows(TransactionAbortedException.class, scan::open);
        assertEquals(0, storage.getOpenedCursorCount());
        assertEquals(1, ccm.getAbortCount());
        assertNull(session.getCurrentTxId());
        assertThrows(IllegalStateException.class, scan::next);
    }

    @Test
    void noActiveTransactionIsRejectedBeforeScan() {
        FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI));
        TableScanOp scan = new TableScanOp(contextWith(storage), "dosen", null);
        session.setCurrentTxId(null);
        IllegalStateException error = assertThrows(IllegalStateException.class, scan::open);
        assertEquals(
                "No active transaction: run the statement inside AutoCommit.run(...) or after"
                        + " BEGIN",
                error.getMessage());
        assertEquals(0, storage.getOpenedCursorCount());
    }
}
