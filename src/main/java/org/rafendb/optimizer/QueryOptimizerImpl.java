package org.rafendb.optimizer;

import org.rafendb.api.optimizer.QueryOptimizer;
import org.rafendb.common.error.OptimizerInternalException;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.optimizer.binder.QueryBinder;
import org.rafendb.optimizer.explain.PlanExplainer;
import org.rafendb.optimizer.join.JoinAccessPathOptimizer;
import org.rafendb.optimizer.parser.QueryParser;
import org.rafendb.optimizer.plan.InitialPlanBuilder;

public class QueryOptimizerImpl implements QueryOptimizer {
    private final QueryParser parser;
    private final QueryBinder binder;
    private final JoinAccessPathOptimizer optimizer;
    private final InitialPlanBuilder planBuilder;

    public QueryOptimizerImpl(
            QueryParser parser,
            QueryBinder binder,
            JoinAccessPathOptimizer optimizer,
            InitialPlanBuilder planBuilder) {
        this.parser = parser;
        this.binder = binder;
        this.optimizer = optimizer;
        this.planBuilder = planBuilder;
    }

    /** {@inheritDoc} */
    @Override
    public ParsedQuery parse(String sql) {
        return bindAndValidate(this.parser.parse(sql));
    }

    private ParsedQuery bindAndValidate(ParsedQuery parsed) {
        return this.binder.bind(parsed);
    }

    /** {@inheritDoc} */
    @Override
    public QueryPlan optimize(ParsedQuery bound) throws OptimizerInternalException {
        if (!(bound instanceof SelectQuery select)) {
            throw new UnsupportedOperationException(
                    "Only SELECT queries are supported for optimization");
        }
        try {
            QueryPlan plan = this.planBuilder.build(select);
            return this.optimizer.optimize(plan);
        } catch (OptimizerInternalException e) {
            throw new OptimizerInternalException(
                    "Failed to optimize query: " + bound + ": " + e.getMessage(), e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String explain(QueryPlan plan) {
        return PlanExplainer.explain(plan);
    }
}
