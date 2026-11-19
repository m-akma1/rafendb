package org.rafendb.optimizer.join;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.*;
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
                if (!(expr instanceof Expr.ColumnRef column) || !matchesLayout(column, input))
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

    /** Rebinds validated offsets, using sourceToTargetOffsets[source] as the new local position. */
    static Expr remapColumnOffsets(Expr expr, int[] sourceToTargetOffsets) {
        if (expr instanceof Expr.ColumnRef column) {
            return new Expr.ColumnRef(
                    column.table(), column.column(), sourceToTargetOffsets[column.offset()]);
        }
        if (expr instanceof Expr.Binary binary) {
            Expr left = remapColumnOffsets(binary.left(), sourceToTargetOffsets);
            Expr right = remapColumnOffsets(binary.right(), sourceToTargetOffsets);
            return new Expr.Binary(binary.op(), left, right);
        }
        return expr;
    }

    /** Checks that each bound column matches the name and qualifier at its offset. */
    static boolean matchesLayout(Expr expr, List<Expr.ColumnRef> columns) {
        if (expr instanceof Expr.ColumnRef column) {
            if (column.offset() < 0 || column.offset() >= columns.size()) return false;
            Expr.ColumnRef expected = columns.get(column.offset());
            return column.column().equals(expected.column())
                    && (column.table() == null || column.table().equals(expected.table()));
        }
        if (expr instanceof Expr.Binary binary) {
            return matchesLayout(binary.left(), columns) && matchesLayout(binary.right(), columns);
        }
        return true;
    }
}
