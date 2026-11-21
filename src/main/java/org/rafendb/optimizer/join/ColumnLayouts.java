package org.rafendb.optimizer.join;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.*;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.common.schema.Column;

/**
 * Tracks output column names and remaps operator-local offsets.
 * Layout entries describe row order but are unbound. Unknown layouts return null.
 */
final class ColumnLayouts {
    private ColumnLayouts() {}

    /** Returns columns in output order, or null when the layout is unknown. */
    static List<Expr.ColumnRef> outputColumns(PlanNode node, StorageManager storage) {
        String table;
        String qualifier;
        if (node instanceof TableScanNode scan) {
            table = scan.table();
            qualifier = scan.alias() == null ? table : scan.alias();
        } else if (node instanceof IndexScanNode scan) {
            table = scan.table();
            qualifier = scan.alias() == null ? table : scan.alias();
        } else if (node instanceof SelectionPlanNode selection) {
            return outputColumns(selection.child(), storage);
        } else if (node instanceof SortPlanNode sort) {
            return outputColumns(sort.child(), storage);
        } else if (node instanceof ProjectionPlanNode projection) {
            List<Expr.ColumnRef> input = outputColumns(projection.child(), storage);
            if (input == null) return null;
            List<Expr.ColumnRef> result = new ArrayList<>();
            for (Expr expr : projection.columns()) {
                if (!(expr instanceof Expr.ColumnRef column) || !matchesColumn(column, input))
                    return null;
                // ProjectOp keeps the written qualifier, including null, in output headers.
                result.add(new Expr.ColumnRef(column.table(), column.column()));
            }
            return result;
        } else if (node instanceof JoinPlanNode join && join.condition() != null) {
            return concatenatedColumns(join.left(), join.right(), storage);
        } else if (node instanceof CartesianPlanNode cartesian) {
            return concatenatedColumns(cartesian.left(), cartesian.right(), storage);
        } else {
            return null;
        }
        List<Expr.ColumnRef> result = new ArrayList<>();
        for (Column column : storage.getSchema(table).columns()) {
            result.add(new Expr.ColumnRef(qualifier, column.name()));
        }
        return result;
    }

    private static List<Expr.ColumnRef> concatenatedColumns(
            PlanNode left, PlanNode right, StorageManager storage) {
        List<Expr.ColumnRef> leftColumns = outputColumns(left, storage);
        List<Expr.ColumnRef> rightColumns = outputColumns(right, storage);
        if (leftColumns == null || rightColumns == null) return null;
        List<Expr.ColumnRef> result = new ArrayList<>(leftColumns);
        result.addAll(rightColumns);
        return result;
    }

    /** Rebinds local offsets in movable predicates. Enclosing-scope bindings stay intact. */
    static Expr remapColumnOffsets(Expr expr, int[] sourceToTargetOffsets) {
        if (expr instanceof Expr.ColumnRef column) {
            if (column.scopeDepth() != 0) return column;
            return new Expr.ColumnRef(
                    column.table(), column.column(), sourceToTargetOffsets[column.offset()]);
        }
        if (expr instanceof Expr.Binary binary) {
            Expr left = remapColumnOffsets(binary.left(), sourceToTargetOffsets);
            Expr right = remapColumnOffsets(binary.right(), sourceToTargetOffsets);
            return new Expr.Binary(binary.op(), left, right);
        }
        if (expr instanceof Expr.Not not) {
            return new Expr.Not(remapColumnOffsets(not.child(), sourceToTargetOffsets));
        }
        return expr;
    }

    /** Checks bindings in this plan. The embedded SELECT is checked as its own plan. */
    static boolean matchesLayout(Expr expr, List<Expr.ColumnRef> columns) {
        return checkExpression(expr, column -> matchesColumn(column, columns), true);
    }

    /** Subqueries stay at their original evaluation sites during join and access search. */
    static boolean canMovePredicate(Expr expr) {
        return checkExpression(expr, column -> true, false);
    }

    private static boolean matchesColumn(Expr.ColumnRef column, List<Expr.ColumnRef> columns) {
        if (column.offset() < 0 || column.scopeDepth() < 0) return false;
        if (column.scopeDepth() > 0) return true;
        if (column.offset() >= columns.size()) return false;
        Expr.ColumnRef expected = columns.get(column.offset());
        return column.column().equals(expected.column())
                && (column.table() == null || column.table().equals(expected.table()));
    }

    private static boolean checkExpression(
            Expr expr, Predicate<Expr.ColumnRef> matchesColumn, boolean allowSubqueries) {
        return switch (expr) {
            case Expr.ColumnRef column -> matchesColumn.test(column);
            case Expr.Binary binary ->
                    checkExpression(binary.left(), matchesColumn, allowSubqueries)
                            && checkExpression(binary.right(), matchesColumn, allowSubqueries);
            case Expr.Not not -> checkExpression(not.child(), matchesColumn, allowSubqueries);
            case Expr.ScalarSubquery sub -> allowSubqueries && hasSingleColumn(sub.query());
            case Expr.ExistsSubquery sub -> allowSubqueries && sub.query() != null;
            case Expr.InSubquery sub ->
                    allowSubqueries
                            && hasSingleColumn(sub.query())
                            // The binder leaves a bare SQL NULL probe untyped.
                            && (sub.value() instanceof Expr.Null
                                    || checkExpression(sub.value(), matchesColumn, true));
            case Expr.Literal _ -> true;
            case Expr.Null _ -> false;
            case null -> false;
        };
    }

    private static boolean hasSingleColumn(SelectQuery query) {
        return query != null && query.projection().size() == 1;
    }
}
