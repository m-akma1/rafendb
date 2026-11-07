package org.rafendb.optimizer.rewrite;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.SelectionPlanNode;
import org.rafendb.common.plan.CartesianPlanNode;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.ProjectionPlanNode;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.common.plan.SortPlanNode;

public class SelectionPushdownRule implements RewriteRule {

    @Override
    public PlanNode apply(PlanNode root) {
        if (root == null) return null;
        return pushdown(root);
    }

    private PlanNode pushdown(PlanNode node) {
        if (node.children().isEmpty()) {
            return node;
        }

        List<PlanNode> rewrittenChildren = new ArrayList<>();
        for (PlanNode child : node.children()) {
            rewrittenChildren.add(pushdown(child));
        }

        PlanNode reconstructedNode = reconstructNode(node, rewrittenChildren);

        if (reconstructedNode instanceof SelectionPlanNode selectionNode) {
            return attemptPushdown(selectionNode);
        }

        return reconstructedNode;
    }

    private PlanNode reconstructNode(PlanNode original, List<PlanNode> newChildren) {
        return switch (original) {
            case SelectionPlanNode sel -> 
                new SelectionPlanNode(sel.condition(), newChildren.get(0));
            case ProjectionPlanNode proj -> 
                new ProjectionPlanNode(proj.columns(), newChildren.get(0));
            case JoinPlanNode join -> 
                new JoinPlanNode(join.algorithm(), join.condition(), newChildren.get(0), newChildren.get(1));
            case CartesianPlanNode cart -> 
                new CartesianPlanNode(cart.algorithm(), newChildren.get(0), newChildren.get(1));
            case SortPlanNode sort -> 
                new SortPlanNode(newChildren.get(0), sort.column(), sort.direction());            
            default -> original;
        };
    }

    private PlanNode attemptPushdown(SelectionPlanNode selectionNode) {
        return selectionNode;
    }
}