package org.rafendb.processor.session;

import java.util.List;
import java.util.Optional;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.query.CreateIndexQuery;
import org.rafendb.common.query.DeleteQuery;
import org.rafendb.common.query.InsertQuery;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.UpdateQuery;
import org.rafendb.common.type.Value;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.expr.ExpressionEvaluator;
import org.rafendb.processor.plan.command.CreateIndexPlanNode;
import org.rafendb.processor.plan.command.DeletePlanNode;
import org.rafendb.processor.plan.command.InsertPlanNode;
import org.rafendb.processor.plan.command.UpdatePlanNode;
import org.rafendb.processor.tuple.ResultRow;

/**
 * Turns a bound data-changing statement into its command plan node. The optimizer plans SELECT
 * only, so the session plans these itself. Pure: it reads no storage and starts nothing.
 */
final class CommandPlanner {
    private static final ResultRow NO_ROW = new ResultRow(List.of(), null);

    private final ExpressionEvaluator evaluator = new DefaultExpressionEvaluator();

    /**
     * Returns whether {@link #plan} handles the statement.
     *
     * @param query a bound statement
     * @return {@code true} for INSERT, UPDATE, DELETE, and CREATE INDEX
     */
    static boolean handles(ParsedQuery query) {
        return query instanceof InsertQuery
                || query instanceof UpdateQuery
                || query instanceof DeleteQuery
                || query instanceof CreateIndexQuery;
    }

    /**
     * Plans one statement.
     *
     * @param query a bound statement that {@link #handles} accepts
     * @return its command node
     * @throws IllegalArgumentException if the statement is not a command this planner knows
     */
    PlanNode plan(ParsedQuery query) {
        return switch (query) {
            case InsertQuery insert -> new InsertPlanNode(insert.table(), values(insert.values()));
            case UpdateQuery update ->
                    new UpdatePlanNode(
                            update.table(),
                            new Expr.ColumnRef(update.table(), update.column()),
                            update.value(),
                            Optional.ofNullable(update.where()));
            case DeleteQuery delete ->
                    new DeletePlanNode(delete.table(), Optional.ofNullable(delete.where()));
            case CreateIndexQuery index ->
                    new CreateIndexPlanNode(
                            index.indexName(),
                            index.table(),
                            new Expr.ColumnRef(index.table(), index.column()),
                            switch (index.indexType()) {
                                case B_PLUS_TREE -> CreateIndexPlanNode.IndexKind.BTREE;
                                case HASH -> CreateIndexPlanNode.IndexKind.HASH;
                            });
            default -> throw new IllegalArgumentException("Not a command: " + query);
        };
    }

    /** INSERT values reference no column, so each one is evaluated on its own (e.g. 2 + 3). */
    private List<Value> values(List<Expr> values) {
        return values.stream().map(value -> evaluator.evaluate(value, NO_ROW, List.of())).toList();
    }
}
