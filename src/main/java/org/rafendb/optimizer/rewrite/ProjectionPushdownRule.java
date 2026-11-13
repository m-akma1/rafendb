package org.rafendb.optimizer.rewrite;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.common.plan.CartesianPlanNode;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.ProjectionPlanNode;
import org.rafendb.common.plan.SelectionPlanNode;
import org.rafendb.common.plan.SortPlanNode;

public class ProjectionPushdownRule implements RewriteRule {

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

        if (reconstructedNode instanceof ProjectionPlanNode projNode) {
            return attemptPushdown(projNode);
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

    private PlanNode attemptPushdown(ProjectionPlanNode proj) {
        PlanNode child = proj.child();

        if (child instanceof JoinPlanNode join) {
            java.util.Set<String> leftTables = getProvidedTables(join.left());
            java.util.Set<String> rightTables = getProvidedTables(join.right());

            List<org.rafendb.common.expr.Expr> leftCols =
                    extractRequiredColumns(proj.columns(), join.condition(), leftTables);
            List<org.rafendb.common.expr.Expr> rightCols =
                    extractRequiredColumns(proj.columns(), join.condition(), rightTables);

            PlanNode newLeft =
                    leftCols.isEmpty()
                            ? join.left()
                            : attemptPushdown(new ProjectionPlanNode(leftCols, join.left()));
            PlanNode newRight =
                    rightCols.isEmpty()
                            ? join.right()
                            : attemptPushdown(new ProjectionPlanNode(rightCols, join.right()));

            JoinPlanNode newJoin =
                    new JoinPlanNode(join.algorithm(), join.condition(), newLeft, newRight);

            return new ProjectionPlanNode(proj.columns(), newJoin);
        }

        if (child instanceof CartesianPlanNode cart) {
            java.util.Set<String> leftTables = getProvidedTables(cart.left());
            java.util.Set<String> rightTables = getProvidedTables(cart.right());

            List<org.rafendb.common.expr.Expr> leftCols =
                    extractRequiredColumns(proj.columns(), null, leftTables);
            List<org.rafendb.common.expr.Expr> rightCols =
                    extractRequiredColumns(proj.columns(), null, rightTables);

            PlanNode newLeft =
                    leftCols.isEmpty()
                            ? cart.left()
                            : attemptPushdown(new ProjectionPlanNode(leftCols, cart.left()));
            PlanNode newRight =
                    rightCols.isEmpty()
                            ? cart.right()
                            : attemptPushdown(new ProjectionPlanNode(rightCols, cart.right()));

            CartesianPlanNode newCart = new CartesianPlanNode(cart.algorithm(), newLeft, newRight);
            return new ProjectionPlanNode(proj.columns(), newCart);
        }

        return proj;
    }

    private List<org.rafendb.common.expr.Expr> extractRequiredColumns(
            List<org.rafendb.common.expr.Expr> outputCols,
            org.rafendb.common.expr.Expr joinCondition,
            java.util.Set<String> availableTables) {

        List<org.rafendb.common.expr.Expr> requiredForThisSide = new ArrayList<>();
        for (org.rafendb.common.expr.Expr expr : outputCols) {
            if (expr instanceof org.rafendb.common.expr.Expr.ColumnRef col
                    && availableTables.contains(col.table())) {
                if (!requiredForThisSide.contains(col)) requiredForThisSide.add(col);
            }
        }

        if (joinCondition != null) {
            extractColumnsFromExpr(joinCondition, availableTables, requiredForThisSide);
        }

        return requiredForThisSide;
    }

    private void extractColumnsFromExpr(
            org.rafendb.common.expr.Expr expr,
            java.util.Set<String> availableTables,
            List<org.rafendb.common.expr.Expr> collectedCols) {

        if (expr instanceof org.rafendb.common.expr.Expr.Binary bin) {
            extractColumnsFromExpr(bin.left(), availableTables, collectedCols);
            extractColumnsFromExpr(bin.right(), availableTables, collectedCols);
        } else if (expr instanceof org.rafendb.common.expr.Expr.ColumnRef col) {
            if (col.table() != null
                    && availableTables.contains(col.table())
                    && !collectedCols.contains(col)) {
                collectedCols.add(col);
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
