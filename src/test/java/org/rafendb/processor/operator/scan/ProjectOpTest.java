package org.rafendb.processor.operator.scan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

class ProjectOpTest {
    private static final List<ColumnSchema> SCHEMA =
            List.of(
                    new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nim"), DataType.INTEGER),
                    new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nama"), DataType.VARCHAR),
                    new ColumnSchema(new Expr.ColumnRef("mahasiswa", "ipk"), DataType.FLOAT));

    private static final Expr NIM = new Expr.ColumnRef("mahasiswa", "nim", 0);
    private static final Expr NAMA = new Expr.ColumnRef("mahasiswa", "nama", 1);
    private static final Expr IPK = new Expr.ColumnRef("mahasiswa", "ipk", 2);
    private static final ResultRow BUDI = row(1, "Budi", 3.5f);

    private static ResultRow row(int nim, String nama, float ipk) {
        return new ResultRow(
                List.of(Value.ofInt(nim), Value.ofText(DataType.VARCHAR, nama), Value.ofFloat(ipk)),
                new Rid(1, nim));
    }

    @Test
    void selectsColumnsInWrittenOrder() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(BUDI));
        ProjectOp project = new ProjectOp(child, List.of(NIM, IPK));
        project.open();
        assertEquals(List.of(Value.ofInt(1), Value.ofFloat(3.5f)), project.next().values());
        assertNull(project.next());
        project.close();
    }

    @Test
    void selectsColumnsInReversedOrder() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(BUDI));
        ProjectOp project = new ProjectOp(child, List.of(IPK, NIM));
        List<ColumnSchema> expected =
                List.of(
                        new ColumnSchema(new Expr.ColumnRef("mahasiswa", "ipk"), DataType.FLOAT),
                        new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nim"), DataType.INTEGER));
        assertEquals(expected, project.schema());
        project.open();
        assertEquals(List.of(Value.ofFloat(3.5f), Value.ofInt(1)), project.next().values());
        project.close();
    }

    @Test
    void sameColumnCanBeSelectedTwice() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(BUDI));
        ProjectOp project = new ProjectOp(child, List.of(NAMA, NAMA));
        project.open();
        Value budi = Value.ofText(DataType.VARCHAR, "Budi");
        assertEquals(List.of(budi, budi), project.next().values());
        project.close();
    }

    @Test
    void selectAllKeepsTableOrder() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(BUDI));
        ProjectOp project = new ProjectOp(child, List.of(NIM, NAMA, IPK));
        project.open();
        assertEquals(BUDI.values(), project.next().values());
        assertEquals(
                List.of(
                        new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nim"), DataType.INTEGER),
                        new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nama"), DataType.VARCHAR),
                        new ColumnSchema(new Expr.ColumnRef("mahasiswa", "ipk"), DataType.FLOAT)),
                project.schema());
        project.close();
    }

    @Test
    void qualifierFromBindingIsKept() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of());
        Expr withAlias = new Expr.ColumnRef("m", "nim", 0);
        Expr nullQualifier = new Expr.ColumnRef(null, "ipk", 2);
        ProjectOp project = new ProjectOp(child, List.of(withAlias, nullQualifier));
        assertEquals(new Expr.ColumnRef("m", "nim"), project.schema().get(0).reference());
        assertEquals(new Expr.ColumnRef(null, "ipk"), project.schema().get(1).reference());
        project.close();
    }

    @Test
    void emptyTableStillHasHeader() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of());
        ProjectOp project = new ProjectOp(child, List.of(NIM, NAMA));
        List<ColumnSchema> expected =
                List.of(
                        new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nim"), DataType.INTEGER),
                        new ColumnSchema(
                                new Expr.ColumnRef("mahasiswa", "nama"), DataType.VARCHAR));
        project.open();
        assertNull(project.next());
        assertNull(project.next());
        assertEquals(expected, project.schema());
        project.close();
    }

    @Test
    void streamsAllRowsOfLargeTable() {
        List<ResultRow> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add(row(i, "Mhs" + i, 3.0f));
        }
        FakeChildOp child = new FakeChildOp(SCHEMA, rows);
        ProjectOp project = new ProjectOp(child, List.of(NIM));
        project.open();
        int count = 0;
        ResultRow result = project.next();
        while (result != null) {
            assertEquals(Value.ofInt(count), result.get(0));
            count++;
            result = project.next();
        }
        assertEquals(1000, count);
        project.close();
    }

    @Test
    void ridIsDropped() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(BUDI));
        ProjectOp project = new ProjectOp(child, List.of(NIM));
        project.open();
        assertNull(project.next().rid());
        project.close();
    }

    @Test
    void sqlNullValueIsKept() {
        ResultRow nullIpk =
                new ResultRow(
                        List.of(
                                Value.ofInt(2),
                                Value.ofText(DataType.VARCHAR, "Ani"),
                                Value.nullOf(DataType.FLOAT)),
                        new Rid(1, 2));
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(nullIpk));
        ProjectOp project = new ProjectOp(child, List.of(IPK));
        project.open();
        assertTrue(project.next().get(0).isNull());
        project.close();
    }

    @Test
    void invalidColumnsAreRejected() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of());
        IllegalArgumentException unbound =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new ProjectOp(
                                        child, List.of(new Expr.ColumnRef("mahasiswa", "nim"))));
        assertEquals("Column offset is unbound or out of range: -1", unbound.getMessage());

        IllegalArgumentException outOfRange =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new ProjectOp(
                                        child, List.of(new Expr.ColumnRef("mahasiswa", "x", 3))));
        assertEquals("Column offset is unbound or out of range: 3", outOfRange.getMessage());

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectOp(child, List.of(new Expr.Literal(Value.ofInt(1)))));
        assertThrows(IllegalArgumentException.class, () -> new ProjectOp(child, List.of()));
    }

    @Test
    void nullArgumentsAreRejected() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of());
        List<Expr> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new ProjectOp(null, List.of(NIM)));
        assertThrows(NullPointerException.class, () -> new ProjectOp(child, null));
        assertThrows(NullPointerException.class, () -> new ProjectOp(child, withNull));
    }

    @Test
    void reopenStartsFromBeginning() {
        ResultRow ani = row(2, "Ani", 3.8f);
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(BUDI, ani));
        ProjectOp project = new ProjectOp(child, List.of(NIM));
        project.open();
        project.next();
        project.open();
        assertEquals(Value.ofInt(1), project.next().get(0));
        assertEquals(Value.ofInt(2), project.next().get(0));
        assertNull(project.next());

        project.close();
        project.open();
        assertEquals(Value.ofInt(1), project.next().get(0));
    }

    @Test
    void childOpenFailureIsPropagatedAndProjectStaysClosed() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(BUDI));
        ProjectOp project = new ProjectOp(child, List.of(NIM));
        child.failNextOpen();
        RuntimeException openError = assertThrows(RuntimeException.class, project::open);
        assertEquals("Child open failed", openError.getMessage());

        IllegalStateException nextError = assertThrows(IllegalStateException.class, project::next);
        assertEquals("ProjectOp is not open", nextError.getMessage());

        project.close();
        assertEquals(1, child.getCloseCount());
    }

    @Test
    void nextAfterFailedReopenThrows() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(BUDI));
        ProjectOp project = new ProjectOp(child, List.of(NIM));
        project.open();
        child.failNextOpen();
        assertThrows(RuntimeException.class, project::open);

        IllegalStateException error = assertThrows(IllegalStateException.class, project::next);
        assertEquals("ProjectOp is not open", error.getMessage());
        project.close();
    }

    @Test
    void nextThrowsWhenNotOpen() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(BUDI));
        ProjectOp project = new ProjectOp(child, List.of(NIM));
        assertThrows(IllegalStateException.class, project::next);

        project.open();
        project.close();
        assertThrows(IllegalStateException.class, project::next);
    }

    @Test
    void closeIsSafeBeforeOpenAndWhenRepeated() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of());
        ProjectOp project = new ProjectOp(child, List.of(NIM));
        project.close();
        assertEquals(1, child.getCloseCount());

        project.open();
        project.close();
        assertEquals(2, child.getCloseCount());

        project.close();
        assertEquals(3, child.getCloseCount());
    }

    @Test
    void schemaIsAlwaysAvailable() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of());
        ProjectOp project = new ProjectOp(child, List.of(NIM));
        List<ColumnSchema> expected =
                List.of(new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nim"), DataType.INTEGER));
        assertEquals(expected, project.schema());

        project.open();
        project.close();
        assertEquals(expected, project.schema());
    }
}
