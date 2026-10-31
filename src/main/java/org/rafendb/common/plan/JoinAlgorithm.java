package org.rafendb.common.plan;

public enum JoinAlgorithm {
    BLOCK_NESTED_LOOP,
    NESTED_LOOP,
    HASH_JOIN,
    SORT_MERGE;
}