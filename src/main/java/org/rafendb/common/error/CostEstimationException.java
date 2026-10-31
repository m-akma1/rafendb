package org.rafendb.common.error;

/** Failure while estimating plan cardinality or cost. */
public class CostEstimationException extends QoException {
    public CostEstimationException(String message) {
        super(ErrorCode.COST_ESTIMATION_ERROR, message);
    }

    public CostEstimationException(String message, Throwable cause) {
        super(ErrorCode.COST_ESTIMATION_ERROR, message, null, cause);
    }
}
