package org.rafendb.storage.index.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rafendb.storage.index.IndexFixtures.i;
import static org.rafendb.storage.index.IndexFixtures.intCol;
import static org.rafendb.storage.index.IndexFixtures.rid;
import static org.rafendb.storage.index.IndexFixtures.s;
import static org.rafendb.storage.index.IndexFixtures.varcharCol;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.Value;
import org.rafendb.storage.buffer.BufferManager;
import org.rafendb.storage.file.BlockId;
import org.rafendb.storage.file.BlockManager;
import org.rafendb.storage.index.IndexEntry;
import org.rafendb.storage.index.IndexPageStore;

/** Tests {@link BPlusTree} over the real block and buffer managers, with small pages. */
class BPlusTreeTest {
    private static final String FILE = "t.idx";

    @TempDir Path dir;

    private final List<Long> forced = new ArrayList<>();
    private final Deque<AutoCloseable> open = new ArrayDeque<>();
    private BlockManager blocks;
    private BufferManager buffers;

    @AfterEach
    void closeAll() throws Exception {
        while (!open.isEmpty()) {
            open.pop().close();
        }
    }

    /** Opens managers on the test directory and a store on the test file. */
    private IndexPageStore store(int pageSize, int frames) {
        blocks = new BlockManager(dir, pageSize);
        buffers = new BufferManager(blocks, forced::add, frames);
        open.push(blocks);
        open.push(buffers);
        return new IndexPageStore(FILE, blocks, buffers);
    }

    private BPlusTree newTree(int pageSize, int frames, Column column)
            throws StorageInternalException {
        IndexPageStore store = store(pageSize, frames);
        blocks.create(FILE);
        BPlusTree tree = new BPlusTree(store, column);
        tree.initialize();
        return tree;
    }

    private BPlusTree newTree() throws StorageInternalException {
        return newTree(128, 16, intCol("a"));
    }

    @Test
    void lookupMissingKeyIsEmpty() throws StorageInternalException {
        assertEquals(List.of(), newTree().lookup(i(1)));
    }

    @Test
    void insertThenLookup() throws StorageInternalException {
        BPlusTree t = newTree();
        assertTrue(t.insert(i(5), rid(0, 1), 10));
        assertEquals(List.of(rid(0, 1)), t.lookup(i(5)));
        assertEquals(List.of(), t.lookup(i(4)));
        assertEquals(List.of(), t.lookup(i(6)));
    }

    @Test
    void manyKeysSplitAndRemainFindable() throws StorageInternalException {
        BPlusTree t = newTree();
        for (int k = 0; k < 300; k++) {
            t.insert(i((k * 37) % 300), rid(k, 0), 1);
        }
        assertTrue(t.height() >= 3, "height " + t.height());
        for (int k = 0; k < 300; k++) {
            assertEquals(1, t.lookup(i(k)).size(), "key " + k);
        }
    }

    @Test
    void duplicateKeysSpanLeaves() throws StorageInternalException {
        BPlusTree t = newTree();
        for (int k = 0; k < 60; k++) {
            t.insert(i(7), rid(k, k), 1);
        }
        t.insert(i(6), rid(0, 0), 1);
        t.insert(i(8), rid(0, 0), 1);
        assertTrue(t.height() >= 2, "duplicates must span several leaves");
        assertEquals(60, t.lookup(i(7)).size());
        assertEquals(List.of(rid(0, 0)), t.lookup(i(6)));
        assertEquals(List.of(rid(0, 0)), t.lookup(i(8)));
    }

    @Test
    void sameKeyWithAnotherRidIsANewEntry() throws StorageInternalException {
        BPlusTree t = newTree();
        assertTrue(t.insert(i(1), rid(0, 0), 1));
        assertTrue(t.insert(i(1), rid(0, 1), 1));
        assertEquals(2, t.lookup(i(1)).size());
    }

    @Test
    void insertIsIdempotentAndDirtiesNothing() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        BPlusTree t = new BPlusTree(store, intCol("a"));
        t.initialize();
        assertTrue(t.insert(i(1), rid(0, 0), 1));
        store.force();
        forced.clear();

        assertFalse(t.insert(i(1), rid(0, 0), 2));

