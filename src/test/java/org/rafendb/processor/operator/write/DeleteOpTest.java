package org.rafendb.processor.operator.write;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.error.QueryExecutionException;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.operator.EventLog;
import org.rafendb.processor.operator.FakeCcm;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.operator.FakeSession;
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

    private final EventLog events = new EventLog();
    private final FakeCcm ccm = new FakeCcm(events);
    private final FakeStorage storage = new FakeStorage(DOSEN, List.of(BUDI, ANI, CICI), events);
    private final FakeSession session = new FakeSession(ccm, storage);

    private static StoredRow row(int slot, int id, String nama) {
        return new StoredRow(
                new Rid(1, slot),
                new Row(List.of(Value.ofInt(id), Value.ofText(DataType.VARCHAR, nama))));
    }

    private DeleteOp deleteAll() {
        return new DeleteOp(session, new TableScanOp(session, "dosen", null), "dosen");
    }

    private DeleteOp deleteOne(ResultRow row) {
        return new DeleteOp(session, new FakeChildOp(List.of(), List.of(row)), "dosen");
    }

    @Test
    void deleteWithWhereRemovesOnlyMatchingRows() {
        FilterOp where =
                new FilterOp(
                        new TableScanOp(session, "dosen", null),
                        ID_IS_2,
                        new DefaultExpressionEvaluator());

        assertEquals(1L, new DeleteOp(session, where, "dosen").execute());

        assertEquals(List.of(BUDI.rid(), CICI.rid()), List.copyOf(storage.rows().keySet()));
    }

    @Test
    void locksThenScansThenLogsBeforeEachDelete() {
        assertEquals(3L, deleteAll().execute());

        assertEquals(
                List.of(
                        "ccm.access WRITE dosen",
                        "ccm.access READ dosen",
                        "sm.scan",
                        "ccm.log DELETE lsn=100",
                        "sm.delete lsn=100",
                        "ccm.log DELETE lsn=101",
                        "sm.delete lsn=101",
                        "ccm.log DELETE lsn=102",
                        "sm.delete lsn=102"),
                events);
        assertTrue(storage.rows().isEmpty());
    }

    @Test
    void logsFullBeforeImage() {
        deleteAll().execute();

        List<StoredRow> deleted = List.of(BUDI, ANI, CICI);
        assertEquals(3, ccm.logged().size());
        for (int i = 0; i < deleted.size(); i++) {
            ChangeRecord change = ccm.logged().get(i);
            assertEquals(deleted.get(i).rid(), change.resource().rid());
            assertEquals(deleted.get(i).row(), storage.decodeRow(change.beforeImage().get()));
            assertTrue(change.afterImage().isEmpty());
        }
    }

    @Test
    void abortedLockDeletesNothingAndClosesChild() {
        FakeChildOp child =
                new FakeChildOp(List.of(), List.of(new ResultRow(BUDI.row().values(), BUDI.rid())));
        ccm.answerNext(AccessResult.aborted("deadlock"));

        assertThrows(
                TransactionAbortedException.class, new DeleteOp(session, child, "dosen")::execute);

        assertEquals(1, child.getCloseCount());
        assertEquals(List.of("ccm.access WRITE dosen", "ccm.abort T1"), events);
        assertEquals(3, storage.rows().size());
    }

    @Test
    void logFailureKeepsRowAndAbortsTransaction() {
        ccm.failNextLog();

        assertThrows(TransactionAbortedException.class, deleteAll()::execute);

        assertEquals(3, storage.rows().size());
        assertEquals(1, events.count("ccm.abort"));
    }

    @Test
    void rowAlreadyGoneAbortsTransaction() {
        ResultRow ghost =
                new ResultRow(
                        List.of(Value.ofInt(9), Value.ofText(DataType.VARCHAR, "Hantu")),
                        new Rid(9, 9));

        TransactionAbortedException error =
                assertThrows(TransactionAbortedException.class, deleteOne(ghost)::execute);

        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals(1, events.count("ccm.abort"));
    }

    @Test
    void storageErrorAfterLogAbortsTransaction() {
        storage.failOn("delete", 1, new StorageInternalException("disk write failed"));

        TransactionAbortedException error =
                assertThrows(TransactionAbortedException.class, deleteAll()::execute);

        assertInstanceOf(StorageInternalException.class, error.getCause());
        assertEquals(3, storage.rows().size());
        assertEquals(1, events.count("ccm.abort"));
    }

    @Test
    void noActiveTransactionIsRejected() {
        session.setCurrentTx(null);

        assertThrows(IllegalStateException.class, deleteAll()::execute);

        assertEquals(List.of(), events);
    }

    @Test
    void rowWithoutRidIsRejectedAndChildIsClosed() {
        FakeChildOp child =
                new FakeChildOp(List.of(), List.of(new ResultRow(BUDI.row().values(), null)));

        assertThrows(IllegalStateException.class, new DeleteOp(session, child, "dosen")::execute);

        assertEquals(1, child.getCloseCount());
        assertEquals(3, storage.rows().size());
        assertTrue(ccm.logged().isEmpty());
    }

    @Test
    void readFailureDeletesNothingAndClosesCursor() {
        storage.failNextRead();

        assertThrows(QueryExecutionException.class, deleteAll()::execute);

        assertTrue(ccm.logged().isEmpty());
        assertEquals(0, storage.openCursors());
        assertEquals(3, storage.rows().size());
    }

    @Test
    void childIsClosedWhenOpenFails() {
        FakeChildOp child = new FakeChildOp(List.of(), List.of());
        child.failNextOpen();

        assertThrows(RuntimeException.class, new DeleteOp(session, child, "dosen")::execute);

        assertEquals(1, child.getCloseCount());
    }
}
