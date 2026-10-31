package org.rafendb.optimizer.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.plan.TableScanNode;

/** Verifies the optimizer's public plan-preservation behavior. */
class JoinAccessPathOptimizerTest {
    /** Checks that invoking the stage preserves the plan, its root, and its estimated cost. */
    @Test
    void returnsInputPlanUnchanged() {
        TableScanNode root = new TableScanNode(null, "students");
        QueryPlan plan = new QueryPlan(root, 12.5);

        QueryPlan result = new JoinAccessPathOptimizer().optimize(plan);

        assertSame(plan, result);
        assertSame(root, result.getRoot());
        assertEquals(12.5, result.getTotalCost());
    }

    /** Checks the documented rejection of a null input plan. */
    @Test
    void rejectsNullPlan() {
        assertThrows(
                NullPointerException.class, () -> new JoinAccessPathOptimizer().optimize(null));
    }
}
