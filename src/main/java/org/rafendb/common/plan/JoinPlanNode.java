package org.rafendb.common.plan;

import java.util.List;
import org.rafendb.common.expr.Expr;

/** condition null untuk natural join. */
public final class JoinPlanNode implements PlanNode {
    private final JoinAlgorithm algorithm;
    private final Expr condition;
    private final PlanNode left;
    private final PlanNode right;

    public JoinPlanNode(JoinAlgorithm algorithm, Expr condition, PlanNode left, PlanNode right) {
        this.algorithm = algorithm;
        this.condition = condition;
        this.left = left;
        this.right = right;
    }

    public JoinAlgorithm algorithm() {
        return null;
    }

    public Expr condition() {
        return null;
    }

    public PlanNode left() {
        return null;
    }

    public PlanNode right() {
        return null;
    }

    @Override
    public List<PlanNode> children() {
        return null;
    }

    @Override
    public String describe() {
        return null;
    }
}
