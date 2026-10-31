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
        return algorithm;
    }

    public Expr condition() {
        return condition;
    }

    public PlanNode left() {
        return left;
    }

    public PlanNode right() {
        return right;
    }

    @Override
    public List<PlanNode> children() {
        return List.of(left, right);
    }

    @Override
    public String describe() {
        return "";
    }
}
