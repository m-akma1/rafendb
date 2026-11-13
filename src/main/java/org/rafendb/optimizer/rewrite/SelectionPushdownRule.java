package org.rafendb.optimizer.rewrite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.CartesianPlanNode;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.ProjectionPlanNode;
import org.rafendb.common.plan.SelectionPlanNode;
import org.rafendb.common.plan.SortPlanNode;
import org.rafendb.optimizer.catalog.Catalog;

public final class SelectionPushdownRule implements RewriteRule {
    public static final Comparator<Expr> KEEP_ORDER = (a, b) -> 0;
    public static final Comparator<Expr> SELECTIVE_FIRST =
            Comparator.comparingInt(SelectionPushdownRule::selectivityRank);
        
    private final PlanLayout layout;
    private final Comparator<Expr> order;

    public SelectionPushdownRule(Catalog catalog) {
        this(catalog, SELECTIVE_FIRST);
    }

    public SelectionPushdownRule(Catalog catalog, Comparator<Expr> order) {
        this.layout = new PlanLayout(catalog);
        this.order = Objects.requireNonNull(order, "order");
    }

    @Override
    public PlanNode apply(PlanNode root) {
        if (root == null || !PlanLayout.supports(root)) return root;
        return layout.rebind(rewrite(root));
    }

    private PlanNode rewrite(PlanNode node) {
        if (node instanceof SelectionPlanNode) {
            // Collect the whole chain top to bottom, then flip it into execution order.
            List<Expr> conditions = new ArrayList<>();
            PlanNode base = node;
            while (base instanceof SelectionPlanNode sel) {
                List<Expr> parts = PlanLayout.conjuncts(sel.condition());
                for (int i = parts.size() - 1; i >= 0; i--) conditions.add(parts.get(i));
                base = sel.child();
            }
            Collections.reverse(conditions);
            return place(conditions, rewrite(base));
        }
        if (node instanceof ProjectionPlanNode proj) {
            return new ProjectionPlanNode(proj.columns(), rewrite(proj.child()));
        }
        if (node instanceof SortPlanNode sort) {
            return new SortPlanNode(rewrite(sort.child()), sort.column(), sort.direction());
        }
        if (node instanceof JoinPlanNode join) {
            return new JoinPlanNode(
                    join.algorithm(),
                    join.condition(),
                    rewrite(join.left()),
                    rewrite(join.right()));
        }
        if (node instanceof CartesianPlanNode cart) {
            return new CartesianPlanNode(
                    cart.algorithm(), rewrite(cart.left()), rewrite(cart.right()));
        }
        return node;
    }

    private PlanNode place(List<Expr> conditions, PlanNode node) {
        if (conditions.isEmpty()) return node;

        if (node instanceof ProjectionPlanNode proj && allResolve(conditions, proj.child())) {
            return new ProjectionPlanNode(proj.columns(), place(conditions, proj.child()));
        }
        if (node instanceof SortPlanNode sort) {
            return new SortPlanNode(
                    place(conditions, sort.child()), sort.column(), sort.direction());
        }
        if (node instanceof JoinPlanNode join && join.condition() != null) {
            return placeBelowBinary(
                    conditions,
                    join.left(),
                    join.right(),
                    (l, r) -> new JoinPlanNode(join.algorithm(), join.condition(), l, r));
        }
        if (node instanceof CartesianPlanNode cart) {
            return placeBelowBinary(
                    conditions,
                    cart.left(),
                    cart.right(),
                    (l, r) -> new CartesianPlanNode(cart.algorithm(), l, r));
        }
        return wrap(conditions, node);
    }

    private interface BinaryFactory {
        PlanNode create(PlanNode left, PlanNode right);
    }

    private PlanNode placeBelowBinary(
            List<Expr> conditions, PlanNode left, PlanNode right, BinaryFactory factory) {
        List<PlanLayout.Col> leftCols = layout.of(left);
        List<PlanLayout.Col> rightCols = layout.of(right);
        List<Expr> toLeft = new ArrayList<>();
        List<Expr> toRight = new ArrayList<>();
        List<Expr> stay = new ArrayList<>();
        for (Expr condition : conditions) {
            List<Expr.ColumnRef> refs = PlanLayout.refs(condition);
            boolean onlyLeft = !refs.isEmpty();
            boolean onlyRight = !refs.isEmpty();
            for (Expr.ColumnRef ref : refs) {
                int inLeft = PlanLayout.find(leftCols, ref);
                int inRight = PlanLayout.find(rightCols, ref);
                if (inLeft < 0 || inRight != PlanLayout.NOT_FOUND) onlyLeft = false;
                if (inRight < 0 || inLeft != PlanLayout.NOT_FOUND) onlyRight = false;
            }
            if (onlyLeft) toLeft.add(condition);
            else if (onlyRight) toRight.add(condition);
            else stay.add(condition);
        }
        PlanNode joined = factory.create(place(toLeft, left), place(toRight, right));
        return wrap(stay, joined);
    }

    private boolean allResolve(List<Expr> conditions, PlanNode input) {
        List<PlanLayout.Col> cols = layout.of(input);
        for (Expr condition : conditions) {
            for (Expr.ColumnRef ref : PlanLayout.refs(condition)) {
                if (PlanLayout.find(cols, ref) < 0) return false;
            }
        }
        return true;
    }

    private PlanNode wrap(List<Expr> conditions, PlanNode node) {
        List<Expr> sorted = new ArrayList<>(conditions);
        sorted.sort(order);
        PlanNode result = node;
        for (Expr condition : sorted) result = new SelectionPlanNode(condition, result);
        return result;
    }

    private static int selectivityRank(Expr condition) {
        if (condition instanceof Expr.Binary bin) {
            return switch (bin.op()) {
                case EQ -> 0;
                case GT, GE, LT, LE -> 1;
                default -> 2;
            };
        }
        return 2;
    }
}
