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
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.processor.error.QueryExecutionException;
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
        TableScanOp scan = new TableScanOp(storage, "dosen", null);
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
        TableScanOp scan = new TableScanOp(mock, "mahasiswa", null);
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
        TableScanOp scan = new TableScanOp(mock, "mahasiswa", "m");
        assertEquals(4, scan.schema().size());
        for (ColumnSchema column : scan.schema()) {
            assertEquals("m", column.reference().table());
        }
    }

    @Test
    void emptyTableStillHasHeader() {
        FakeStorage storage = new FakeStorage(DOSEN, List.of());
        TableScanOp scan = new TableScanOp(storage, "dosen", null);
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
        TableScanOp scan = new TableScanOp(mock, "prodi", null);
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
        TableScanOp scan = new TableScanOp(storage, "dosen", null);
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
        TableScanOp scan = new TableScanOp(storage, "dosen", null);
        scan.close();
        scan.open();
        scan.close();
        assertEquals(0, storage.getOpenCursorCount());
        scan.close();
        assertEquals(1, storage.getOpenedCursorCount());
    }

    @Test
    void nextThrowsWhenNotOpen() {
        TableScanOp scan = new TableScanOp(mock, "prodi", null);
        assertThrows(IllegalStateException.class, scan::next);

        scan.open();
        scan.close();
        IllegalStateException error = assertThrows(IllegalStateException.class, scan::next);
        assertEquals("TableScanOp is not open", error.getMessage());
    }

    @Test
    void unknownTableIsRejected() {
        StorageRuntimeException error =
                assertThrows(
                        StorageRuntimeException.class, () -> new TableScanOp(mock, "dosen", null));
        assertTrue(error.getMessage().contains("dosen"));
    }

    @Test
    void scanFailureIsWrappedAndOldCursorIsClosed() {
        FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI));
        TableScanOp scan = new TableScanOp(storage, "dosen", null);
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
        TableScanOp scan = new TableScanOp(storage, "dosen", null);
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
        assertThrows(NullPointerException.class, () -> new TableScanOp(null, "prodi", null));
        assertThrows(NullPointerException.class, () -> new TableScanOp(mock, null, null));
    }

    @Test
    void schemaIsAlwaysAvailable() {
        TableScanOp scan = new TableScanOp(mock, "prodi", null);
        List<ColumnSchema> expected =
                List.of(
                        new ColumnSchema(new Expr.ColumnRef("prodi", "kode"), DataType.CHAR),
                        new ColumnSchema(new Expr.ColumnRef("prodi", "nama"), DataType.VARCHAR));
        assertEquals(expected, scan.schema());
        scan.open();
        scan.close();
        assertEquals(expected, scan.schema());
    }
}
