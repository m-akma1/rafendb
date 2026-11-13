package org.rafendb.storage.index.hash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rafendb.storage.index.IndexFixtures.ch;
import static org.rafendb.storage.index.IndexFixtures.charCol;
import static org.rafendb.storage.index.IndexFixtures.f;
import static org.rafendb.storage.index.IndexFixtures.floatCol;
import static org.rafendb.storage.index.IndexFixtures.i;
import static org.rafendb.storage.index.IndexFixtures.intCol;
import static org.rafendb.storage.index.IndexFixtures.rid;
import static org.rafendb.storage.index.IndexFixtures.s;
import static org.rafendb.storage.index.IndexFixtures.varcharCol;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
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
import org.rafendb.storage.index.btree.BPlusTree;

/** Tests {@link HashIndex} over the real block and buffer managers, with small pages. */
class HashIndexTest {
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

    private HashIndex newIndex(int pageSize, int frames, Column column, int buckets)
            throws StorageInternalException {
        IndexPageStore store = store(pageSize, frames);
        blocks.create(FILE);
        HashIndex index = new HashIndex(store, column, buckets);
        index.initialize();
        return index;
    }

    private HashIndex newIndex() throws StorageInternalException {
        return newIndex(128, 16, intCol("a"), 4);
    }

    private static Set<Rid> set(List<Rid> rids) {
        Set<Rid> unique = new HashSet<>(rids);
        assertEquals(rids.size(), unique.size(), "duplicate Rids in " + rids);
        return unique;
    }

    @Test
    void lookupMissingKeyIsEmpty() throws StorageInternalException {
        assertEquals(List.of(), newIndex().lookup(i(1)));
    }

    @Test
    void insertThenLookup() throws StorageInternalException {
        HashIndex h = newIndex();
        assertTrue(h.insert(i(5), rid(0, 1), 10));
        assertEquals(List.of(rid(0, 1)), h.lookup(i(5)));
        assertEquals(List.of(), h.lookup(i(6)));
    }

    @Test
    void duplicatesFillOverflowPages() throws StorageInternalException {
        HashIndex h = newIndex();
        long pagesBefore = blocks.size(FILE);
        Set<Rid> expected = new HashSet<>();
        for (int k = 0; k < 60; k++) {
            h.insert(i(7), rid(k, k), 1);
            expected.add(rid(k, k));
        }
        assertTrue(blocks.size(FILE) > pagesBefore, "60 entries need overflow pages");
        assertEquals(expected, set(h.lookup(i(7))));
    }

    @Test
    void differentKeysInOneBucketDoNotMix() throws StorageInternalException {
        HashIndex h = newIndex(128, 16, intCol("a"), 1);
        for (int k = 0; k < 40; k++) {
            h.insert(i(k), rid(k, 0), 1);
        }
        for (int k = 0; k < 40; k++) {
            assertEquals(List.of(rid(k, 0)), h.lookup(i(k)), "key " + k);
        }
    }

    @Test
    void insertIsIdempotentAndDirtiesNothing() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        HashIndex h = new HashIndex(store, intCol("a"), 4);
        h.initialize();
        for (int k = 0; k < 30; k++) {
            assertTrue(h.insert(i(7), rid(k, 0), 1));
        }
        store.force();
        forced.clear();

        // Also when the entry sits in an overflow page, and an earlier page has room.
        assertFalse(h.insert(i(7), rid(29, 0), 2));
        assertTrue(h.delete(i(7), rid(0, 0), 3));
        store.force();
        forced.clear();
        assertFalse(h.insert(i(7), rid(29, 0), 4));

