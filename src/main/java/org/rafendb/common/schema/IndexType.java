package org.rafendb.common.schema;

/**
 * Index structures.
 *
 * <p>Immutable and thread-safe.
 */
public enum IndexType {
    /** Hash index; equality search only. */
    HASH,
    /** B+ tree index; equality and range search. */
    B_PLUS_TREE;

    /**
     * Returns whether range search is supported.
     *
     * @return {@code true} only for {@link #B_PLUS_TREE}
     */
    public boolean supportsRange() {
        return this == B_PLUS_TREE;
    }
}
