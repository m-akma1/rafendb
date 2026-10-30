package org.rafendb.common.plan;

public enum JoinAlgorithm {
    BLOCK_NESTED_LOOP("Block Nested Loop Join"),
    NESTED_LOOP("Nested Loop Join"),
    HASH_JOIN("Hash Join"),
    SORT_MERGE("Sort Merge Join");

    private final String displayName;

    JoinAlgorithm(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
