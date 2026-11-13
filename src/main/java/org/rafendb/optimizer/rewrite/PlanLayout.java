package org.rafendb.optimizer.rewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.CartesianPlanNode;
import org.rafendb.common.plan.IndexScanNode;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.ProjectionPlanNode;
import org.rafendb.common.plan.SelectionPlanNode;
import org.rafendb.common.plan.SortPlanNode;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.common.schema.Column;
import org.rafendb.optimizer.catalog.Catalog;

final class PlanLayout {

    record Col(String qualifier, String table, String name) {}

    static final int NOT_FOUND = -1;

    static final int AMBIGUOUS = -2;

    private final Catalog catalog;

    PlanLayout(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    static boolean supports(PlanNode node) {
        boolean known =
                node instanceof TableScanNode
                        || node instanceof IndexScanNode
                        || node instanceof SelectionPlanNode
                        || node instanceof ProjectionPlanNode
                        || node instanceof SortPlanNode
                        || node instanceof JoinPlanNode
                        || node instanceof CartesianPlanNode;
        if (!known) return false;
        if (node instanceof ProjectionPlanNode proj) {
            for (Expr column : proj.columns()) {
                if (!(column instanceof Expr.ColumnRef)) return false;
            }
        }
        for (PlanNode child : node.children()) {
            if (!supports(child)) return false;
        }
        return true;
    }

    List<Col> of(PlanNode node) {
        if (node instanceof TableScanNode scan) return scanColumns(scan.alias(), scan.table());
        if (node instanceof IndexScanNode scan) return scanColumns(scan.alias(), scan.table());
        if (node instanceof SelectionPlanNode sel) return of(sel.child());
        if (node instanceof SortPlanNode sort) return of(sort.child());
        if (node instanceof JoinPlanNode join) return concat(of(join.left()), of(join.right()));
        if (node instanceof CartesianPlanNode cart) {
            return concat(of(cart.left()), of(cart.right()));
        }
        if (node instanceof ProjectionPlanNode proj) {
            List<Col> input = of(proj.child());
            List<Col> out = new ArrayList<>();
            for (Expr expr : proj.columns()) {
                out.add(input.get(requireIndex(input, asColumn(expr))));
            }
            return out;
        }
        throw new UnsupportedOperationException(
                "Unsupported plan node: " + node.getClass().getSimpleName());
    }

    private List<Col> scanColumns(String alias, String table) {
        String qualifier = alias != null ? alias : table;
        List<Col> out = new ArrayList<>();
        for (Column column : catalog.getSchema(table).columns()) {
            out.add(new Col(qualifier, table, column.name()));
        }
        return out;
    }

    static int find(List<Col> layout, Expr.ColumnRef ref) {
        int found = NOT_FOUND;
        for (int i = 0; i < layout.size(); i++) {
            Col col = layout.get(i);
            if (!eq(col.name(), ref.column())) continue;
            if (ref.table() != null && !eq(col.qualifier(), ref.table()) && !eq(col.table(), ref.table())) {
                continue;
            }
            if (found != NOT_FOUND) return AMBIGUOUS;
            found = i;
        }
        return found;
    }

    private static int requireIndex(List<Col> layout, Expr.ColumnRef ref) {
        int index = find(layout, ref);
        if (index < 0) {
            throw new IllegalStateException(
                    (index == AMBIGUOUS ? "Ambiguous column reference: " : "Unresolved column: ")
                            + describe(ref));
        }
        return index;
    }

    static List<Expr.ColumnRef> refs(Expr expr) {
        List<Expr.ColumnRef> out = new ArrayList<>();
        collectRefs(expr, out);
        return out;
    }

    private static void collectRefs(Expr expr, List<Expr.ColumnRef> out) {
        if (expr instanceof Expr.ColumnRef ref) {
            out.add(ref);
        } else if (expr instanceof Expr.Binary bin) {
            collectRefs(bin.left(), out);
            collectRefs(bin.right(), out);
        }
    }

    static List<Expr> conjuncts(Expr expr) {
        List<Expr> out = new ArrayList<>();
        splitAnd(expr, out);
        return out;
    }

    private static void splitAnd(Expr expr, List<Expr> out) {
        if (expr instanceof Expr.Binary bin && bin.op() == Expr.Op.AND) {
            splitAnd(bin.left(), out);
            splitAnd(bin.right(), out);
        } else {
            out.add(expr);
        }
    }

    PlanNode rebind(PlanNode node) {
        if (node instanceof SelectionPlanNode sel) {
            PlanNode child = rebind(sel.child());
            return new SelectionPlanNode(rebind(sel.condition(), of(child)), child);
        }
        if (node instanceof ProjectionPlanNode proj) {
            PlanNode child = rebind(proj.child());
            List<Col> input = of(child);
            List<Expr> columns = new ArrayList<>();
            for (Expr expr : proj.columns()) columns.add(rebind(expr, input));
            return new ProjectionPlanNode(columns, child);
        }
        if (node instanceof SortPlanNode sort) {
            PlanNode child = rebind(sort.child());
            Expr.ColumnRef column = (Expr.ColumnRef) rebind(sort.column(), of(child));
            return new SortPlanNode(child, column, sort.direction());
        }
        if (node instanceof JoinPlanNode join) {
            PlanNode left = rebind(join.left());
            PlanNode right = rebind(join.right());
            Expr condition =
                    join.condition() == null
                            ? null
                            : rebind(join.condition(), concat(of(left), of(right)));
            return new JoinPlanNode(join.algorithm(), condition, left, right);
        }
        if (node instanceof CartesianPlanNode cart) {
            return new CartesianPlanNode(
                    cart.algorithm(), rebind(cart.left()), rebind(cart.right()));
        }
        return node;
    }

    private static Expr rebind(Expr expr, List<Col> layout) {
        if (expr instanceof Expr.ColumnRef ref) {
            return new Expr.ColumnRef(ref.table(), ref.column(), requireIndex(layout, ref));
        }
        if (expr instanceof Expr.Binary bin) {
            return new Expr.Binary(bin.op(), rebind(bin.left(), layout), rebind(bin.right(), layout));
        }
        return expr;
    }

    private static Expr.ColumnRef asColumn(Expr expr) {
        if (expr instanceof Expr.ColumnRef ref) return ref;
        throw new IllegalStateException("Projection only supports plain columns: " + expr);
    }

    private static List<Col> concat(List<Col> a, List<Col> b) {
        List<Col> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean eq(String a, String b) {
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }

    private static String describe(Expr.ColumnRef ref) {
        return ref.table() == null ? ref.column() : ref.table() + "." + ref.column();
    }
}