        store.force();
        assertEquals(List.of(), forced);
        assertEquals(29, h.lookup(i(7)).size());
    }

    @Test
    void deleteRemovesOnlyThatRid() throws StorageInternalException {
        HashIndex h = newIndex();
        h.insert(i(1), rid(0, 0), 1);
        h.insert(i(1), rid(0, 1), 1);
        assertTrue(h.delete(i(1), rid(0, 0), 2));
        assertEquals(List.of(rid(0, 1)), h.lookup(i(1)));
    }

    @Test
    void deleteIsIdempotentAndDirtiesNothing() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        HashIndex h = new HashIndex(store, intCol("a"), 4);
        h.initialize();
        h.insert(i(1), rid(0, 0), 1);
        assertTrue(h.delete(i(1), rid(0, 0), 2));
        store.force();
        forced.clear();

        assertFalse(h.delete(i(1), rid(0, 0), 3));
        assertFalse(h.delete(i(9), rid(0, 0), 3));

        store.force();
        assertEquals(List.of(), forced);
    }

    @Test
    void deleteFindsEntriesInOverflowPages() throws StorageInternalException {
        HashIndex h = newIndex();
        for (int k = 0; k < 60; k++) {
            h.insert(i(7), rid(k, 0), 1);
        }
        assertTrue(h.delete(i(7), rid(59, 0), 2));
        assertEquals(59, h.lookup(i(7)).size());
        assertFalse(h.lookup(i(7)).contains(rid(59, 0)));
    }

    @Test
    void freedSpaceIsReused() throws StorageInternalException {
        HashIndex h = newIndex();
        for (int k = 0; k < 60; k++) {
            h.insert(i(7), rid(k, 0), 1);
        }
        long pages = blocks.size(FILE);
        for (int k = 0; k < 60; k++) {
            assertTrue(h.delete(i(7), rid(k, 0), 2));
        }
        assertEquals(List.of(), h.lookup(i(7)));
        for (int k = 0; k < 60; k++) {
            h.insert(i(7), rid(k, 1), 3);
        }
        assertEquals(pages, blocks.size(FILE));
        assertEquals(60, h.lookup(i(7)).size());
    }

    @Test
    void equalValuesShareABucket() throws StorageInternalException {
        HashIndex floats = newIndex(128, 16, floatCol("x"), 16);
        floats.insert(f(-0.0f), rid(0, 0), 1);
        assertEquals(List.of(rid(0, 0)), floats.lookup(f(0.0f)));
    }

    @Test
    void charAndVarcharOfTheSameTextAreOneKey() throws StorageInternalException {
        HashIndex h = newIndex(256, 16, charCol("c", 10), 16);
        h.insert(ch("abc"), rid(0, 0), 1);
        assertEquals(List.of(rid(0, 0)), h.lookup(s("abc")));
        assertFalse(h.insert(s("abc"), rid(0, 0), 2));
        assertEquals(List.of(), h.lookup(ch("abc ")));
    }

    @Test
    void hashDoesNotDependOnTheRun() {
        // Fixed values: a bucket number on disk must mean the same in every run.
        assertEquals(42, HashIndex.hash(i(42)));
        assertEquals(96354 ^ (96354 >>> 16), HashIndex.hash(s("abc")));
        assertEquals(HashIndex.hash(s("abc")), HashIndex.hash(ch("abc")));
        assertEquals(HashIndex.hash(f(0.0f)), HashIndex.hash(f(-0.0f)));
    }

    @Test
    void aKeyLivesInTheBucketItsStableHashSays() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        HashIndex h = new HashIndex(store, intCol("a"), 8);
        h.initialize();
        // Hash 42, so bucket 42 mod 8 = 2, the third head page after the header.
        h.insert(i(42), rid(0, 0), 1);
        int entries = store.read(3, b -> (int) b.getShort(0));
        assertEquals(1, entries);
    }

    @Test
    void aTextKeyLivesInTheBucketItsStableHashSays() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        HashIndex h = new HashIndex(store, varcharCol("v", 8), 8);
        h.initialize();
        // "abc".hashCode() is 96354; with its high bits mixed in, 96355 mod 8 = 3.
        h.insert(s("abc"), rid(0, 0), 1);
        int entries = store.read(4, b -> (int) b.getShort(0));
        assertEquals(1, entries);
    }

    @Test
    void negativeHashesFindABucket() throws StorageInternalException {
        HashIndex h = newIndex();
        h.insert(i(-7), rid(0, 0), 1);
        h.insert(i(Integer.MIN_VALUE), rid(0, 1), 1);
        assertEquals(List.of(rid(0, 0)), h.lookup(i(-7)));
        assertEquals(List.of(rid(0, 1)), h.lookup(i(Integer.MIN_VALUE)));
    }

    @Test
    void acceptsTheWidestTextColumn() throws StorageInternalException {
        HashIndex h = newIndex(4096, 16, varcharCol("v", Column.MAX_TEXT_LENGTH), 4);
        String wide = "x".repeat(Column.MAX_TEXT_LENGTH);
        // One such entry fills a page, so each goes to its own overflow page.
        for (int k = 0; k < 3; k++) {
            assertTrue(h.insert(s(wide), rid(k, 0), 1));
        }
        assertEquals(3, h.lookup(s(wide)).size());
    }

    @Test
    void rejectsAColumnWiderThanAPage() {
        IndexPageStore store = store(128, 4);
        assertThrows(
                StorageRuntimeException.class, () -> new HashIndex(store, varcharCol("v", 200)));
    }

    @Test
    void rejectsANonPositiveBucketCount() {
        IndexPageStore store = store(128, 4);
        assertThrows(IllegalArgumentException.class, () -> new HashIndex(store, intCol("a"), 0));
    }

    @Test
    void rangeIsUnsupported() throws StorageInternalException {
        HashIndex h = newIndex();
        assertThrows(UnsupportedOperationException.class, () -> h.range(null, true, null, true));
    }

    @Test
    void resultsAreUnmodifiable() throws StorageInternalException {
        HashIndex h = newIndex();
        assertThrows(UnsupportedOperationException.class, () -> h.lookup(i(1)).add(rid(0, 0)));
    }

    @Test
    void survivesReopenAndKeepsItsBucketCount() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        HashIndex h = new HashIndex(store, intCol("a"), 7);
        h.initialize();
        for (int k = 0; k < 200; k++) {
            h.insert(i(k), rid(k, 0), k + 1L);
        }
        store.force();

        // The new object asks for another count, but the file's header decides.
        HashIndex reopened = new HashIndex(store(128, 16), intCol("a"), 64);

        for (int k = 0; k < 200; k++) {
            assertEquals(List.of(rid(k, 0)), reopened.lookup(i(k)), "key " + k);
        }
    }

    @Test
    void worksWhileTheFacadeHoldsATablePage() throws StorageInternalException {
        HashIndex h = newIndex(128, 2, intCol("a"), 4);
        blocks.create("data.tbl");
        BlockId tablePage = blocks.append("data.tbl");
        buffers.pin(tablePage);
        try {
            for (int k = 0; k < 100; k++) {
                h.insert(i(k), rid(k, 0), 1);
            }
            assertEquals(List.of(rid(50, 0)), h.lookup(i(50)));
        } finally {
            buffers.unpin(tablePage);
        }
    }

    @Test
    void writesCarryTheirLsn() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        HashIndex h = new HashIndex(store, intCol("a"), 4);
        h.initialize();
        store.force();
        assertEquals(List.of(), forced);

        h.insert(i(1), rid(0, 0), 42);
        store.force();
        assertEquals(List.of(42L), forced);

        forced.clear();
        h.delete(i(1), rid(0, 0), 77);
        store.force();
        assertEquals(List.of(77L), forced);
    }

    @Test
    void overflowLinksCarryTheLsnToo() throws StorageInternalException {
        IndexPageStore store = store(128, 16);
        blocks.create(FILE);
        HashIndex h = new HashIndex(store, intCol("a"), 1);
        h.initialize();
        store.force();
        int k = 0;
        long pages = blocks.size(FILE);
        while (blocks.size(FILE) == pages) {
            h.insertUnlogged(i(k), rid(k, 0));
            k++;
        }
        store.force();
        forced.clear();
        pages = blocks.size(FILE);

        while (blocks.size(FILE) == pages) {
            h.insert(i(k), rid(k, 0), 9);
            k++;
        }
        store.force();

        // The new page and the page linking to it both changed.
        assertEquals(List.of(9L, 9L), forced);
    }

    @Test
    void unloggedInsertSkipsWal() throws StorageInternalException {
        IndexPageStore store = store(128, 4);
        blocks.create(FILE);
        HashIndex h = new HashIndex(store, intCol("a"), 4);
        h.initialize();
        for (int k = 0; k < 100; k++) {
            assertTrue(h.insertUnlogged(i(k), rid(k, 0)));
        }
        assertFalse(h.insertUnlogged(i(5), rid(5, 0)));
        store.force();
        assertEquals(List.of(), forced);
        assertEquals(List.of(rid(50, 0)), h.lookup(i(50)));
    }

    @Test
    void initializeRejectsAFileWithPages() throws StorageInternalException {
        HashIndex h = newIndex();
        assertThrows(StorageInternalException.class, h::initialize);
    }

    @Test
    void uninitializedOrCorruptFileIsAStorageError() throws StorageInternalException {
        IndexPageStore store = store(128, 4);
        blocks.create(FILE);
        HashIndex h = new HashIndex(store, intCol("a"), 4);
        assertThrows(StorageInternalException.class, () -> h.lookup(i(1)));
        store.append();
        assertThrows(StorageInternalException.class, () -> h.lookup(i(1)));
        assertThrows(StorageInternalException.class, () -> h.insert(i(1), rid(0, 0), 1));
    }

    @Test
    void aTreeFileAndAHashFileRejectEachOther() throws StorageInternalException {
        IndexPageStore store = store(128, 8);
        blocks.create(FILE);
        new BPlusTree(store, intCol("a")).initialize();
        assertThrows(
                StorageInternalException.class,
                () -> new HashIndex(store, intCol("a")).lookup(i(1)));

        blocks.create("h.idx");
        IndexPageStore hashStore = new IndexPageStore("h.idx", blocks, buffers);
        new HashIndex(hashStore, intCol("a"), 2).initialize();
        assertThrows(
                StorageInternalException.class,
                () -> new BPlusTree(hashStore, intCol("a")).lookup(i(1)));
    }

    @Test
    void matchesAModelUnderRandomOperations() throws StorageInternalException {
        checkAgainstModel(newIndex(128, 8, intCol("a"), 3), 42, true);
    }

    @Test
    void matchesAModelUnderRandomVariableLengthKeys() throws StorageInternalException {
        checkAgainstModel(newIndex(256, 8, varcharCol("v", 40), 3), 7, false);
    }

    /** Applies random inserts and deletes to the index and a set, comparing all results. */
    private static void checkAgainstModel(HashIndex index, long seed, boolean numeric)
            throws StorageInternalException {
        Random random = new Random(seed);
        Set<IndexEntry> model = new HashSet<>();
        List<Value> keys = new ArrayList<>();
        for (int k = 0; k < 60; k++) {
            keys.add(numeric ? i(k * 31 - 500) : s("k".repeat(random.nextInt(38)) + k % 10));
        }
        for (int op = 0; op < 4000; op++) {
            Value key = keys.get(random.nextInt(keys.size()));
            Rid rid = rid(random.nextInt(4), random.nextInt(4));
            IndexEntry entry = new IndexEntry(key, rid);
            if (random.nextInt(3) == 0) {
                assertEquals(model.remove(entry), index.delete(key, rid, op + 1L));
            } else {
                assertEquals(model.add(entry), index.insert(key, rid, op + 1L));
            }
            if (op % 200 == 0) {
                for (Value k : keys) {
                    Set<Rid> expected = new HashSet<>();
                    for (IndexEntry e : model) {
                        if (e.key().equals(k)) {
                            expected.add(e.rid());
                        }
                    }
                    assertEquals(expected, set(index.lookup(k)), "key " + k);
                }
            }
        }
    }
}
