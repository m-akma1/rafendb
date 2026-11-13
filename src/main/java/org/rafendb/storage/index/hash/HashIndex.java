package org.rafendb.storage.index.hash;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.Value;
import org.rafendb.storage.index.Index;
import org.rafendb.storage.index.IndexEntry;
import org.rafendb.storage.index.IndexKeys;
import org.rafendb.storage.index.IndexPageStore;
import org.rafendb.storage.index.KeyCodec;

/**
 * A static hash index stored in pages: a fixed number of buckets, each a chain of pages. It answers
 * equality only.
 *
 * <p>Page 0 is the header: {@code int} magic, {@code byte} kind, {@code int} bucket count. Pages 1
 * to the bucket count are the chain heads, and overflow pages follow. A bucket page is a {@code
 * short} entry count and a {@code long} next page, followed by {@code count} entries in no
 * particular order.
 *
 * <p>The bucket of a key comes from {@link #hash}, never from {@link Value#hashCode}: that mixes in
 * an enum's identity hash, which may differ between runs, while a bucket number lives on disk.
 *
 * <p>A page is encoded to a full image first and then written at once, so writing one page cannot
 * fail halfway. Only one page is pinned at a time. Adding an overflow page writes the new page
 * before linking it. Chains never shrink; a later insert reuses the free space.
 *
 * <p>Not thread-safe; see {@link Index}.
 */
public final class HashIndex implements Index {
    /** Buckets of a new index, unless a test asks for another number. */
    public static final int DEFAULT_BUCKETS = 64;

    private static final int MAGIC = 0x52494458;
    private static final byte KIND = 2;
    private static final int HEADER_PAGE = 0;
    private static final int MAGIC_OFFSET = 0;
    private static final int KIND_OFFSET = 4;
    private static final int BUCKETS_OFFSET = 5;
    private static final long NO_PAGE = -1;

    private final IndexPageStore store;
    private final Column column;
    private final int pageSize;
    private final int newBuckets;

    /**
     * Attaches to an index file without touching it, with {@link #DEFAULT_BUCKETS} if it is new.
     *
     * @param store the file's pages
     * @param column the indexed column
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.rafendb.common.error.StorageRuntimeException if the column is too wide for this
     *     page size
     */
    public HashIndex(IndexPageStore store, Column column) {
        this(store, column, DEFAULT_BUCKETS);
    }

    /**
     * Attaches to an index file without touching it.
     *
     * @param store the file's pages
     * @param column the indexed column
     * @param buckets the bucket count used by {@link #initialize}; an initialized file keeps the
     *     count in its header
     * @throws NullPointerException if {@code store} or {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code buckets} is not positive
     * @throws org.rafendb.common.error.StorageRuntimeException if the column is too wide for this
     *     page size
     */
    public HashIndex(IndexPageStore store, Column column, int buckets) {
        this.store = Objects.requireNonNull(store, "store");
        this.column = Objects.requireNonNull(column, "column");
        if (buckets <= 0) {
            throw new IllegalArgumentException("bucket count must be positive: " + buckets);
        }
        this.newBuckets = buckets;
        this.pageSize = store.pageSize();
        IndexKeys.requireIndexable(column, IndexType.HASH, pageSize);
    }

    /** {@inheritDoc} */
    @Override
    public void initialize() throws StorageInternalException {
        if (store.pageCount() != 0) {
            throw new StorageInternalException("index file is not empty");
        }
        store.append();
        byte[] empty = encode(new Bucket(new ArrayList<>(), NO_PAGE));
        for (int i = 0; i < newBuckets; i++) {
            store.writeUnlogged(store.append(), b -> b.put(empty));
        }
        // Last, so a file that stopped being formatted is rejected instead of misread.
        byte[] header = new byte[pageSize];
        ByteBuffer h = ByteBuffer.wrap(header);
        h.putInt(MAGIC_OFFSET, MAGIC);
        h.put(KIND_OFFSET, KIND);
        h.putInt(BUCKETS_OFFSET, newBuckets);
        store.writeUnlogged(HEADER_PAGE, b -> b.put(header));
    }

    /** {@inheritDoc} */
    @Override
    public boolean insert(Value key, Rid rid, long lsn) throws StorageInternalException {
        return add(
                new IndexEntry(key, rid),
                (page, image) -> store.write(page, lsn, b -> b.put(image)));
    }

    /** {@inheritDoc} */
    @Override
    public boolean insertUnlogged(Value key, Rid rid) throws StorageInternalException {
        return add(
                new IndexEntry(key, rid),
                (page, image) -> store.writeUnlogged(page, b -> b.put(image)));
    }

