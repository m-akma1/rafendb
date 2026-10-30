package org.rafendb.mock.optimizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.api.optimizer.QueryOptimizer;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.query.ParsedQuery;

/**
 * Mock {@link QueryOptimizer} for testing and integration without a real optimizer.
 *
 * <p>This implementation contains no parsing, validation, or optimization logic.
 * It records calls and returns trivial, well-formed objects so that callers
 * (Query Processor, driver program, tests) can be wired up and exercised before
 * the real optimizer exists. Not thread-safe.</p>
 *
 * <p>Behaviour:</p>
 * <ul>
 *   <li>{@link #parse(String)} returns a placeholder {@link ParsedQuery} that
 *       echoes the SQL — no tokenization is performed.</li>
 *   <li>{@link #bindAndValidate(ParsedQuery)} does nothing (no catalog access).</li>
 *   <li>{@link #optimize(ParsedQuery)} returns a single-node plan whose cost is
 *       a fixed constant.</li>
 *   <li>{@link #explain(QueryPlan)} returns {@link PlanNode#describe()} of the
 *       root prefixed with the total cost.</li>
 * </ul>
 *
 * <p>Call recording is exposed via {@link #getCalls()} so tests can assert the
 * pipeline was invoked in the expected order. Reset with {@link #reset()}.</p>
 */
public class MockQueryOptimizer implements QueryOptimizer {

    /** Fixed cost returned by {@link #optimize(ParsedQuery)}. */
    public static final double DEFAULT_COST = 1.0;

    private final List<String> calls = new ArrayList<>();

    /** Creates a mock optimizer with no recorded calls. */
    public MockQueryOptimizer() {}

    @Override
    public ParsedQuery parse(String sql) {
        Objects.requireNonNull(sql, "sql");
        calls.add("parse");
        return new PlaceholderParsedQuery(sql);
    }

    @Override
    public void bindAndValidate(ParsedQuery parsed) {
        Objects.requireNonNull(parsed, "parsed");
        calls.add("bindAndValidate");
        // Intentionally empty: no catalog access in the mock.
    }

    @Override
    public QueryPlan optimize(ParsedQuery parsed) {
        Objects.requireNonNull(parsed, "parsed");
        calls.add("optimize");
        PlanNode root = new PlaceholderPlanNode("MockPlan(" + parsed.originalSql() + ")");
        return new QueryPlan(root, DEFAULT_COST);
    }

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
}
