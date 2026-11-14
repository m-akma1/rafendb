package org.rafendb.optimizer.driver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.rafendb.mock.storage.MockStorageManager;

public final class OptimizerDriver {
    private OptimizerDriver() {}

    /**
     * Loads the script named in {@code args[0]}, runs every scenario, prints the report, and exits
     * with status 1 if any scenario failed (so this can be wired into a build's test step).
     *
     * @param args {@code args[0]} is the script path
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: OptimizerDriver <script-path>");
            System.exit(2);
        }
        List<TestScenario> scenarios;
        try {
            scenarios = ScriptLoader.load(Path.of(args[0]));
        } catch (IOException e) {
            System.err.println("Error loading script: " + e.getMessage());
            System.exit(2);
            return;
        }
        List<ScenarioResult> results = new ArrayList<>();
        ScenarioRunner runner = new ScenarioRunner(new MockStorageManager());
        for (TestScenario scenario : scenarios) {
            results.add(runner.run(scenario));
        }
        TestReport report = new TestReport(results);
        System.out.println(report.render());
        if (!report.allPassed()) System.exit(1);
    }
}