    /** {@inheritDoc} */
    @Override
    public boolean delete(Value key, Rid rid, long lsn) throws StorageInternalException {
        IndexEntry entry = new IndexEntry(key, rid);
        long page = bucketOf(key);
        while (page != NO_PAGE) {
            Bucket bucket = readBucket(page);
            if (bucket.entries.remove(entry)) {
                byte[] image = encode(bucket);
                store.write(page, lsn, b -> b.put(image));
                return true;
            }
            page = bucket.next;
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public List<Rid> lookup(Value key) throws StorageInternalException {
        List<Rid> found = new ArrayList<>();
        long page = bucketOf(key);
        while (page != NO_PAGE) {
            Bucket bucket = readBucket(page);
            for (IndexEntry entry : bucket.entries) {
                if (entry.key().equals(key)) {
                    found.add(entry.rid());
                }
            }
            page = bucket.next;
        }
        return List.copyOf(found);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always; hashing does not keep keys in order
     */
    @Override
    public List<Rid> range(Value lower, boolean includeLower, Value upper, boolean includeUpper) {
        throw new UnsupportedOperationException("a hash index cannot search ranges");
    }

    /**
     * Returns a key's hash, which depends only on its value, so that it is the same in every run.
     * CHAR and VARCHAR values with the same text have the same hash, and so do the two float zeros,
     * because {@link Value} treats them as equal.
     *
     * @param key a non-NULL key
     * @return the hash
     */
    static int hash(Value key) {
        int h =
                switch (key.type()) {
                    case INTEGER -> Integer.hashCode(key.asInt());
                    case FLOAT -> Float.hashCode(key.asFloat());
                    case CHAR, VARCHAR -> key.asText().hashCode();
                };
        return h ^ (h >>> 16);
    }

    /**
     * Adds an entry unless the bucket's chain has it, into the first page with room, or into a new
     * overflow page.
     *
     * @param entry the entry
     * @param writer how pages are written
     * @return {@code false} if the entry was already there; nothing is written then
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     */
    private boolean add(IndexEntry entry, PageWriter writer) throws StorageInternalException {
        int size = KeyCodec.entrySize(entry, column);
        long roomPage = NO_PAGE;
        Bucket roomBucket = null;
        long last = NO_PAGE;
        Bucket lastBucket = null;
        long page = bucketOf(entry.key());
        while (page != NO_PAGE) {
            Bucket bucket = readBucket(page);
            if (bucket.entries.contains(entry)) {
                return false;
            }
            if (roomBucket == null && encodedSize(bucket) + size <= pageSize) {
                roomPage = page;
                roomBucket = bucket;
            }
            last = page;
            lastBucket = bucket;
            page = bucket.next;
        }
        if (roomBucket != null) {
            roomBucket.entries.add(entry);
            writer.write(roomPage, encode(roomBucket));
            return true;
        }
        long overflow = store.append();
        List<IndexEntry> entries = new ArrayList<>();
        entries.add(entry);
        writer.write(overflow, encode(new Bucket(entries, NO_PAGE)));
        lastBucket.next = overflow;
        writer.write(last, encode(lastBucket));
        return true;
    }

    /**
     * Finds the head page of a key's bucket, after checking that the file is one of these indexes.
     *
     * @param key the key
     * @return the page number
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     */
    private long bucketOf(Value key) throws StorageInternalException {
        if (store.pageCount() == 0) {
            throw new StorageInternalException("index file is not initialized");
        }
        int buckets =
                store.read(
                        HEADER_PAGE,
                        b ->
                                b.getInt(MAGIC_OFFSET) == MAGIC && b.get(KIND_OFFSET) == KIND
                                        ? b.getInt(BUCKETS_OFFSET)
                                        : 0);
        if (buckets <= 0) {
            throw new StorageInternalException("not a hash index file");
        }
        return 1L + Math.floorMod(hash(key), buckets);
    }

    /**
     * Reads and decodes a page. The result does not refer to the page's bytes.
     *
     * @param page the page number
     * @return the bucket page
     * @throws StorageInternalException on an I/O failure
     */
    private Bucket readBucket(long page) throws StorageInternalException {
        return store.read(page, this::decode);
    }

    /**
     * Returns the encoded size of a bucket page.
     *
     * @param bucket the bucket page
     * @return the size in bytes
     */
    private int encodedSize(Bucket bucket) {
        int size = IndexKeys.BUCKET_HEADER_BYTES;
        for (IndexEntry entry : bucket.entries) {
            size += KeyCodec.entrySize(entry, column);
        }
        return size;
    }

    /**
     * Encodes a bucket page.
     *
     * @param bucket the bucket page
     * @return a full page image; the rest of the page is zero
     */
    private byte[] encode(Bucket bucket) {
        byte[] image = new byte[pageSize];
        ByteBuffer b = ByteBuffer.wrap(image);
        b.putShort((short) bucket.entries.size());
        b.putLong(bucket.next);
        for (IndexEntry entry : bucket.entries) {
            KeyCodec.writeEntry(b, entry, column);
        }
        return image;
    }

    /**
     * Decodes a bucket page.
     *
     * @param b the page at position zero
     * @return the bucket page
     */
    private Bucket decode(ByteBuffer b) {
        int count = Short.toUnsignedInt(b.getShort());
        long next = b.getLong();
        List<IndexEntry> entries = new ArrayList<>(count + 1);
        for (int i = 0; i < count; i++) {
            entries.add(KeyCodec.readEntry(b, column));
        }
        return new Bucket(entries, next);
    }

    /** How a page image reaches the store: logged with an LSN, or unlogged. */
    @FunctionalInterface
    private interface PageWriter {
        /**
         * Writes a full page.
         *
         * @param page the page number
         * @param image the page's new bytes
         * @throws StorageInternalException on an I/O failure
         */
        void write(long page, byte[] image) throws StorageInternalException;
    }

    /** A decoded bucket page, not tied to the page's bytes. */
    private static final class Bucket {
        final List<IndexEntry> entries;
        long next;

        /**
         * Creates a bucket page.
         *
         * @param entries the entries, kept as the page's own list
         * @param next the next page of the chain, or {@link #NO_PAGE}
         */
        Bucket(List<IndexEntry> entries, long next) {
            this.entries = entries;
            this.next = next;
        }
    }
}
