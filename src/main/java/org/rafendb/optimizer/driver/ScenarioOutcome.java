package org.rafendb.optimizer.driver;

public enum ScenarioOutcome {
    /** The query parses, binds and optimizes without error. */
    VALID,

    /** The query is rejected during tokenizing or parsing (QO-01): an {@code OptimizerRuntimeException}
     * whose {@code getCode()} is {@code ErrorCode.SYNTAX_ERROR}. */
    SYNTAX_ERROR,

    /** The query parses but fails semantic validation (QO-07): an {@code OptimizerRuntimeException}
     * whose {@code getCode()} is {@code ErrorCode.VALIDATION_ERROR}. */
    VALIDATION_ERROR;

    public static ScenarioOutcome fromString(String str) {
        switch (str) {
            case "VALID":
                return VALID;
            case "SYNTAX_ERROR":
                return SYNTAX_ERROR;
            case "VALIDATION_ERROR":
                return VALIDATION_ERROR;
            default:
                throw new IllegalArgumentException("Unknown scenario outcome: " + str);
        }
    }
}
