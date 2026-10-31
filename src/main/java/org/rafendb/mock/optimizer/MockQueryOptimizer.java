package org.rafendb.mock.optimizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.api.optimizer.QueryOptimizer;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.query.ParsedQuery;

/**
 * Component-level {@link QueryOptimizer} mock that returns deterministic dummy results.
 *
 * <p>No SQL parsing, catalog access, rewriting, or cost estimation is performed.
 * Queries retain the original SQL, and plans contain a dummy leaf with a fixed cost.
 * Supporting mock query and node implementations are private to this class.
 *
 * <p>Not thread-safe. Calls are recorded in order through {@link #getCalls()};
 * {@link #reset()} clears the log.
 */
public class MockQueryOptimizer implements QueryOptimizer {

    /** Fixed cost returned by {@link #optimize(ParsedQuery)}. */
    public static final double DEFAULT_COST = 1.0;

    private final List<String> calls = new ArrayList<>();

    /** Creates a mock optimizer with no recorded calls. */
    public MockQueryOptimizer() {}

    /** {@inheritDoc} Returns a dummy query containing the original SQL without parsing it. */
    @Override
    public ParsedQuery parse(String sql) {
        Objects.requireNonNull(sql, "sql");
        calls.add("parse");
        return new MockParsedQuery(sql);
    }

    /** {@inheritDoc} Succeeds without accessing schema metadata. */
    @Override
    public void bindAndValidate(ParsedQuery parsed) {
        Objects.requireNonNull(parsed, "parsed");
        calls.add("bindAndValidate");
        // Intentionally empty: no catalog access in the mock.
    }

    /** {@inheritDoc} Returns a dummy leaf plan with the fixed {@link #DEFAULT_COST}. */
    @Override
    public QueryPlan optimize(ParsedQuery parsed) {
        Objects.requireNonNull(parsed, "parsed");
        calls.add("optimize");
        PlanNode root = new MockPlanNode("MockPlan(" + parsed.originalSql() + ")");
        return new QueryPlan(root, DEFAULT_COST);
    }

    /** {@inheritDoc} Describes only the supplied root and total cost. */
    @Override
    public String explain(QueryPlan plan) {
        Objects.requireNonNull(plan, "plan");
        calls.add("explain");
        return plan.getRoot().describe() + " [cost=" + plan.getTotalCost() + "]";
    }

    /**
     * Returns the names of the methods invoked on this mock, in call order
     * (e.g. {@code ["parse", "bindAndValidate", "optimize"]}).
     *
     * @return an unmodifiable snapshot of recorded call names
     */
    public List<String> getCalls() {
        return List.copyOf(calls);
    }

    /** Clears the recorded call list. */
    public void reset() {
        calls.clear();
    }

    /** Immutable dummy query that preserves the original SQL. */
    private static final class MockParsedQuery implements ParsedQuery {
        private final String sql;

        /**
         * Stores SQL supplied by the mock's parsing entry point.
         *
         * @param sql the original, non-null SQL
         */
        private MockParsedQuery(String sql) {
            this.sql = sql;
        }

        /** {@inheritDoc} */
        @Override
        public String originalSql() {
            return sql;
        }

        /** @return the existing diagnostic representation of this dummy query */
        @Override
        public String toString() {
            return "PlaceholderParsedQuery{" + sql + "}";
        }
    }

    /** Immutable dummy leaf with a fixed description and no children. */
    private static final class MockPlanNode implements PlanNode {
        private final String description;

        /**
         * Stores the description supplied by the mock optimizer.
         *
         * @param description the non-null description
         */
        private MockPlanNode(String description) {
            this.description = description;
        }

        /** {@inheritDoc} */
        @Override
        public List<PlanNode> children() {
            return List.of();
        }

        /** {@inheritDoc} */
        @Override
        public String describe() {
            return description;
        }
    }
}
