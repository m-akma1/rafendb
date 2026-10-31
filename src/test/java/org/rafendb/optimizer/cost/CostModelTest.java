package org.rafendb.optimizer.cost;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.plan.PlanNode;

class CostModelTest {
    private static PlanNode node(String name, PlanNode... children) {
        return new PlanNode() {
            @Override
            public List<PlanNode> children() {
                return List.of(children);
            }

            @Override
            public String describe() {
                return name;
            }
        };
    }

    @Test
    void costValidatesAndAddsValues() {
        Cost a = new Cost(2, 10);
        Cost b = new Cost(3, 5);
        assertEquals(2, a.getSeeks());
        assertEquals(10, a.getTransfers());
        assertEquals(9.0, a.timeMs());
        assertEquals(new Cost(5, 15), a.plus(b));
        assertEquals(a, new Cost(2, 10));
        assertEquals(a.hashCode(), new Cost(2, 10).hashCode());
        assertThrows(IllegalArgumentException.class, () -> new Cost(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Cost(0, -1));
    }

    @Test
    void mockModelReturnsNonnegativeEstimatesAndLocalCost() {
        MockCostModel model = new MockCostModel(100);
        PlanNode leaf = node("leaf");
        PlanNode parent = node("parent", leaf);
        assertTrue(model.estimateRows(parent) >= 0);
        assertEquals(new Cost(1, 100), model.estimateLocalCost(parent));
        assertEquals(new Cost(1, 100), model.estimateLocalCost(leaf));
        assertThrows(RuntimeException.class, () -> model.estimateRows(null));
    }

    @Test
    void totalIncludesNestedSubtreeAndCountsSharedNodeOnce() {
        MockCostModel model = new MockCostModel(10);
        PlanNode shared = node("shared");
        PlanNode left = node("left", shared);
        PlanNode right = node("right", shared);
        PlanNode root = node("root", left, right);
        // root + left + right + shared: each costs (1 seek, 10 transfers).
        assertEquals(new Cost(4, 40), model.estimateTotalCost(root));
        assertThrows(RuntimeException.class, () -> model.estimateTotalCost(null));
    }

    @Test
    void totalRejectsInvalidChildrenAndNullLocalCost() {
        CostModel nullCost =
                new CostModel() {
                    @Override
                    public long estimateRows(PlanNode node) {
                        return 0;
                    }

                    @Override
                    public Cost estimateLocalCost(PlanNode node) {
                        return null;
                    }
                };
        assertThrows(RuntimeException.class, () -> nullCost.estimateTotalCost(node("x")));
        PlanNode invalid =
                new PlanNode() {
                    @Override
                    public List<PlanNode> children() {
                        return null;
                    }

                    @Override
                    public String describe() {
                        return "invalid";
                    }
                };
        assertThrows(RuntimeException.class, () -> new MockCostModel().estimateTotalCost(invalid));
    }
}
