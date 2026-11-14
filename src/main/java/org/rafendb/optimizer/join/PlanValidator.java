package org.rafendb.optimizer.join;

import static org.rafendb.optimizer.join.ColumnLayouts.matchesLayout;
import static org.rafendb.optimizer.join.ColumnLayouts.outputColumns;

import java.util.List;
import java.util.Objects;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.OptimizerInternalException.CostEstimationException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.*;

/**
 * Checks executable read-plan shapes and operator-local column bindings.
 * Uses shared layout and index rules. It does not bind names or check expression types.
 */
final class PlanValidator {
    private final StorageManager storage;
    private final AccessPathOptimizer access;

    /** Generated and incoming index scans must follow the same eligibility rules. */
    PlanValidator(StorageManager storage, AccessPathOptimizer access) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.access = Objects.requireNonNull(access, "access");
    }

    /** Rejects unsupported operators, invalid offsets and untyped SQL NULLs before costing. */
    void validate(PlanNode node) throws CostEstimationException {
        for (PlanNode child : node.children()) validate(child);
        if (node instanceof TableScanNode) return;
        if (node instanceof IndexScanNode scan) {
            List<Expr.ColumnRef> layout = outputColumns(scan, storage);
            requireBoundExpression(scan.descriptor().condition(), layout);
            if (!access.isEligibleIndexScan(scan)) {
                throw new CostEstimationException("Invalid index descriptor: " + scan.descriptor());
            }
            return;
        }
        if (node instanceof JoinPlanNode join) {
            requireBnl(join.algorithm());
            if (join.condition() == null) {
                throw new CostEstimationException(
                        "QP cannot translate unresolved NATURAL JOIN; resolve its condition and output projection first");
            }
            requireBoundExpression(join.condition(), outputColumns(join, storage));
        } else if (node instanceof CartesianPlanNode cartesian) {
            requireBnl(cartesian.algorithm());
        } else if (node instanceof SelectionPlanNode selection) {
            requireBoundExpression(
                    selection.condition(), outputColumns(selection.child(), storage));
        } else if (node instanceof ProjectionPlanNode projection) {
            if (projection.columns().isEmpty())
                throw new CostEstimationException("QP cannot execute an empty projection");
            List<Expr.ColumnRef> input = outputColumns(projection.child(), storage);
            for (Expr expression : projection.columns()) {
                if (!(expression instanceof Expr.ColumnRef))
                    throw new CostEstimationException(
                            "QP ProjectOp supports column references only: " + expression);
                requireBoundExpression(expression, input);
            }
        } else if (node instanceof SortPlanNode sort) {
            requireBoundExpression(sort.column(), outputColumns(sort.child(), storage));
        } else {
            throw new CostEstimationException(
                    "QP cannot translate plan node: " + node.getClass().getName());
        }
    }

    /** The plan translator supports only block nested-loop joins. */
    private static void requireBnl(JoinAlgorithm algorithm) throws CostEstimationException {
        if (algorithm != JoinAlgorithm.BLOCK_NESTED_LOOP)
            throw new CostEstimationException("QP does not support join algorithm: " + algorithm);
    }

    /** Offsets are local to this operator's input. Validation does not repair them. */
    private static void requireBoundExpression(Expr expression, List<Expr.ColumnRef> input)
            throws CostEstimationException {
        if (input == null || !matchesLayout(expression, input) || untypedNull(expression)) {
            throw new CostEstimationException(
                    "Invalid or unbound expression for input layout: " + expression);
        }
    }

    /** SQL NULL literals must have a type before evaluation. */
    private static boolean untypedNull(Expr expression) {
        return expression instanceof Expr.Null
                || expression instanceof Expr.Binary b
                        && (untypedNull(b.left()) || untypedNull(b.right()));
    }
}
