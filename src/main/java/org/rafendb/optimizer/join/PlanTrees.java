package org.rafendb.optimizer.join;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.*;

/** Builds and compares immutable plan trees. */
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
            return child == node.child() ? root : project(node.columns(), child);
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

    /** Null here means an explicit Cartesian product, never an unresolved natural join. */
    static PlanNode createJoinOrCartesian(Expr condition, PlanNode left, PlanNode right) {
        return condition == null
                ? new CartesianPlanNode(JoinAlgorithm.BLOCK_NESTED_LOOP, left, right)
                : new JoinPlanNode(JoinAlgorithm.BLOCK_NESTED_LOOP, condition, left, right);
    }

    /** Restores each original column from its new position, preserving names and qualifiers. */
    static PlanNode restoreOutputOrder(
            List<Expr.ColumnRef> original, int[] positions, PlanNode child) {
        List<Expr> columns = new ArrayList<>();
        boolean identity = true;
        for (int i = 0; i < original.size(); i++) {
            Expr.ColumnRef column = original.get(i);
            columns.add(new Expr.ColumnRef(column.table(), column.column(), positions[i]));
            identity &= positions[i] == i;
        }
        return identity ? child : project(columns, child);
    }

    /**
     * Composes column-only projections to avoid stacking wrappers after repeated swaps.
     * Computed projections stay in place. Composed offsets refer to the new child.
     */
    static PlanNode project(List<Expr> columns, PlanNode child) {
        if (child instanceof ProjectionPlanNode inner) {
            List<Expr> composed = new ArrayList<>();
            for (Expr expr : columns) {
                if (!(expr instanceof Expr.ColumnRef column)
                        || column.offset() < 0
                        || column.offset() >= inner.columns().size()
                        || !(inner.columns().get(column.offset())
                                instanceof Expr.ColumnRef source)) {
                    return new ProjectionPlanNode(columns, child);
                }
                composed.add(new Expr.ColumnRef(column.table(), column.column(), source.offset()));
            }
            return project(composed, inner.child());
        }
        return new ProjectionPlanNode(columns, child);
    }

    /**
     * Includes execution details omitted by describe(), such as offsets and index predicates.
     * Sort and Cartesian nodes need explicit keys because they lack value equality.
     * Unknown nodes keep their own equality semantics.
     */
    static Object structuralKey(PlanNode node) {
        if (node instanceof SelectionPlanNode n) {
            return List.of(SelectionPlanNode.class, n.condition(), structuralKey(n.child()));
        }
        if (node instanceof ProjectionPlanNode n) {
            return List.of(ProjectionPlanNode.class, n.columns(), structuralKey(n.child()));
        }
        if (node instanceof SortPlanNode n) {
            return List.of(SortPlanNode.class, n.column(), n.direction(), structuralKey(n.child()));
        }
        if (node instanceof JoinPlanNode n) {
            return Arrays.asList(
                    JoinPlanNode.class,
                    n.algorithm(),
                    n.condition(),
                    structuralKey(n.left()),
                    structuralKey(n.right()));
        }
        if (node instanceof CartesianPlanNode n) {
            return List.of(
                    CartesianPlanNode.class,
                    n.algorithm(),
                    structuralKey(n.left()),
                    structuralKey(n.right()));
        }
        return node;
    }

    /** Splits only AND, keeping every other expression intact. */
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
