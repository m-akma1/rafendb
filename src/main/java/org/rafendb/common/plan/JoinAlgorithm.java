package org.rafendb.common.plan;

/**
 * Join algorithms a {@link JoinPlanNode} can use.
 *
 * <p>Immutable and thread-safe.
 */
public enum JoinAlgorithm {
    /** Block nested-loop join. */
    BLOCK_NESTED_LOOP,
    /** Tuple-at-a-time nested-loop join. */
    NESTED_LOOP,
    /** Hash join. */
    HASH_JOIN,
    /** Sort-merge join. */
    SORT_MERGE;
}
