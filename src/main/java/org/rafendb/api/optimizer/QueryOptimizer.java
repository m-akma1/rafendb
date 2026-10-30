package org.rafendb.api.optimizer;

import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.plan.QueryPlan;

public interface QueryOptimizer {
    ParsedQuery parse(String sql);
    void bindAndValidate(ParsedQuery parsed);
    QueryPlan optimize(ParsedQuery parsed);
    String explain(QueryPlan plan);
}
