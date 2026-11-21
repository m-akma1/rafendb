package org.rafendb.processor.operator.index;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.IndexScanDescriptor;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.operator.FakeCcm;
import org.rafendb.processor.tuple.ResultRow;

/** IndexScanOp over the mock SM's sample data (mahasiswa_nim_idx, prodi_kode_idx). */
class IndexScanOpTest {
    private final MockStorageManager storage = new MockStorageManager();
    private final FakeCcm ccm = new FakeCcm();
    private final SessionContext session = new SessionContext(1);
    private final ExecutionContext context = new ExecutionContext(session, ccm, storage, null);

    IndexScanOpTest() {
        session.setCurrentTxId(new TxId(1));
    }

    @AfterEach
    void closeStorage() throws Exception {
        storage.close();
    }

    private IndexScanOp nimScan(Expr.Op op, int key) {
        Expr condition =
                new Expr.Binary(
                        op, new Expr.ColumnRef("m", "nim", 0), new Expr.Literal(Value.ofInt(key)));
        return new IndexScanOp(
                context,
                "mahasiswa",
                "m",
                new IndexScanDescriptor("mahasiswa_nim_idx", "nim", condition));
    }

    private static List<Integer> nims(IndexScanOp scan) {
        List<Integer> nims = new ArrayList<>();
        for (ResultRow row = scan.next(); row != null; row = scan.next()) {
            assertNotNull(row.rid());
            nims.add(row.get(0).asInt());
        }
        return nims;
    }

    @ParameterizedTest
    @CsvSource({
        "EQ, 13524275, 13524275",
        "GT, 13524276, 18224278 18224280",
        "GE, 13524276, 13524276 18224278 18224280",
        "LT, 13524276, 13224279 13524275",
        "LE, 13524275, 13224279 13524275"
    })
    void returnsTheRowsMatchingEachComparison(Expr.Op op, int key, String expected) {
        List<Integer> want = List.of(expected.split(" ")).stream().map(Integer::valueOf).toList();
        try (IndexScanOp scan = nimScan(op, key)) {
            scan.open();
            assertEquals(want, nims(scan));
        }
    }

    @Test
    void hashIndexAnswersEqualityOnText() {
        Expr condition =
                new Expr.Binary(
                        Expr.Op.EQ,
                        new Expr.ColumnRef("p", "kode", 0),
                        new Expr.Literal(Value.ofText(DataType.CHAR, "IF")));
        try (IndexScanOp scan =
                new IndexScanOp(
                        context,
                        "prodi",
                        "p",
                        new IndexScanDescriptor("prodi_kode_idx", "kode", condition))) {
            scan.open();
            assertEquals("IF", scan.next().get(0).asText());
            assertNull(scan.next());
        }
    }

    @Test
    void readLockIsTakenOnOpenAndAnAbortedLockLeavesTheScanClosed() {
        try (IndexScanOp scan = nimScan(Expr.Op.EQ, 13524275)) {
            scan.open();
            assertEquals(List.of("ccm.access READ mahasiswa"), ccm.events());

            ccm.answerNext(AccessResult.aborted("deadlock"));
            assertThrows(TransactionAbortedException.class, scan::open);
            assertThrows(IllegalStateException.class, scan::next);
            assertNull(session.getCurrentTxId());
        }
    }

    @Test
    void schemaUsesTheAlias() {
        try (IndexScanOp scan = nimScan(Expr.Op.EQ, 13524275)) {
            assertEquals(new Expr.ColumnRef("m", "nim"), scan.schema().get(0).reference());
        }
    }

    @Test
    void nextOutsideOpenIsRejectedAndReopenRestarts() {
        try (IndexScanOp scan = nimScan(Expr.Op.GE, 13524276)) {
            assertThrows(IllegalStateException.class, scan::next);
            scan.open();
            scan.next();
            scan.open();
            assertEquals(List.of(13524276, 18224278, 18224280), nims(scan));
            scan.close();
            assertThrows(IllegalStateException.class, scan::next);
        }
    }

    @Test
    void rejectsAComparisonAnIndexCannotAnswer() {
        try (IndexScanOp scan = nimScan(Expr.Op.NE, 13524275)) {
            assertThrows(IllegalArgumentException.class, scan::open);
        }
    }
}
