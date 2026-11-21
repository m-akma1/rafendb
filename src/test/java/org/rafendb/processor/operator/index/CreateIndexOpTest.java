package org.rafendb.processor.operator.index;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.DataType;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.QueryExecutionException;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.operator.EventLog;
import org.rafendb.processor.operator.FakeCcm;
import org.rafendb.processor.operator.FakeStorage;
import org.rafendb.processor.plan.command.CreateIndexPlanNode;
import org.rafendb.processor.plan.command.CreateIndexPlanNode.IndexKind;

class CreateIndexOpTest {
    private static final TableSchema MAHASISWA =
            new TableSchema(
                    "mahasiswa", List.of(new Column("nama", DataType.VARCHAR, 50, true, false)));

    private final EventLog events = new EventLog();
    private final FakeCcm ccm = new FakeCcm(events);
    private final FakeStorage storage = new FakeStorage(MAHASISWA, List.of(), events);
    private final SessionContext session = new SessionContext(1);
    private final ExecutionContext context = new ExecutionContext(session, ccm, storage, null);

    CreateIndexOpTest() {
        session.setCurrentTxId(new TxId(1));
    }

    private CreateIndexOp create(IndexKind kind) {
        return new CreateIndexOp(
                context,
                new CreateIndexPlanNode(
                        "idx_nama", "mahasiswa", new Expr.ColumnRef("mahasiswa", "nama"), kind));
    }

    @ParameterizedTest
    @CsvSource({"BTREE, B_PLUS_TREE", "HASH, HASH"})
    void locksTheTableThenAsksStorageForTheMatchingIndexType(IndexKind kind, IndexType type) {
        assertEquals(0, create(kind).execute().affectedRows());

        assertEquals(List.of("ccm.access WRITE mahasiswa", "sm.createIndex"), events);
        assertEquals(
                List.of(new IndexInfo("idx_nama", "mahasiswa", "nama", type)), storage.indexes());
    }

    @Test
    void abortedLockCreatesNothing() {
        ccm.answerNext(AccessResult.aborted("deadlock"));

        assertThrows(TransactionAbortedException.class, create(IndexKind.BTREE)::execute);

        assertTrue(storage.indexes().isEmpty());
    }

    @Test
    void storageErrorsKeepTheirKind() {
        StorageRuntimeException rejected = new StorageRuntimeException("unknown column");
        storage.failOn("createIndex", 1, rejected);
        assertSame(
                rejected,
                assertThrows(StorageRuntimeException.class, create(IndexKind.BTREE)::execute));

        storage.failOn("createIndex", 2, new StorageInternalException("disk write failed"));
        QueryExecutionException wrapped =
                assertThrows(QueryExecutionException.class, create(IndexKind.BTREE)::execute);
        assertInstanceOf(StorageInternalException.class, wrapped.getCause());

        assertTrue(storage.indexes().isEmpty());
    }
}
