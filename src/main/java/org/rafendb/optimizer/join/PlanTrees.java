package org.rafendb.optimizer.join;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.*;

/** Utilities for rebuilding immutable plan trees and combining predicates. */
final class PlanTrees {
    private PlanTrees() {}

    /** Replaces a node by identity, rebuilding only affected ancestors. */
    static PlanNode replace(PlanNode root, PlanNode target, PlanNode replacement) {
        if (root == target) return replacement;
        if (root instanceof SelectionPlanNode node) {
            PlanNode child = replace(node.child(), target, replacement);
            return child == node.child() ? root : new SelectionPlanNode(node.condition(), child);
        }
        if (root instanceof ProjectionPlanNode node) {
            PlanNode child = replace(node.child(), target, replacement);
            return child == node.child() ? root : new ProjectionPlanNode(node.columns(), child);
        }
        if (root instanceof SortPlanNode node) {
            PlanNode child = replace(node.child(), target, replacement);
            return child == node.child()
                    ? root
                    : new SortPlanNode(child, node.column(), node.direction());
        }
        if (root instanceof JoinPlanNode node) {
            PlanNode left = replace(node.left(), target, replacement);
            PlanNode right = replace(node.right(), target, replacement);
            return left == node.left() && right == node.right()
                    ? root
                    : new JoinPlanNode(node.algorithm(), node.condition(), left, right);
        }
        if (root instanceof CartesianPlanNode node) {
            PlanNode left = replace(node.left(), target, replacement);
            PlanNode right = replace(node.right(), target, replacement);
            return left == node.left() && right == node.right()
                    ? root
                    : new CartesianPlanNode(node.algorithm(), left, right);
        }
        return root;
    }

    /** Returns children of operators supported by replace(). */
    static List<PlanNode> children(PlanNode node) {
        if (node instanceof SelectionPlanNode
                || node instanceof ProjectionPlanNode
                || node instanceof SortPlanNode
                || node instanceof JoinPlanNode
                || node instanceof CartesianPlanNode) return node.children();
        return List.of();
    }

    static List<Expr> conjuncts(Expr expr) {
        List<Expr> result = new ArrayList<>();
        collectConjuncts(expr, result);
        return result;
    }

    private static void collectConjuncts(Expr expr, List<Expr> result) {
        if (expr instanceof Expr.Binary binary && binary.op() == Expr.Op.AND) {
            collectConjuncts(binary.left(), result);
            collectConjuncts(binary.right(), result);
        } else {
            result.add(expr);
        }
    }

    /** Null is an empty accumulator, not a SQL NULL expression. */
    static Expr and(Expr first, Expr second) {
        return first == null ? second : new Expr.Binary(Expr.Op.AND, first, second);
    }
}
