package org.rafendb.optimizer.join;

import static org.junit.jupiter.api.Assertions.*;
import static org.rafendb.common.plan.JoinAlgorithm.BLOCK_NESTED_LOOP;
import static org.rafendb.optimizer.join.JoinAccessPathTestSupport.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr.*;
import org.rafendb.common.plan.*;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.mock.storage.MockStorageManager;

class CartesianJoinConversionTest {
    private final MockStorageManager storage = new MockStorageManager();
    private final JoinOptimizer joins = new JoinOptimizer();
    private final CartesianPlanNode product =
            new CartesianPlanNode(BLOCK_NESTED_LOOP, STUDENTS, PROGRAMS);
    private final ColumnRef studentProgram = new ColumnRef("m", "kode_prodi", 3);
    private final Binary enrolledIn =
            new Binary(Op.EQ, studentProgram, new ColumnRef("p", "kode", 4));
    private final Value ifCode = Value.ofText(DataType.CHAR, "IF");
    private final Binary inInformatics = new Binary(Op.EQ, studentProgram, new Literal(ifCode));

    @Test
    void convertsConnectedProductToJoin() {
        var original = new SelectionPlanNode(enrolledIn, product);

        var normalized = assertInstanceOf(JoinPlanNode.class, joins.normalize(original));
        assertEquals(enrolledIn, normalized.condition());
        assertEquals(rows(storage, original), rows(storage, normalized), "Complete joined rows");

        // Projecting the program code makes the two IF and two STI students duplicate rows.
        var expected =
                Map.of(
                        List.of(ifCode),
                        2,
                        List.of(Value.ofText(DataType.CHAR, "STI")),
                        2,
                        List.of(Value.ofText(DataType.CHAR, "EL")),
                        1);
        var projected = new ProjectionPlanNode(List.of(studentProgram), normalized);
        assertEquals(expected, rows(storage, projected), "Students per program");
    }

    @Test
    void mergesSelectionWithExistingJoin() {
        var join = new JoinPlanNode(BLOCK_NESTED_LOOP, enrolledIn, STUDENTS, PROGRAMS);
        var original = new SelectionPlanNode(inInformatics, join);

        var merged = assertInstanceOf(JoinPlanNode.class, joins.normalize(original));

        assertEquals(
                Set.of(enrolledIn, inInformatics),
                Set.copyOf(PlanTrees.conjuncts(merged.condition())));
        assertEquals(rows(storage, original), rows(storage, merged));
    }

    @Test
    void keepsProductForOneSidedFilter() {
        var original = new SelectionPlanNode(inInformatics, product);

        var normalized = assertInstanceOf(SelectionPlanNode.class, joins.normalize(original));

        assertInstanceOf(CartesianPlanNode.class, normalized.child());
        assertEquals(inInformatics, normalized.condition());
        assertEquals(rows(storage, original), rows(storage, normalized));
    }
}
