package org.rafendb.common.schema;

/** The index structures the engine supports; the name of each constant is its SQL spelling. */
public enum IndexType {
    /** B+ tree index; also the default when CREATE INDEX gives no USING clause. */
    BTREE,
    /** Hash index. */
    HASH
}
