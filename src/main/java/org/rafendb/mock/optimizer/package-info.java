/**
 * Mock of {@code api.optimizer}. {@link MockQueryOptimizer} records calls and returns trivial, well-formed results ({@link MockParsedQuery}, {@link MockPlanNode}), so other components can run without the real optimizer. The subpackages hold mocks of optimizer internals.
 *
 * <p>Owner: G3 Query Optimizer.
 */
package org.rafendb.mock.optimizer;
