package org.rafendb.storage.index;

import java.util.Objects;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.Value;

/**
 * One index entry: a key and the record holding it. Entries order by key, then Rid, so each one
 * is unique even when keys repeat; that is what lets an index keep duplicate keys, delete exactly
 * one record's entry, and ignore a repeated insert during redo.
 *
 * <p>Immutable and thread-safe.
 *
 * @param key the key, non-NULL
 * @param rid the record's address within the index's table
 */
public record IndexEntry(Value key, Rid rid) implements Comparable<IndexEntry> {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code key} or {@code rid} is {@code null}
     * @throws IllegalArgumentException if {@code key} is NULL
     */
    public IndexEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(rid, "rid");
        if (key.isNull()) {
            throw new IllegalArgumentException("NULL keys are not indexed");
        }
    }

    /**
     * Returns the smallest possible entry for a key, to start a search at the first entry with
     * that key. Its Rid, block 0 slot 0, is the smallest valid Rid.
     *
     * @param key the key, non-NULL
     * @return the entry
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IllegalArgumentException if {@code key} is NULL
     */
    public static IndexEntry probe(Value key) {
        return new IndexEntry(key, new Rid(0, 0));
    }

    /**
     * Compares by key, then Rid.
     *
     * @param other the entry to compare with, whose key is of the same family
     * @return negative, zero or positive as this is less than, equal to or greater than {@code
     *     other}
     * @throws NullPointerException if {@code other} is {@code null}
     * @throws IllegalArgumentException if the key families differ
     */
    @Override
    public int compareTo(IndexEntry other) {
        int byKey = IndexKeys.compare(key, other.key);
        return byKey != 0 ? byKey : rid.compareTo(other.rid);
    }
}
