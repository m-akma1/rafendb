package org.rafendb.optimizer.driver;

/**
 * One line of a test script read by {@link ScriptLoader} (QO-16): a query and the outcome it must
 * produce.
 *
 * @param name a short label for reports, e.g. {@code "select-star"} or the script's line number
 * @param sql the SQL text to run, exactly as written in the script
 * @param expected what running {@code sql} must produce
 */
public record TestScenario(String name, String sql, ScenarioOutcome expected) {}
