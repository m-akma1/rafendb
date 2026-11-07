package org.rafendb.optimizer.rewrite;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.common.plan.CartesianPlanNode;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.ProjectionPlanNode;
import org.rafendb.common.plan.SelectionPlanNode;
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
                    new JoinPlanNode(
                            join.algorithm(),
                            join.condition(),
                            newChildren.get(0),
                            newChildren.get(1));
            case CartesianPlanNode cart ->
                    new CartesianPlanNode(cart.algorithm(), newChildren.get(0), newChildren.get(1));
            case SortPlanNode sort ->
                    new SortPlanNode(newChildren.get(0), sort.column(), sort.direction());
            default -> original;
        };
    }

    private PlanNode attemptPushdown(SelectionPlanNode sel) {
        PlanNode child = sel.child();

        if (child instanceof ProjectionPlanNode proj) {
            SelectionPlanNode newSel = new SelectionPlanNode(sel.condition(), proj.child());
            return new ProjectionPlanNode(proj.columns(), attemptPushdown(newSel));
        }

        if (child instanceof SortPlanNode sort) {
            SelectionPlanNode newSel = new SelectionPlanNode(sel.condition(), sort.child());
            return new SortPlanNode(attemptPushdown(newSel), sort.column(), sort.direction());
        }

        if (child instanceof JoinPlanNode join) {
            java.util.Set<String> condTables = getReferencedTables(sel.condition());
            java.util.Set<String> leftTables = getProvidedTables(join.left());
            java.util.Set<String> rightTables = getProvidedTables(join.right());

            if (!condTables.isEmpty() && leftTables.containsAll(condTables)) {
                SelectionPlanNode newLeft = new SelectionPlanNode(sel.condition(), join.left());
                return new JoinPlanNode(
                        join.algorithm(), join.condition(), attemptPushdown(newLeft), join.right());
            } else if (!condTables.isEmpty() && rightTables.containsAll(condTables)) {
                SelectionPlanNode newRight = new SelectionPlanNode(sel.condition(), join.right());
                return new JoinPlanNode(
                        join.algorithm(), join.condition(), join.left(), attemptPushdown(newRight));
            }
        }

        if (child instanceof CartesianPlanNode cart) {
            java.util.Set<String> condTables = getReferencedTables(sel.condition());
            java.util.Set<String> leftTables = getProvidedTables(cart.left());
            java.util.Set<String> rightTables = getProvidedTables(cart.right());

            if (!condTables.isEmpty() && leftTables.containsAll(condTables)) {
                SelectionPlanNode newLeft = new SelectionPlanNode(sel.condition(), cart.left());
                return new CartesianPlanNode(
                        cart.algorithm(), attemptPushdown(newLeft), cart.right());
            } else if (!condTables.isEmpty() && rightTables.containsAll(condTables)) {
                SelectionPlanNode newRight = new SelectionPlanNode(sel.condition(), cart.right());
                return new CartesianPlanNode(
                        cart.algorithm(), cart.left(), attemptPushdown(newRight));
            }
        }

        return sel;
    }

    private java.util.Set<String> getReferencedTables(org.rafendb.common.expr.Expr expr) {
        java.util.Set<String> tables = new java.util.HashSet<>();
        extractTablesFromExpr(expr, tables);
        return tables;
    }

    private void extractTablesFromExpr(
            org.rafendb.common.expr.Expr expr, java.util.Set<String> tables) {
        if (expr instanceof org.rafendb.common.expr.Expr.Binary bin) {
            extractTablesFromExpr(bin.left(), tables);
            extractTablesFromExpr(bin.right(), tables);
        } else if (expr instanceof org.rafendb.common.expr.Expr.ColumnRef col) {
            if (col.table() != null) {
                tables.add(col.table());
            }
        }
    }

    private java.util.Set<String> getProvidedTables(PlanNode node) {
        java.util.Set<String> tables = new java.util.HashSet<>();
        extractTablesFromPlan(node, tables);
        return tables;
    }

    private void extractTablesFromPlan(PlanNode node, java.util.Set<String> tables) {
        if (node instanceof org.rafendb.common.plan.TableScanNode scan) {
            tables.add(scan.alias() != null ? scan.alias() : scan.table());
        } else if (node instanceof org.rafendb.common.plan.IndexScanNode idxScan) {
            tables.add(idxScan.alias() != null ? idxScan.alias() : idxScan.table());
        } else {
            for (PlanNode child : node.children()) {
                extractTablesFromPlan(child, tables);
            }
        }
    }
}
