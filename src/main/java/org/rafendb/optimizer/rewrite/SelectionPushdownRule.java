package org.rafendb.optimizer.rewrite;

import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.SelectionPlanNode;

public class SelectionPushdownRule implements RewriteRule {

    @Override
    public PlanNode apply(PlanNode root) {
        if (root == null) return null;
        return pushdown(root);
    }

    private PlanNode pushdown(PlanNode node) {
        if (node instanceof SelectionPlanNode selNode) {
            PlanNode child = selNode.child();

            if (child instanceof JoinPlanNode joinNode) {
                Expr condition = selNode.condition();
                String targetTable = extractTargetTable(condition, joinNode);

                if (targetTable.equals("LEFT")) {
                    SelectionPlanNode newLeft = new SelectionPlanNode(condition, joinNode.left());
                    return new JoinPlanNode(
                            joinNode.algorithm(), joinNode.condition(), newLeft, joinNode.right());
                } else if (targetTable.equals("RIGHT")) {
                    SelectionPlanNode newRight = new SelectionPlanNode(condition, joinNode.right());
                    return new JoinPlanNode(
                            joinNode.algorithm(), joinNode.condition(), joinNode.left(), newRight);
                }
            }
        }
        return node;
    }

    private String extractTargetTable(Expr condition, JoinPlanNode join) {
        // Implementasi evaluasi kondisi untuk menentukan target tabel
        return "BOTH";
    }
}
