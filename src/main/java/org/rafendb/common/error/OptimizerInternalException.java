package org.rafendb.common.error;

/**
 * Query Optimizer's {@link RafenDBInternalException}: a failure the client did not cause. Checked:
 * must be handled by the internal caller, never shown to the user.
 *
 * <p>Thrown directly it means {@link ErrorCode#INTERNAL_ERROR}: an unexpected failure inside QO,
 * such as a bug, a violated invariant, or a feature not implemented yet. The nested class tells a
 * specific kind apart:
 *
 * <ul>
 *   <li>{@link CostEstimationException} (QO-08, QO-12 to QO-14)
 * </ul>
 */
public class OptimizerInternalException extends RafenDBInternalException {
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    /**
     * Creates an {@link ErrorCode#INTERNAL_ERROR} exception with a message.
     *
     * @param message the detail message
     */
    public OptimizerInternalException(String message) {
        this(ErrorCode.INTERNAL_ERROR, message, null);
    }

    /**
     * Creates an {@link ErrorCode#INTERNAL_ERROR} exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public OptimizerInternalException(String message, Throwable cause) {
        this(ErrorCode.INTERNAL_ERROR, message, cause);
    }

    /**
     * Creates an exception for a specific kind of failure.
     *
     * @param code the error code for this kind of failure
     * @param message the detail message
     * @param cause the underlying cause; {@code null} if none
     */
    protected OptimizerInternalException(ErrorCode code, String message, Throwable cause) {
        super(Component.QO, message, cause);
        this.code = code;
    }

    /**
     * Returns the error code reported to the Query Processor.
     *
     * @return {@link ErrorCode#INTERNAL_ERROR} or {@link ErrorCode#COST_ESTIMATION_ERROR}
     */
    public ErrorCode getCode() {
        return code;
    }

    /**
     * The query is valid but QO cannot compute a cost or choose a plan (QO-08, QO-12 to QO-14):
     * table statistics from the Storage Manager are missing or corrupt, a needed join algorithm is
     * not offered by the Query Processor, or no valid plan candidate remains after the search.
     */
    public static class CostEstimationException extends OptimizerInternalException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates an exception with a message.
         *
         * @param message the detail message, naming the table, statistic, or algorithm involved
         */
        public CostEstimationException(String message) {
            super(ErrorCode.COST_ESTIMATION_ERROR, message, null);
        }

        /**
         * Creates an exception with a message and a cause.
         *
         * @param message the detail message, naming the table, statistic, or algorithm involved
         * @param cause the underlying cause
         */
        public CostEstimationException(String message, Throwable cause) {
            super(ErrorCode.COST_ESTIMATION_ERROR, message, cause);
        }
    }
}