        store.force();
        assertEquals(List.of(), forced);
        assertEquals(1, t.lookup(i(1)).size());
    }

    @Test
    void varcharKeysWork() throws StorageInternalException {
        BPlusTree t = newTree(256, 16, varcharCol("v", 20));
        for (int k = 0; k < 100; k++) {
            t.insert(s("name" + k), rid(k, 0), 1);
        }
        assertEquals(List.of(rid(42, 0)), t.lookup(s("name42")));
        assertEquals(List.of(), t.lookup(s("name")));
    }

    @Test
    void constructorRejectsTooWideColumn() {
        IndexPageStore store = store(128, 4);
        assertThrows(
                StorageRuntimeException.class, () -> new BPlusTree(store, varcharCol("v", 100)));
    }

    @Test
    void widestAllowedColumnSplitsCorrectly() throws StorageInternalException {
        // 1337 bytes is the limit for 4096-byte pages, so each node holds only three such entries.
        BPlusTree t = newTree(4096, 16, varcharCol("v", 1337));
        for (int k = 0; k < 40; k++) {
            t.insert(s(String.format("%04d", k).repeat(334)), rid(k, 0), 1);
        }
        assertTrue(t.height() >= 3, "height " + t.height());
        for (int k = 0; k < 40; k++) {
            assertEquals(
                    List.of(rid(k, 0)), t.lookup(s(String.format("%04d", k).repeat(334))), "" + k);
        }
    }

    @Test
    void splitDividesByBytesNotByEntryCount() throws StorageInternalException {
        // Ten short entries then three wide ones: dividing by count leaves all three wide entries
        // and four short ones in one half, which does not fit a page.
        BPlusTree t = newTree(4096, 16, varcharCol("v", 1337));
        for (int k = 0; k < 10; k++) {
            t.insert(s("a" + k), rid(k, 0), 1);
        }
        for (int k = 0; k < 3; k++) {
            t.insert(s("b" + k + "x".repeat(1335)), rid(k, 1), 1);
        }
        for (int k = 0; k < 3; k++) {
            assertEquals(List.of(rid(k, 1)), t.lookup(s("b" + k + "x".repeat(1335))));
        }
        assertEquals(List.of(rid(4, 0)), t.lookup(s("a4")));
    }

    @Test
    void survivesReopenIncludingNewRoot() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        BPlusTree t = new BPlusTree(store, intCol("a"));
        t.initialize();
        for (int k = 0; k < 500; k++) {
            t.insert(i(k), rid(k, 0), k + 1L);
        }
        int height = t.height();
        store.force();

        // Fresh managers and a fresh tree: everything must come from the file, not from fields.
        BPlusTree reopened = new BPlusTree(store(128, 16), intCol("a"));

        assertEquals(height, reopened.height());
        assertEquals(List.of(rid(123, 0)), reopened.lookup(i(123)));
        assertEquals(List.of(rid(499, 0)), reopened.lookup(i(499)));
    }

    @Test
    void worksWhileTheFacadeHoldsATablePage() throws StorageInternalException {
        // Two frames: one taken by the table page the facade pins during maintenance, one for us.
        BPlusTree t = newTree(128, 2, intCol("a"));
        blocks.create("data.tbl");
        BlockId tablePage = blocks.append("data.tbl");
        buffers.pin(tablePage);
        try {
            for (int k = 0; k < 300; k++) {
                t.insert(i(k), rid(k, 0), 1);
            }
            assertEquals(List.of(rid(250, 0)), t.lookup(i(250)));
        } finally {
            buffers.unpin(tablePage);
        }
    }

    @Test
    void insertForcesWalThroughItsLsn() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        BPlusTree t = new BPlusTree(store, intCol("a"));
        t.initialize();
        t.insert(i(1), rid(0, 0), 42);
        store.force();
        assertEquals(List.of(42L), forced);
    }

    @Test
    void splitPagesCarryTheLsnToo() throws StorageInternalException {
        IndexPageStore store = store(128, 64);
        blocks.create(FILE);
        BPlusTree t = new BPlusTree(store, intCol("a"));
        t.initialize();
        for (int k = 0; k < 300; k++) {
            t.insertUnlogged(i(k), rid(k, 0));
        }
        store.force();
        forced.clear();
        int height = t.height();

        // Keep inserting logged entries until a split reaches the root, changing the header too.
        int k = 300;
        while (t.height() == height) {
            t.insert(i(k), rid(k, 0), 7);
            k++;
        }

        // Several pages changed, including the header; none may be flushed without the WAL.
        store.force();
        assertTrue(forced.size() >= 3, "forced " + forced);
        assertEquals(List.of(7L), forced.stream().distinct().toList());
    }

    @Test
    void unloggedInsertSkipsWal() throws StorageInternalException {
        IndexPageStore store = store(128, 4);
        blocks.create(FILE);
        BPlusTree t = new BPlusTree(store, intCol("a"));
        t.initialize();
        for (int k = 0; k < 300; k++) {
            assertTrue(t.insertUnlogged(i(k), rid(k, 0)));
        }
        assertFalse(t.insertUnlogged(i(5), rid(5, 0)));
        store.force();
        assertEquals(List.of(), forced);
        assertEquals(List.of(rid(250, 0)), t.lookup(i(250)));
    }

    @Test
    void initializeRejectsAFileWithPages() throws StorageInternalException {
        BPlusTree t = newTree();
        assertThrows(StorageInternalException.class, t::initialize);
    }

    @Test
    void uninitializedOrCorruptFileIsAStorageError() throws StorageInternalException {
        IndexPageStore store = store(128, 4);
        blocks.create(FILE);
        BPlusTree t = new BPlusTree(store, intCol("a"));
        assertThrows(StorageInternalException.class, () -> t.lookup(i(1)));
        store.append();
        assertThrows(StorageInternalException.class, () -> t.lookup(i(1)));
        assertThrows(StorageInternalException.class, () -> t.insert(i(1), rid(0, 0), 1));
    }

    private BPlusTree treeWith0to99() throws StorageInternalException {
        BPlusTree t = newTree();
        for (int k = 0; k < 100; k++) {
            t.insert(i(k), rid(k, 0), 1);
        }
        return t;
    }

    @Test
    void rangeInclusiveExclusive() throws StorageInternalException {
        BPlusTree t = treeWith0to99();
        assertEquals(11, t.range(i(10), true, i(20), true).size());
        assertEquals(9, t.range(i(10), false, i(20), false).size());
        assertEquals(10, t.range(i(10), true, i(20), false).size());
        assertEquals(10, t.range(i(10), false, i(20), true).size());
    }

    @Test
    void rangeUnboundedSides() throws StorageInternalException {
        BPlusTree t = treeWith0to99();
        assertEquals(10, t.range(null, true, i(9), true).size());
        assertEquals(10, t.range(i(90), true, null, true).size());
        assertEquals(100, t.range(null, true, null, true).size());
    }

    @Test
    void rangeEmptyCases() throws StorageInternalException {
        BPlusTree t = treeWith0to99();
        assertEquals(List.of(), t.range(i(50), true, i(40), true));
        assertEquals(List.of(), t.range(i(50), false, i(50), true));
        assertEquals(List.of(), t.range(i(200), true, null, true));
        assertEquals(List.of(), t.range(null, true, i(-1), true));
    }

    @Test
    void rangeIsSortedByKeyThenRid() throws StorageInternalException {
        BPlusTree t = newTree();
        t.insert(i(5), rid(2, 0), 1);
        t.insert(i(5), rid(1, 0), 1);
        t.insert(i(6), rid(0, 0), 1);
        assertEquals(List.of(rid(1, 0), rid(2, 0), rid(0, 0)), t.range(i(5), true, i(6), true));
    }

    @Test
    void rangeExcludingADuplicatedBoundSkipsAllOfIt() throws StorageInternalException {
        BPlusTree t = newTree();
        for (int k = 0; k < 40; k++) {
            t.insert(i(7), rid(k, 0), 1);
        }
        t.insert(i(8), rid(0, 0), 1);
        assertEquals(List.of(rid(0, 0)), t.range(i(7), false, null, true));
        assertEquals(40, t.range(null, true, i(8), false).size());
    }

    @Test
    void resultsAreUnmodifiable() throws StorageInternalException {
        BPlusTree t = treeWith0to99();
        assertThrows(UnsupportedOperationException.class, () -> t.lookup(i(1)).add(rid(0, 0)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> t.range(null, true, null, true).add(rid(0, 0)));
    }

    @Test
    void deleteRemovesOnlyThatRid() throws StorageInternalException {
        BPlusTree t = newTree();
        t.insert(i(1), rid(0, 0), 1);
        t.insert(i(1), rid(0, 1), 1);
        assertTrue(t.delete(i(1), rid(0, 0), 2));
        assertEquals(List.of(rid(0, 1)), t.lookup(i(1)));
    }

    @Test
    void deleteIsIdempotentAndDirtiesNothing() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        BPlusTree t = new BPlusTree(store, intCol("a"));
        t.initialize();
        t.insert(i(1), rid(0, 0), 1);
        assertTrue(t.delete(i(1), rid(0, 0), 2));
        store.force();
        forced.clear();

        assertFalse(t.delete(i(1), rid(0, 0), 3));
        assertFalse(t.delete(i(9), rid(0, 0), 3));

        store.force();
        assertEquals(List.of(), forced);
    }

    @Test
    void deleteCarriesItsLsn() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        BPlusTree t = new BPlusTree(store, intCol("a"));
        t.initialize();
        t.insertUnlogged(i(1), rid(0, 0));
        store.force();
        assertTrue(t.delete(i(1), rid(0, 0), 77));
        store.force();
        assertEquals(List.of(77L), forced);
    }

    @Test
    void deleteEverythingThenReuse() throws StorageInternalException {
        BPlusTree t = treeWith0to99();
        for (int k = 0; k < 100; k++) {
            assertTrue(t.delete(i(k), rid(k, 0), 2));
        }
        assertEquals(List.of(), t.range(null, true, null, true));
        assertEquals(List.of(), t.lookup(i(50)));
        t.insert(i(50), rid(1, 1), 3);
        assertEquals(List.of(rid(1, 1)), t.lookup(i(50)));
    }

    @Test
    void searchWalksPastEmptyLeaves() throws StorageInternalException {
        BPlusTree t = treeWith0to99();
        for (int k = 20; k < 60; k++) {
            t.delete(i(k), rid(k, 0), 2);
        }
        List<Rid> expected = new ArrayList<>();
        for (int k = 60; k < 70; k++) {
            expected.add(rid(k, 0));
        }
        assertEquals(expected, t.range(i(30), true, i(69), true));
    }

    @Test
    void matchesAModelUnderRandomIntegerOperations() throws StorageInternalException {
        BPlusTree t = newTree();
        checkAgainstModel(t, 42, 40, r -> i(r.nextInt(200)));
    }

    @Test
    void matchesAModelUnderRandomVariableLengthKeys() throws StorageInternalException {
        BPlusTree t = newTree(256, 8, varcharCol("v", 40));
        checkAgainstModel(t, 7, 40, r -> s("k".repeat(r.nextInt(39)) + r.nextInt(9)));
    }

    /** Applies random inserts and deletes to the tree and a sorted set, comparing all results. */
    private static void checkAgainstModel(
            BPlusTree tree, long seed, int rounds, Function<Random, Value> keys)
            throws StorageInternalException {
        Random random = new Random(seed);
        TreeSet<IndexEntry> model = new TreeSet<>();
        for (int round = 0; round < rounds; round++) {
            for (int op = 0; op < 100; op++) {
                Value key = keys.apply(random);
                Rid rid = rid(random.nextInt(4), random.nextInt(4));
                IndexEntry entry = new IndexEntry(key, rid);
                if (random.nextInt(3) == 0) {
                    assertEquals(model.remove(entry), tree.delete(key, rid, op + 1L));
                } else {
                    assertEquals(model.add(entry), tree.insert(key, rid, op + 1L));
                }
            }
            assertEquals(
                    model.stream().map(IndexEntry::rid).toList(),
                    tree.range(null, true, null, true));
            Value a = keys.apply(random);
            Value b = keys.apply(random);
            Value low = a.compareTo(b) <= 0 ? a : b;
            Value high = a.compareTo(b) <= 0 ? b : a;
            boolean includeLow = random.nextBoolean();
            boolean includeHigh = random.nextBoolean();
            assertEquals(
                    model.stream()
                            .filter(
                                    e -> {
                                        int fromLow = e.key().compareTo(low);
                                        int fromHigh = e.key().compareTo(high);
                                        return (fromLow > 0 || (fromLow == 0 && includeLow))
                                                && (fromHigh < 0 || (fromHigh == 0 && includeHigh));
                                    })
                            .map(IndexEntry::rid)
                            .toList(),
                    tree.range(low, includeLow, high, includeHigh));
            assertEquals(
                    model.stream().filter(e -> e.key().equals(low)).map(IndexEntry::rid).toList(),
                    tree.lookup(low));
        }
    }
}
