package org.rafendb.storage.index;

import java.util.Objects;
import java.util.Optional;
import org.rafendb.common.error.StorageException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/**
 * Key rules shared by every index structure: which keys a column accepts, how keys compare, and
 * which columns are narrow enough to index.
 *
 * <p>Keys are never converted. A key must be of the column's type family (INTEGER, FLOAT, or
 * text, where CHAR and VARCHAR are one family), and text compares with {@link String#compareTo},
 * without padding or case folding, exactly like {@link Value#compareTo}.
 *
 * <p>Stateless and thread-safe.
 */
public final class IndexKeys {

    /** Bytes of a B+ tree node header: {@code isLeaf}, {@code count} and {@code nextLeaf}. */
    static final int NODE_HEADER_BYTES = 11;

    /** Bytes of a child page number in an internal node. */
    static final int CHILD_BYTES = 8;

    /**
     * Entries a B+ tree node must hold, so that splitting an overfull node leaves both halves
     * non-empty after one separator moves up.
     */
    static final int MIN_NODE_ENTRIES = 3;

    /** Bytes of a hash bucket header: {@code count} and {@code nextOverflow}. */
    static final int BUCKET_HEADER_BYTES = 10;

    private IndexKeys() {}

    /**
     * Checks a search key or range bound against the indexed column.
     *
     * <p>The type family is checked first, so a NULL of another family is still rejected; only
     * then does a NULL key yield empty, since NULL keys are not indexed.
     *
     * @param key the key or bound, possibly NULL
     * @param column the indexed column
     * @return {@code key} itself, or empty if it is NULL
     * @throws NullPointerException if an argument is {@code null}
     * @throws StorageException if {@code key} is of another type family than {@code column}
     */
    public static Optional<Value> normalize(Value key, Column column) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(column, "column");
        DataType keyType = key.type();
        DataType columnType = column.type();
        if (keyType != columnType && !(keyType.isText() && columnType.isText())) {
            throw new StorageException(
                    "key of type "
                            + keyType
                            + " does not match column "
                            + column.name()
                            + " of type "
                            + columnType);
        }
        return key.isNull() ? Optional.empty() : Optional.of(key);
    }

    /**
     * Compares two keys in index order.
     *
     * @param a a non-NULL key
     * @param b a non-NULL key of the same family as {@code a}
     * @return negative, zero or positive as {@code a} is less than, equal to or greater than
     *     {@code b}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if either key is NULL or the families differ
     */
    public static int compare(Value a, Value b) {
        return a.compareTo(b);
    }

    /**
     * Checks that a column's widest entry fits the index structure's pages.
     *
     * <p>A B+ tree internal node must hold {@value #MIN_NODE_ENTRIES} entries with their child
     * pointers plus one more child, so it rejects {@code 3 * maxEntry + 43 > pageSize}; with
     * 4096-byte pages, text columns longer than 1337 bytes. A hash bucket page must hold one
     * entry, so it rejects {@code maxEntry + 10 > pageSize}, which no column up to {@link
     * Column#MAX_TEXT_LENGTH} reaches with 4096-byte pages.
     *
     * @param column the column to index
     * @param type the index structure
     * @param pageSize the page size in bytes
     * @throws NullPointerException if {@code column} or {@code type} is {@code null}
     * @throws StorageException if the column is too wide for {@code type}
     */
    public static void requireIndexable(Column column, IndexType type, int pageSize) {
        Objects.requireNonNull(type, "type");
        int maxEntry = KeyCodec.maxEntrySize(column);
        int needed =
                switch (type) {
                    case B_PLUS_TREE ->
                            NODE_HEADER_BYTES
                                    + CHILD_BYTES
                                    + MIN_NODE_ENTRIES * (maxEntry + CHILD_BYTES);
                    case HASH -> BUCKET_HEADER_BYTES + maxEntry;
                };
        if (needed > pageSize) {
            throw new StorageException("column " + column.name() + " is too wide to index");
        }
    }
}
