package org.rafendb.processor.operator.index;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.rafendb.common.expr.Expr;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.mock.concurrency.MockConcurrencyControl;
import org.rafendb.mock.recovery.MockRecoveryManager;

import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.tuple.ResultRow;

/**
 * Unit tests for the IndexScanOp operator.
 *
 * <p>These tests use MockStorageManager to verify equality searches,
 * range searches, HASH index lookups, schema handling, and operator lifecycle.
 */
class IndexScanOpTest {

    private MockStorageManager storage;
    private MockConcurrencyControl ccm;
    private MockRecoveryManager frm;

    private SessionContext session;
    private ExecutionContext context;

    /**
     * Initializes the mock components and starts a transaction before each test.
     */
    @BeforeEach
    void setUp() {
        storage = new MockStorageManager();
        ccm = new MockConcurrencyControl();
        frm = new MockRecoveryManager();

        session = new SessionContext(1);
        session.setCurrentTxId(ccm.begin());

        context = new ExecutionContext(
            session,
            ccm,
            storage,
            frm
        );
    }

    /**
     * Closes the mock storage manager after each test.
     */
    @AfterEach
    void tearDown() throws Exception {
        storage.close();
    }

    /**
     * Creates an IndexScanOp for the mahasiswa table.
     *
     * @param op the comparison operator
     * @param key the integer value used in the comparison
     * @return a configured index scan operator
     */
    private IndexScanOp createMahasiswaScan(Expr.Op op, int key) {

        Expr.ColumnRef column =
            new Expr.ColumnRef("m", "nim", 0);

        Expr.Literal literal =
            new Expr.Literal(Value.ofInt(key));

        Expr.Binary condition =
            new Expr.Binary(op, column, literal);

        var descriptor = new org.rafendb.common.plan.IndexScanDescriptor(
            "mahasiswa_nim_idx",
            "nim",
            condition
        );

        return new IndexScanOp(
            context,
            "mahasiswa",
            "m",
            descriptor
        );
    }

    /**
     * Extracts the NIM values from the returned rows.
     *
     * @param rows the result rows
     * @return a list containing the NIM values
     */
    private List<Integer> collectNim(IndexScanOp scan) {

        java.util.ArrayList<Integer> result = new java.util.ArrayList<>();

        ResultRow row;

        while ((row = scan.next()) != null) {
            result.add(row.get(0).asInt());
        }

        return result;
    }

    /**
     * Tests an equality lookup using a B+ Tree index.
     */
    @Test
    void testEqualityLookup() {

        IndexScanOp scan =
            createMahasiswaScan(Expr.Op.EQ, 13524275);

        scan.open();

        ResultRow result = scan.next();

        assertNotNull(result);
        assertEquals(13524275, result.get(0).asInt());
        assertEquals("John Doe", result.get(1).asText());
        assertNotNull(result.rid());

        assertNull(scan.next());

        scan.close();
    }

    /**
     * Tests a greater-than range lookup.
     */
    @Test
    void testGreaterThanLookup() {

        IndexScanOp scan =
            createMahasiswaScan(Expr.Op.GT, 13524276);

        scan.open();

        List<Integer> result = collectNim(scan);

        assertEquals(
            List.of(18224278, 18224280),
            result
        );

        scan.close();
    }

    /**
     * Tests a greater-than-or-equal range lookup.
     */
    @Test
    void testGreaterThanOrEqualLookup() {

        IndexScanOp scan =
            createMahasiswaScan(Expr.Op.GE, 13524276);

        scan.open();

        List<Integer> result = collectNim(scan);

        assertEquals(
            List.of(13524276, 18224278, 18224280),
            result
        );

        scan.close();
    }

    /**
     * Tests a less-than range lookup.
     */
    @Test
    void testLessThanLookup() {

        IndexScanOp scan =
            createMahasiswaScan(Expr.Op.LT, 13524276);

        scan.open();

        List<Integer> result = collectNim(scan);

        assertEquals(
            List.of(13224279, 13524275),
            result
        );

        scan.close();
    }

    /**
     * Tests a less-than-or-equal range lookup.
     */
    @Test
    void testLessThanOrEqualLookup() {

        IndexScanOp scan =
            createMahasiswaScan(Expr.Op.LE, 13524275);

        scan.open();

        List<Integer> result = collectNim(scan);

        assertEquals(
            List.of(13224279, 13524275),
            result
        );

        scan.close();
    }

    /**
     * Tests equality lookup using a HASH index.
     */
    @Test
    void testHashIndexEqualityLookup() {

        Expr.ColumnRef column =
            new Expr.ColumnRef("p", "kode", 0);

        Expr.Literal literal =
            new Expr.Literal(
                Value.ofText(DataType.CHAR, "IF")
            );

        Expr.Binary condition =
            new Expr.Binary(Expr.Op.EQ, column, literal);

        var descriptor = new org.rafendb.common.plan.IndexScanDescriptor(
            "prodi_kode_idx",
            "kode",
            condition
        );

        IndexScanOp scan = new IndexScanOp(
            context,
            "prodi",
            "p",
            descriptor
        );

        scan.open();

        ResultRow result = scan.next();

        assertNotNull(result);
        assertEquals("IF", result.get(0).asText());
        assertEquals("Teknik Informatika", result.get(1).asText());

        assertNull(scan.next());

        scan.close();
    }

    /**
     * Tests whether the output schema uses the specified table alias.
     */
    @Test
    void testSchemaUsesTableAlias() {

        IndexScanOp scan =
            createMahasiswaScan(Expr.Op.EQ, 13524275);

        assertEquals(
            "m",
            scan.schema().get(0).reference().table()
        );

        assertEquals(
            "nim",
            scan.schema().get(0).reference().column()
        );

        assertEquals(
            DataType.INTEGER,
            scan.schema().get(0).type()
        );
    }

    /**
     * Tests that next() cannot be called before open().
     */
    @Test
    void testNextBeforeOpenThrowsException() {

        IndexScanOp scan =
            createMahasiswaScan(Expr.Op.EQ, 13524275);

        assertThrows(
            IllegalStateException.class,
            scan::next
        );
    }

    /**
     * Tests that close() resets the operator state.
     */
    @Test
    void testCloseResetsOperatorState() {

        IndexScanOp scan =
            createMahasiswaScan(Expr.Op.EQ, 13524275);

        scan.open();
        scan.close();

        assertThrows(
            IllegalStateException.class,
            scan::next
        );

        scan.close();
    }

    /**
     * Tests that opening an operator again restarts the scan.
     */
    @Test
    void testOpenResetsScanPosition() {

        IndexScanOp scan =
            createMahasiswaScan(Expr.Op.EQ, 13524275);

        scan.open();

        ResultRow firstResult = scan.next();

        assertNotNull(firstResult);

        scan.open();

        ResultRow repeatedResult = scan.next();

        assertNotNull(repeatedResult);

        assertEquals(
            firstResult.get(0),
            repeatedResult.get(0)
        );

        assertEquals(
            firstResult.rid(),
            repeatedResult.rid()
        );

        scan.close();
    }

    /**
     * Tests that unsupported comparison operators are rejected.
     */
    @Test
    void testUnsupportedComparisonOperator() {

        IndexScanOp scan =
            createMahasiswaScan(Expr.Op.NE, 13524275);

        assertThrows(
            IllegalArgumentException.class,
            scan::open
        );

        scan.close();
    }
}
