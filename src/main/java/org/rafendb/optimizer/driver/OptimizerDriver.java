package org.rafendb.optimizer.driver;

import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.Component;
import org.rafendb.driver.Driver;
import org.rafendb.mock.storage.MockStorageManager;

public final class OptimizerDriver extends Driver {

    private StorageManager storageManager;
    private ScenarioRunner runner;
    private int scenarioNumber;

    private OptimizerDriver() {
        super(Component.QO);
    }

    public static void main(String[] args) {
        new OptimizerDriver().launch(args);
    }

    @Override
    protected void setup() {
        storageManager = createStorageManager();
        runner = new ScenarioRunner(storageManager);
        scenarioNumber = 0;
    }

    private StorageManager createStorageManager() {
        if (mocked(Component.SM)) {
            return new MockStorageManager();
        }

        throw new IllegalStateException(
                "Real StorageManager initialization has not been configured. "
                        + "Use --mock=sm for now.");
    }

    /**
     * Format: EXPECTED_OUTCOME | SQL
     */
    @Override
    protected void fallback(String line) {

        String[] parts = line.split("\\|", 2);

        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed scenario. Expected: OUTCOME | SQL");
        }

        ScenarioOutcome expected = ScenarioOutcome.fromString(parts[0].trim());
        String sql = parts[1].trim();

        if (sql.isEmpty()) {
            throw new IllegalArgumentException("SQL query cannot be empty");
        }

        TestScenario scenario = new TestScenario("Scenario " + (++scenarioNumber), sql, expected);
        ScenarioResult result = runner.run(scenario);

        // Display details
        out().println("  SQL: " + sql);
        out().println("  Expected: " + expected);
        out().println("  Actual: " + result.actual());

        if (result.detail() != null && !result.detail().isBlank()) {
            out().println("  Detail: \n" + result.detail());
        }

        check(scenario.name(), result.passed());
    }

    @Override
    protected void close() throws Exception {
        if (storageManager != null) {
            storageManager.close();
            storageManager = null;
        }
    }
}
