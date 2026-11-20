package org.rafendb.optimizer.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.error.OptimizerInternalException;
import org.rafendb.common.error.OptimizerRuntimeException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.*;
import org.rafendb.common.query.FromItem;
import org.rafendb.common.query.OrderByItem;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.optimizer.cost.CostModel;

/**
 * Builds an initial physical query plan from a bound SelectQuery.
 *
 * The generated plan has not undergone optimization.
 */
public final class InitialPlanBuilder {
    private final CostModel model;

    public InitialPlanBuilder(CostModel model) {
        this.model = model;
    }

    /**
     * Builds the initial QueryPlan.
     *
     * @param query a SelectQuery that has passed QueryBinder
     * @return initial query plan with estimated cost
     */
    public QueryPlan build(SelectQuery query) throws OptimizerInternalException {
        Objects.requireNonNull(query, "query must not be null");

        PlanNode root = buildFrom(query.from());
        if (query.whereCondition() != null) {
            root = new SelectionPlanNode(query.whereCondition(), root);
        }
        List<OrderByItem> orderBy = query.orderBy();
        for (int i = orderBy.size() - 1; i >= 0; i--) {
            OrderByItem item = orderBy.get(i);

            if (!(item.expression() instanceof Expr.ColumnRef column)) {
                throw new OptimizerRuntimeException.SyntaxException(
                        "ORDER BY requires a column reference");
            }

            root =
                    new SortPlanNode(
                            root,
                            column,
                            item.ascending()
                                    ? SortPlanNode.Direction.ASC
                                    : SortPlanNode.Direction.DESC);
        }
        root = new ProjectionPlanNode(query.projection(), root);

        double totalCost = model.getCost(root);

        return new QueryPlan(root, totalCost);
    }

    /**
     * Recursively converts the FROM tree into physical plan nodes.
     */
    private PlanNode buildFrom(FromItem item) throws OptimizerInternalException {
        return switch (item) {
            case FromItem.TableRef table -> new TableScanNode(null, table.name());
            case FromItem.CartesianProduct product ->
                    new CartesianPlanNode(
                            JoinAlgorithm.BLOCK_NESTED_LOOP,
                            buildFrom(product.left()),
                            buildFrom(product.right()));
            case FromItem.Join join ->
                    new JoinPlanNode(
                            JoinAlgorithm.BLOCK_NESTED_LOOP,
                            join.condition(),
                            buildFrom(join.left()),
                            buildFrom(join.right()));
            case FromItem.NaturalJoin _ ->
                    throw new OptimizerInternalException(
                            "NaturalJoin must be resolved by QueryBinder");
        };
    }
}
