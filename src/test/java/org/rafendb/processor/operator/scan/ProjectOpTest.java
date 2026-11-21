package org.rafendb.processor.operator.scan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/** Lifecycle is covered by OperatorLifecycleTest. */
class ProjectOpTest {
    private static final ColumnSchema NIM_COLUMN =
            new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nim"), DataType.INTEGER);
    private static final ColumnSchema NAMA_COLUMN =
            new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nama"), DataType.VARCHAR);
    private static final ColumnSchema IPK_COLUMN =
            new ColumnSchema(new Expr.ColumnRef("mahasiswa", "ipk"), DataType.FLOAT);
    private static final List<ColumnSchema> SCHEMA = List.of(NIM_COLUMN, NAMA_COLUMN, IPK_COLUMN);

    private static final Expr NIM = new Expr.ColumnRef("mahasiswa", "nim", 0);
    private static final Expr NAMA = new Expr.ColumnRef("mahasiswa", "nama", 1);
    private static final Expr IPK = new Expr.ColumnRef("mahasiswa", "ipk", 2);

    private static final Value ONE = Value.ofInt(1);
    private static final Value BUDI = Value.ofText(DataType.VARCHAR, "Budi");
    private static final Value NULL_IPK = Value.nullOf(DataType.FLOAT);
    private static final ResultRow ROW = new ResultRow(List.of(ONE, BUDI, NULL_IPK), new Rid(1, 1));

    private final FakeChildOp child = new FakeChildOp(SCHEMA, List.of(ROW));

    @Test
    void picksColumnsByOffsetInTheGivenOrderAndDropsTheRid() {
        record Case(List<Expr> columns, List<ColumnSchema> schema, List<Value> values) {}
        for (Case c :
                List.of(
                        new Case(
                                List.of(NIM, IPK),
                                List.of(NIM_COLUMN, IPK_COLUMN),
                                List.of(ONE, NULL_IPK)),
                        new Case(
                                List.of(IPK, NIM),
                                List.of(IPK_COLUMN, NIM_COLUMN),
                                List.of(NULL_IPK, ONE)),
                        new Case(
                                List.of(NAMA, NAMA),
                                List.of(NAMA_COLUMN, NAMA_COLUMN),
                                List.of(BUDI, BUDI)))) {
            ProjectOp project = new ProjectOp(child, c.columns());
            assertEquals(c.schema(), project.schema());

            project.open();
            ResultRow out = project.next();
            assertEquals(c.values(), out.values());
            assertNull(out.rid());
            assertNull(project.next());
            project.close();
        }
    }

    @Test
    void headerKeepsTheQualifierFromBinding() {
        List<Expr> columns =
                List.of(new Expr.ColumnRef("m", "nim", 0), new Expr.ColumnRef(null, "ipk", 2));
        try (ProjectOp project = new ProjectOp(child, columns)) {
            assertEquals(new Expr.ColumnRef("m", "nim"), project.schema().get(0).reference());
            assertEquals(new Expr.ColumnRef(null, "ipk"), project.schema().get(1).reference());
        }
    }

    @Test
    void rejectsColumnsItCannotResolve() {
        for (List<Expr> columns :
                List.<List<Expr>>of(
                        List.of(new Expr.ColumnRef("mahasiswa", "nim")), // unbound offset
                        List.of(new Expr.ColumnRef("mahasiswa", "x", 3)),
                        List.of(new Expr.Literal(Value.ofInt(1))),
                        List.of())) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProjectOp(child, columns),
                    columns.toString());
        }
    }
}
