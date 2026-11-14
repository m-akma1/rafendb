package org.rafendb.processor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.*;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.Value;
import org.rafendb.processor.plan.command.CreateIndexPlanNode;
import org.rafendb.processor.plan.command.UpdatePlanNode;
import org.rafendb.processor.tuple.ResultRow;

class Qp2ContractTest {
    @Test
    void wrapperExposesTraversableTreeWithoutEmbeddingChildrenInDescriptions() {
        var left = new TableScanNode(null, "pegawai");
        var right = new TableScanNode(null, "departemen");
        var root = new CartesianPlanNode(JoinAlgorithm.BLOCK_NESTED_LOOP, left, right);
        var plan = new QueryPlan(root, 27.5);
        assertSame(root, plan.root());
        assertEquals(27.5, plan.totalCost());
        assertEquals(List.of(left, right), plan.root().children());
        assertTrue(left.children().isEmpty());
        assertFalse(root.describe().contains("\n"));
        assertFalse(root.describe().contains("pegawai"));
        assertThrows(UnsupportedOperationException.class, () -> root.children().clear());
        assertThrows(NullPointerException.class, () -> new QueryPlan(null, 0));
    }

    private final Expr value = new Expr.Literal(Value.ofInt(5000));

    @Test
    void commandTargetsStillRejectMissingOrBlankColumnNames() {
        for (String name : new String[] {null, "", " "}) {
            var column = new Expr.ColumnRef("pegawai", name);
            Class<? extends RuntimeException> error =
                    name == null ? NullPointerException.class : IllegalArgumentException.class;
            assertThrows(
                    error, () -> new UpdatePlanNode("pegawai", column, value, Optional.empty()));
            assertThrows(
                    error,
                    () ->
                            new CreateIndexPlanNode(
                                    "idx", "pegawai", column, CreateIndexPlanNode.IndexKind.BTREE));
        }
    }

    @Test
    void fixtureOwnershipAndRowShapeAreProtected() {
        var values = new ArrayList<Value>(List.of(Value.ofInt(6000)));
        var rid = new Rid(1, 2);
        var row = new ResultRow(values, rid);
        values.clear();
        assertEquals(List.of(Value.ofInt(6000)), row.values());
        assertEquals(rid, row.rid());
        var storageRow = new Row(row.values());
        assertEquals(row.values(), storageRow.values());
        assertEquals(row.get(0), storageRow.get(0));
        assertEquals(row.size(), storageRow.size());
        assertThrows(UnsupportedOperationException.class, () -> storageRow.values().clear());
        assertThrows(UnsupportedOperationException.class, () -> row.values().clear());
        assertThrows(NullPointerException.class, () -> new ResultRow(null, null));
        assertThrows(
                NullPointerException.class,
                () -> new SelectionPlanNode(null, new TableScanNode(null, "pegawai")));
    }
}
