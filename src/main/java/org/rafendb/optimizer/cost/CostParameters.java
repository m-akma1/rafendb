package org.rafendb.optimizer.cost;

/**
 * Immutable physical assumptions used by {@link StatsCostModel}.
 *
 * @param seekMillis estimated milliseconds per seek, finite and nonnegative
 * @param transferMillis estimated milliseconds per block transfer, finite and nonnegative
 * @param blockSizeBytes block size in bytes, positive
 * @param bufferBlocks available buffer block count, at least three
 * @param defaultEqSelectivity fallback equality selectivity in [0, 1]
 * @param rangeSelectivity heuristic range selectivity in [0, 1]
 */
public record CostParameters(
        double seekMillis,
        double transferMillis,
        int blockSizeBytes,
        int bufferBlocks,
        double defaultEqSelectivity,
        double rangeSelectivity) {
    /**
     * Creates validated parameters.
     *
     * @throws IllegalArgumentException if a value is outside its documented range
     */
    public CostParameters {
        if (!Double.isFinite(seekMillis)
                || seekMillis < 0
                || !Double.isFinite(transferMillis)
                || transferMillis < 0
                || blockSizeBytes <= 0
                || bufferBlocks < 3
                || !Double.isFinite(defaultEqSelectivity)
                || defaultEqSelectivity < 0
                || defaultEqSelectivity > 1
                || !Double.isFinite(rangeSelectivity)
                || rangeSelectivity < 0
                || rangeSelectivity > 1)
            throw new IllegalArgumentException("Invalid cost parameters");
    }

    /**
     * Returns defaults: 4 ms/seek, 0.1 ms/transfer, 4096-byte blocks, M=10, equality=0.1 and
     * range=1/3.
     *
     * @return immutable default parameters
     */
    public static CostParameters defaults() {
        return new CostParameters(Cost.SEEK_MILLIS, Cost.TRANSFER_MILLIS, 4096, 10, 0.1, 1.0 / 3.0);
    }
}