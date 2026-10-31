package org.rafendb.optimizer;

import org.rafendb.api.optimizer.QueryOptimizer;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.optimizer.binder.QueryBinder;
import org.rafendb.optimizer.lexer.SqlTokenizer;
import org.rafendb.optimizer.lexer.Tokenizer;

public class QueryOptimizerImpl implements QueryOptimizer {

    private final Tokenizer tokenizer = new SqlTokenizer();
    private final QueryBinder binder = new QueryBinder();

    @Override
    public ParsedQuery parse(String sql) {
        var tokens = tokenizer.tokenize(sql);
        // TODO: Pass tokens to DmlParser, CommandParser, or SelectParser
        throw new UnsupportedOperationException("Parsing not fully wired yet");
    }

    @Override
    public void bindAndValidate(ParsedQuery parsed) {
        binder.bindAndValidate(parsed);
    }

    @Override
    public QueryPlan optimize(ParsedQuery parsed) {
        // TODO: Apply logical rewrites (org.rafendb.optimizer.rewrite.*)
        // TODO: Apply cost-based physical plan selection (org.rafendb.optimizer.join.*)
        throw new UnsupportedOperationException("Optimization pipeline incomplete");
    }

    @Override
    public String explain(QueryPlan plan) {
        // TODO: Recursively traverse plan.getRootNode().children() to build formatted tree string
        return "EXPLAIN OUTPUT";
    }
}
