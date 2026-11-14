package org.rafendb.optimizer.driver;

import java.util.List;

/**
 * Pass/fail report for one script run (QO-16's last acceptance criterion).
 *
 * @param results every scenario's result, in script order
 */
public record TestReport(List<ScenarioResult> results) {
    /**
     * Returns the number of scenarios that passed.
     *
     * @return count where {@code result.passed()}
     */
    public long passCount() {
        return results.stream().filter(ScenarioResult::passed).count();
    }

    /**
     * Returns whether every scenario passed.
     *
     * @return {@code passCount() == results.size()}
     */
    public boolean allPassed() {
        return passCount() == results.size();
    }

    /**
     * Renders the report: one line per scenario ("PASS" or "FAIL" with its detail), then a summary
     * line ("X/Y scenarios passed").
     *
     * @return the report text
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (ScenarioResult result : results) {
            sb.append(result.passed() ? "PASS" : "FAIL").append(": ");
            sb.append(result.detail()).append("\n");
            if(!result.passed()) {
                sb.append(" (expected: ")
                        .append(result.scenario().expected()).append(", actual: ")
                        .append(result.actual()).append(")\n");
            }
        }
        sb.append(passCount()).append("/").append(results.size()).append(" scenarios passed.");
        return sb.toString();
    }
}