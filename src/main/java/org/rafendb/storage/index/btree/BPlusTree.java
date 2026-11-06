package org.rafendb.storage.index.btree;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
 * A B+ tree stored in pages. Entries order by key, then Rid, so every entry is unique even when
 * keys repeat, and all of them live in the leaves, which are chained in order.
 *
 * <p>Page 0 is the header: {@code int} magic, {@code byte} kind, {@code long} root page. The root
 * is read from it on every operation and never kept in a field, so a root change is persisted like
 * any other page change. A node is a {@code byte} leaf flag, a {@code short} entry count and a
 * {@code long} next leaf, followed by {@code count} entries in a leaf, or by a {@code long} first
 * child and {@code count} pairs of separator entry and right child in an internal node.
 *
 * <p>A node is decoded into memory, changed, encoded to a full page image, then written at once,
 * so writing one page cannot fail halfway. Only one page is pinned at a time.
 *
 * <p>A split writes the new right node, then the left node, then the parent. If writing stops in
 * between, the entries stay reachable through the leaf chain, but the caller must treat the index
 * as damaged and rebuild it.
 *
 * <p>Not thread-safe; see {@link Index}.
 */
public final class BPlusTree implements Index {
    private static final int MAGIC = 0x52494458;
    private static final byte KIND = 1;
    private static final int HEADER_PAGE = 0;
    private static final int MAGIC_OFFSET = 0;
    private static final int KIND_OFFSET = 4;
    private static final int ROOT_OFFSET = 5;
    private static final long NO_PAGE = -1;

    private final IndexPageStore store;
    private final Column column;
    private final int pageSize;

    /**
     * Attaches to an index file without touching it.
     *
     * @param store the file's pages
     * @param column the indexed column
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.rafendb.common.error.StorageRuntimeException if the column is too wide for this
     *     page size
     */
    public BPlusTree(IndexPageStore store, Column column) {
        this.store = Objects.requireNonNull(store, "store");
        this.column = Objects.requireNonNull(column, "column");
        this.pageSize = store.pageSize();
        IndexKeys.requireIndexable(column, IndexType.B_PLUS_TREE, pageSize);
    }

