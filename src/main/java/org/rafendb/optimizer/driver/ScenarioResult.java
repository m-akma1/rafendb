package org.rafendb.optimizer.driver;

public record ScenarioResult(
        TestScenario scenario, ScenarioOutcome actual, boolean passed, String detail) {}
