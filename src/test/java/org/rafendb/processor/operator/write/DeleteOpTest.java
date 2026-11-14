package org.rafendb.processor.operator.write;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.RowImageCodec;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.operator.FakeCcm;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.operator.FakeStorage;
import org.rafendb.processor.operator.filter.FilterOp;
import org.rafendb.processor.operator.scan.TableScanOp;
import org.rafendb.processor.tuple.ResultRow;

class DeleteOpTest {
    private static final TableSchema DOSEN =
            new TableSchema(
                    "dosen",
                    List.of(
                            new Column("id", DataType.INTEGER, 0, false, true),
                            new Column("nama", DataType.VARCHAR, 20, true, false)));
    private static final StoredRow BUDI = row(0, 1, "Budi");
    private static final StoredRow ANI = row(1, 2, "Ani");
    private static final StoredRow CICI = row(2, 3, "Cici");

    /** id = 2 */
    private static final Expr ID_IS_2 =
            new Expr.Binary(
                    Expr.Op.EQ,
                    new Expr.ColumnRef("dosen", "id", 0),
                    new Expr.Literal(Value.ofInt(2)));

    private final FakeCcm ccm = new FakeCcm();
    private final SessionContext session = new SessionContext(1);
    private final FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI, ANI, CICI));
    private final ExecutionContext context = new ExecutionContext(session, ccm, storage, null);

    DeleteOpTest() {
        session.setCurrentTxId(new TxId(1));
        storage.recordEventsTo(ccm.getEvents());
    }

    private static StoredRow row(int slot, int id, String nama) {
        return new StoredRow(
                new Rid(1, slot),
                new Row(List.of(Value.ofInt(id), Value.ofText(DataType.VARCHAR, nama))));
    }

    private TableScanOp scanDosen() {
        return new TableScanOp(context, "dosen", null);
    }

    /** Id yang masih ada di tabel, dibaca lewat scan baru. */
    private List<Value> remainingIds() {
        TableScanOp scan = scanDosen();
        List<Value> ids = new ArrayList<>();
        scan.open();
        ResultRow row = scan.next();
        while (row != null) {
            ids.add(row.get(0));
            row = scan.next();
        }
        scan.close();
        return ids;
    }

    @Test
    void deleteWithWhereRemovesOnlyMatchingRows() {
        FilterOp where = new FilterOp(scanDosen(), ID_IS_2, new DefaultExpressionEvaluator());
        DeleteOp delete = new DeleteOp(context, where, "dosen");
        assertEquals(Long.valueOf(1), delete.execute());
        assertEquals(List.of(Value.ofInt(1), Value.ofInt(3)), remainingIds());
    }

    @Test
    void deleteAllReturnsCountAndRowsAreGone() {
        DeleteOp delete = new DeleteOp(context, scanDosen(), "dosen");
        assertEquals(Long.valueOf(3), delete.execute());
        assertTrue(remainingIds().isEmpty());
    }

    @Test
    void locksThenScansThenLogsBeforeEachDelete() {
        DeleteOp delete = new DeleteOp(context, scanDosen(), "dosen");
        delete.execute();
        List<String> expected =
                List.of(
                        "request WRITE dosen",
                        "request READ dosen",
                        "scan dosen",
                        "log dosen " + BUDI.rid(),
                        "delete dosen " + BUDI.rid() + " lsn=1",
                        "log dosen " + ANI.rid(),
                        "delete dosen " + ANI.rid() + " lsn=2",
                        "log dosen " + CICI.rid(),
                        "delete dosen " + CICI.rid() + " lsn=3");
        assertEquals(expected, ccm.getEvents());
    }

    @Test
    void deleteUsesLsnFromLogAndLogsBeforeImage() {
        DeleteOp delete = new DeleteOp(context, scanDosen(), "dosen");
        delete.execute();
        assertTrue(ccm.getEvents().contains("delete dosen " + BUDI.rid() + " lsn=1"));
        assertTrue(ccm.getEvents().contains("delete dosen " + ANI.rid() + " lsn=2"));
        assertTrue(ccm.getEvents().contains("delete dosen " + CICI.rid() + " lsn=3"));

        List<StoredRow> deletedRows = List.of(BUDI, ANI, CICI);
        List<ChangeRecord> logged = ccm.getLoggedChanges();
        assertEquals(3, logged.size());
        for (int i = 0; i < deletedRows.size(); i++) {
            ChangeRecord change = logged.get(i);
            Row before = RowImageCodec.DEFAULT.decode(change.beforeImage().get());
            assertEquals(deletedRows.get(i).rid(), change.resource().rid());
            assertEquals(deletedRows.get(i).row(), before);
            assertTrue(change.afterImage().isEmpty());
        }
    }

    @Test
    void abortedLockDeletesNothing() {
        ccm.answerNext(AccessResult.aborted("Deadlock"));
        DeleteOp delete = new DeleteOp(context, scanDosen(), "dosen");
        assertThrows(TransactionAbortedException.class, delete::execute);
        assertEquals(3, storage.getRowCount());
        assertEquals(0, storage.getOpenedCursorCount());
        assertEquals(1, ccm.getAbortCount());
    }

    @Test
    void logFailureKeepsRowAndAbortsTransaction() {
        ccm.failNextLog();
        DeleteOp delete = new DeleteOp(context, scanDosen(), "dosen");
        assertThrows(TransactionAbortedException.class, delete::execute);
        assertEquals(3, storage.getRowCount());
        assertFalse(ccm.getEvents().contains("delete dosen " + BUDI.rid()));
        assertEquals(1, ccm.getAbortCount());
        assertNull(session.getCurrentTxId());
    }

    @Test
    void rowAlreadyGoneAbortsTransaction() {
        ResultRow ghost =
                new ResultRow(
                        List.of(Value.ofInt(9), Value.ofText(DataType.VARCHAR, "Hantu")),
                        new Rid(9, 9));
        FakeChildOp child = new FakeChildOp(List.of(), List.of(ghost));
        DeleteOp delete = new DeleteOp(context, child, "dosen");
        TransactionAbortedException error =
                assertThrows(TransactionAbortedException.class, delete::execute);
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals("Row " + ghost.rid() + " was already gone", error.getCause().getMessage());
        assertEquals(1, ccm.getAbortCount());
    }

    @Test
    void storageErrorAfterLogAbortsTransaction() {
        storage.failNextDelete();
        DeleteOp delete = new DeleteOp(context, scanDosen(), "dosen");
        TransactionAbortedException error =
                assertThrows(TransactionAbortedException.class, delete::execute);
        assertInstanceOf(StorageInternalException.class, error.getCause());
        assertEquals(3, storage.getRowCount());
        assertEquals(1, ccm.getAbortCount());
    }

    @Test
    void noActiveTransactionIsRejected() {
        session.setCurrentTxId(null);
        DeleteOp delete = new DeleteOp(context, scanDosen(), "dosen");
        IllegalStateException error = assertThrows(IllegalStateException.class, delete::execute);
        assertEquals(
                "No active transaction: run DML inside AutoCommit.run(...) or after BEGIN",
                error.getMessage());
        assertTrue(ccm.getEvents().isEmpty());
    }

    @Test
    void rowWithoutRidIsRejectedAndChildIsClosed() {
        ResultRow noRid = new ResultRow(BUDI.row().values(), null);
        FakeChildOp child = new FakeChildOp(List.of(), List.of(noRid));
        DeleteOp delete = new DeleteOp(context, child, "dosen");
        IllegalStateException error = assertThrows(IllegalStateException.class, delete::execute);
        assertEquals("DeleteOp needs rows with RID", error.getMessage());
        assertEquals(1, child.getCloseCount());
        assertEquals(3, storage.getRowCount());
    }

    @Test
    void childIsClosedWhenLockFails() {
        FakeChildOp child = new FakeChildOp(List.of(), List.of());
        ccm.answerNext(AccessResult.aborted("Deadlock"));
        DeleteOp delete = new DeleteOp(context, child, "dosen");
        assertThrows(TransactionAbortedException.class, delete::execute);
        assertEquals(1, child.getCloseCount());
    }

    @Test
    void childIsClosedWhenOpenFails() {
        FakeChildOp child = new FakeChildOp(List.of(), List.of());
        child.failNextOpen();
        DeleteOp delete = new DeleteOp(context, child, "dosen");
        assertThrows(RuntimeException.class, delete::execute);
        assertEquals(1, child.getCloseCount());
    }

    @Test
    void emptyChildDeletesNothing() {
        FakeChildOp child = new FakeChildOp(List.of(), List.of());
        DeleteOp delete = new DeleteOp(context, child, "dosen");
        assertEquals(Long.valueOf(0), delete.execute());
    }

    @Test
    void nullArgumentsAreRejected() {
        FakeChildOp child = new FakeChildOp(List.of(), List.of());
        assertThrows(NullPointerException.class, () -> new DeleteOp(null, child, "dosen"));
        assertThrows(NullPointerException.class, () -> new DeleteOp(context, null, "dosen"));
        assertThrows(NullPointerException.class, () -> new DeleteOp(context, child, null));
    }
}