    /** {@inheritDoc} */
    @Override
    public void initialize() throws StorageInternalException {
        if (store.pageCount() != 0) {
            throw new StorageInternalException("index file is not empty");
        }
        store.append();
        long root = store.append();
        PageWriter writer = unlogged();
        writer.write(root, encode(newLeaf(new ArrayList<>(), NO_PAGE)));
        writer.write(HEADER_PAGE, encodeHeader(root));
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
        return add(new IndexEntry(key, rid), unlogged());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nodes are never merged: a leaf may become empty and stays in the chain, which searches
     * walk past. A rebuild compacts the tree.
     */
    @Override
    public boolean delete(Value key, Rid rid, long lsn) throws StorageInternalException {
        IndexEntry entry = new IndexEntry(key, rid);
        long page = findLeaf(entry);
        Node node = readNode(page);
        int position = Collections.binarySearch(node.entries, entry);
        if (position < 0) {
            return false;
        }
        node.entries.remove(position);
        byte[] image = encode(node);
        store.write(page, lsn, b -> b.put(image));
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public List<Rid> lookup(Value key) throws StorageInternalException {
        return range(key, true, key, true);
    }

    /** {@inheritDoc} */
    @Override
    public List<Rid> range(Value lower, boolean includeLower, Value upper, boolean includeUpper)
            throws StorageInternalException {
        List<Rid> found = new ArrayList<>();
        long page = lower == null ? leftmostLeaf() : findLeaf(IndexEntry.probe(lower));
        while (page != NO_PAGE) {
            Node node = readNode(page);
            for (IndexEntry entry : node.entries) {
                if (lower != null) {
                    int fromLower = IndexKeys.compare(entry.key(), lower);
                    if (fromLower < 0 || (fromLower == 0 && !includeLower)) {
                        continue;
                    }
                }
                if (upper != null) {
                    int fromUpper = IndexKeys.compare(entry.key(), upper);
                    if (fromUpper > 0 || (fromUpper == 0 && !includeUpper)) {
                        return List.copyOf(found);
                    }
                }
                found.add(entry.rid());
            }
            page = node.next;
        }
        return List.copyOf(found);
    }

    /**
     * Returns the number of levels, for tests.
     *
     * @return 1 for a tree that is one leaf
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     */
    int height() throws StorageInternalException {
        int height = 1;
        Node node = readNode(readRoot());
        while (!node.isLeaf) {
            height++;
            node = readNode(node.children.get(0));
        }
        return height;
    }

    /**
     * Adds an entry, splitting nodes upward from the leaf as long as they overflow.
     *
     * @param entry the entry
     * @param writer how pages are written
     * @return {@code false} if the entry was already there; nothing is written then
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     */
    private boolean add(IndexEntry entry, PageWriter writer) throws StorageInternalException {
        Deque<Step> path = new ArrayDeque<>();
        long page = readRoot();
        Node node = readNode(page);
        while (!node.isLeaf) {
            int child = childIndex(node, entry);
            path.push(new Step(page, node, child));
            page = node.children.get(child);
            node = readNode(page);
        }
        int position = Collections.binarySearch(node.entries, entry);
        if (position >= 0) {
            return false;
        }
        node.entries.add(-position - 1, entry);

        while (encodedSize(node) > pageSize) {
            Split split = split(node);
            long rightPage = store.append();
            if (node.isLeaf) {
                split.right().next = node.next;
                node.next = rightPage;
            }
            writer.write(rightPage, encode(split.right()));
            writer.write(page, encode(node));
            if (path.isEmpty()) {
                long newRoot = store.append();
                writer.write(newRoot, encode(newRoot(split.separator(), page, rightPage)));
                writer.write(HEADER_PAGE, encodeHeader(newRoot));
                return true;
            }
            Step parent = path.pop();
            parent.node().entries.add(parent.child(), split.separator());
            parent.node().children.add(parent.child() + 1, rightPage);
            page = parent.page();
            node = parent.node();
        }
        writer.write(page, encode(node));
        return true;
    }

    /**
     * Finds the leaf where an entry belongs.
     *
     * @param entry the entry
     * @return the leaf's page number
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     */
    private long findLeaf(IndexEntry entry) throws StorageInternalException {
        long page = readRoot();
        Node node = readNode(page);
        while (!node.isLeaf) {
            page = node.children.get(childIndex(node, entry));
            node = readNode(page);
        }
        return page;
    }

    /**
     * Finds the first leaf of the chain.
     *
     * @return the leaf's page number
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     */
    private long leftmostLeaf() throws StorageInternalException {
        long page = readRoot();
        Node node = readNode(page);
        while (!node.isLeaf) {
            page = node.children.get(0);
            node = readNode(page);
        }
        return page;
    }

    /**
     * Picks the child covering an entry: the separator on its left is at most the entry, and the
     * one on its right is greater.
     *
     * @param node an internal node
     * @param entry the entry
     * @return the child's position in {@code node.children}
     */
    private static int childIndex(Node node, IndexEntry entry) {
        int position = Collections.binarySearch(node.entries, entry);
        return position >= 0 ? position + 1 : -position - 1;
    }

    /**
     * Reads the root page number, after checking that the file is one of these trees.
     *
     * @return the root's page number
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     */
    private long readRoot() throws StorageInternalException {
        if (store.pageCount() == 0) {
            throw new StorageInternalException("index file is not initialized");
        }
        long root =
                store.read(
                        HEADER_PAGE,
                        b ->
                                b.getInt(MAGIC_OFFSET) == MAGIC && b.get(KIND_OFFSET) == KIND
                                        ? b.getLong(ROOT_OFFSET)
                                        : NO_PAGE);
        if (root == NO_PAGE) {
            throw new StorageInternalException("not a B+ tree index file");
        }
        return root;
    }

    /**
     * Reads and decodes a node. The result does not refer to the page's bytes.
     *
     * @param page the page number
     * @return the node
     * @throws StorageInternalException on an I/O failure
     */
    private Node readNode(long page) throws StorageInternalException {
        return store.read(page, this::decode);
    }

    /**
     * Returns the writer for building an index, without log records.
     *
     * @return the writer
     */
    private PageWriter unlogged() {
        return (page, image) -> store.writeUnlogged(page, b -> b.put(image));
    }

    /**
     * Encodes the header page.
     *
     * @param root the root's page number
     * @return a full page image
     */
    private byte[] encodeHeader(long root) {
        byte[] image = new byte[pageSize];
        ByteBuffer b = ByteBuffer.wrap(image);
        b.putInt(MAGIC_OFFSET, MAGIC);
        b.put(KIND_OFFSET, KIND);
        b.putLong(ROOT_OFFSET, root);
        return image;
    }

    /**
     * Returns the encoded size of a node, which may exceed a page before it is split.
     *
     * @param node the node
     * @return the size in bytes
     */
    private int encodedSize(Node node) {
        int size = IndexKeys.NODE_HEADER_BYTES + contentSize(node);
        return node.isLeaf ? size : size + IndexKeys.CHILD_BYTES;
    }

    /**
     * Returns the size of a node's entries, with their right children in an internal node.
     *
     * @param node the node
     * @return the size in bytes
     */
    private int contentSize(Node node) {
        int size = 0;
        for (IndexEntry entry : node.entries) {
            size += slotSize(node, entry);
        }
        return size;
    }

    /**
     * Returns the size of one entry in a node, with its right child in an internal node.
     *
     * @param node the node
     * @param entry the entry
     * @return the size in bytes
     */
    private int slotSize(Node node, IndexEntry entry) {
        int size = KeyCodec.entrySize(entry, column);
        return node.isLeaf ? size : size + IndexKeys.CHILD_BYTES;
    }

    /**
     * Encodes a node that fits a page.
     *
     * @param node the node
     * @return a full page image; the rest of the page is zero
     */
    private byte[] encode(Node node) {
        byte[] image = new byte[pageSize];
        ByteBuffer b = ByteBuffer.wrap(image);
        b.put((byte) (node.isLeaf ? 1 : 0));
        b.putShort((short) node.entries.size());
        b.putLong(node.next);
        if (node.isLeaf) {
            for (IndexEntry entry : node.entries) {
                KeyCodec.writeEntry(b, entry, column);
            }
        } else {
            b.putLong(node.children.get(0));
            for (int i = 0; i < node.entries.size(); i++) {
                KeyCodec.writeEntry(b, node.entries.get(i), column);
                b.putLong(node.children.get(i + 1));
            }
        }
        return image;
    }

    /**
     * Decodes a node.
     *
     * @param b the page at position zero
     * @return the node
     */
    private Node decode(ByteBuffer b) {
        boolean isLeaf = b.get() == 1;
        int count = Short.toUnsignedInt(b.getShort());
        long next = b.getLong();
        List<IndexEntry> entries = new ArrayList<>(count + 1);
        if (isLeaf) {
            for (int i = 0; i < count; i++) {
                entries.add(KeyCodec.readEntry(b, column));
            }
            return newLeaf(entries, next);
        }
        List<Long> children = new ArrayList<>(count + 2);
        children.add(b.getLong());
        for (int i = 0; i < count; i++) {
            entries.add(KeyCodec.readEntry(b, column));
            children.add(b.getLong());
        }
        return new Node(false, entries, children, NO_PAGE);
    }

    /**
     * Moves the upper part of an overflowing node into a new node. The split point is by bytes, not
     * by entry count, because entries differ in length: the left half gets the longest prefix of at
     * most half the bytes.
     *
     * <p>{@link IndexKeys#requireIndexable} guarantees that three of the widest slots fit a page,
     * which is what makes both halves non-empty and small enough to fit.
     *
     * @param node the node, which keeps the lower part
     * @return the separator for the parent and the new right node
     */
    private Split split(Node node) {
        List<IndexEntry> entries = node.entries;
        int half = contentSize(node) / 2;
        int lastLeft = node.isLeaf ? entries.size() - 1 : entries.size() - 2;
        int left = 0;
        int used = 0;
        while (left < lastLeft && used + slotSize(node, entries.get(left)) <= half) {
            used += slotSize(node, entries.get(left));
            left++;
        }
        left = Math.max(left, 1);

        if (node.isLeaf) {
            List<IndexEntry> upper = new ArrayList<>(entries.subList(left, entries.size()));
            entries.subList(left, entries.size()).clear();
            return new Split(upper.get(0), newLeaf(upper, NO_PAGE));
        }
        IndexEntry separator = entries.get(left);
        List<IndexEntry> upper = new ArrayList<>(entries.subList(left + 1, entries.size()));
        List<Long> upperChildren =
                new ArrayList<>(node.children.subList(left + 1, node.children.size()));
        entries.subList(left, entries.size()).clear();
        node.children.subList(left + 1, node.children.size()).clear();
        return new Split(separator, new Node(false, upper, upperChildren, NO_PAGE));
    }

    /**
     * Creates a leaf.
     *
     * @param entries the entries, kept as the leaf's own list
     * @param next the next leaf, or {@link #NO_PAGE}
     * @return the leaf
     */
    private static Node newLeaf(List<IndexEntry> entries, long next) {
        return new Node(true, entries, List.of(), next);
    }

    /**
     * Creates a new root over the two halves of a split.
     *
     * @param separator the entry between the halves
     * @param left the left half's page number
     * @param right the right half's page number
     * @return the node
     */
    private static Node newRoot(IndexEntry separator, long left, long right) {
        return new Node(
                false,
                new ArrayList<>(List.of(separator)),
                new ArrayList<>(List.of(left, right)),
                NO_PAGE);
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

    /**
     * One internal node on the way down to a leaf.
     *
     * @param page the node's page number
     * @param node the node
     * @param child the position of the child taken
     */
    private record Step(long page, Node node, int child) {}

    /**
     * The result of splitting a node.
     *
     * @param separator the entry the parent gets between the two halves
     * @param right the new right half
     */
    private record Split(IndexEntry separator, Node right) {}

    /** A decoded node, not tied to the page's bytes. */
    private static final class Node {
        final boolean isLeaf;
        final List<IndexEntry> entries;
        final List<Long> children;
        long next;

        /**
         * Creates a node.
         *
         * @param isLeaf whether this is a leaf
         * @param entries the entries, or separators of an internal node
         * @param children the children of an internal node, one more than {@code entries}
         * @param next the next leaf, or {@link #NO_PAGE}
         */
        Node(boolean isLeaf, List<IndexEntry> entries, List<Long> children, long next) {
            this.isLeaf = isLeaf;
            this.entries = entries;
            this.children = children;
            this.next = next;
        }
    }
}
