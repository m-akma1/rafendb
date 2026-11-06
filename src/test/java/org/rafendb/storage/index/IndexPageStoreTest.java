package org.rafendb.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ReadOnlyBufferException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.storage.buffer.BufferManager;
import org.rafendb.storage.file.BlockManager;

/** Tests {@link IndexPageStore} over the real block and buffer managers. */
class IndexPageStoreTest {
    private static final String FILE = "t.idx";
    private static final int PAGE_SIZE = 64;

    @TempDir Path dir;

    private final List<Long> forced = new ArrayList<>();
    private BlockManager blocks;
    private BufferManager buffers;
    private IndexPageStore store;

    @BeforeEach
    void open() throws StorageInternalException {
        blocks = new BlockManager(dir, PAGE_SIZE);
        blocks.create(FILE);
        buffers = new BufferManager(blocks, forced::add, 4);
        store = new IndexPageStore(FILE, blocks, buffers);
    }

    @AfterEach
    void close() throws StorageInternalException {
        buffers.close();
        blocks.close();
    }

    private static int intAt(IndexPageStore s, long page, int offset)
            throws StorageInternalException {
        return s.read(page, b -> b.getInt(offset));
    }

    @Test
    void appendNumbersFromZeroAndZeroFills() throws StorageInternalException {
        assertEquals(0, store.pageCount());
        assertEquals(0, store.append());
        assertEquals(1, store.append());
        assertEquals(2, store.pageCount());
        assertEquals(PAGE_SIZE, store.pageSize());
        assertEquals(0, intAt(store, 1, 8));
    }

    @Test
    void writeIsVisibleToRead() throws StorageInternalException {
        store.append();
        store.write(0, 5, b -> b.putInt(0, 99));
        assertEquals(99, intAt(store, 0, 0));
    }

    @Test
    void buffersStartAtPositionZero() throws StorageInternalException {
        store.append();
        store.write(0, 5, b -> b.putInt(7));
        store.write(0, 5, b -> assertEquals(0, b.position()));
        int first = store.read(0, b -> b.getInt());
        assertEquals(7, first);
    }

    @Test
    void readerCannotModifyPage() throws StorageInternalException {
        store.append();
        assertThrows(ReadOnlyBufferException.class, () -> store.read(0, b -> b.putInt(0, 1)));
    }

    @Test
    void pinIsReleasedEvenWhenReaderOrWriterThrows() throws StorageInternalException {
        try (BufferManager single = new BufferManager(blocks, forced::add, 1)) {
            IndexPageStore s = new IndexPageStore(FILE, blocks, single);
            s.append();
            s.append();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            s.read(
                                    0,
                                    b -> {
                                        throw new IllegalStateException("boom");
                                    }));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            s.write(
                                    0,
                                    1,
                                    b -> {
                                        throw new IllegalStateException("boom");
                                    }));
            // The only frame must be free again, so another page can take it.
            assertEquals(0, intAt(s, 1, 0));
        }
    }

    @Test
    void forceFlushesThroughHighestLsn() throws StorageInternalException {
        store.append();
        store.write(0, 7, b -> b.putInt(0, 1));
        store.write(0, 3, b -> b.putInt(0, 2));
        store.force();
        assertEquals(List.of(7L), forced);
    }

    @Test
    void unloggedWriteSkipsWal() throws StorageInternalException {
        store.append();
        store.writeUnlogged(0, b -> b.putInt(0, 1));
        store.force();
        assertEquals(List.of(), forced);
    }

    @Test
    void unloggedWriteKeepsEarlierLsn() throws StorageInternalException {
        store.append();
        store.write(0, 9, b -> b.putInt(0, 1));
        store.writeUnlogged(0, b -> b.putInt(4, 2));
        store.force();
        assertEquals(List.of(9L), forced);
    }

    @Test
    void contentsSurviveReopen() throws StorageInternalException {
        store.append();
        store.append();
        store.write(0, 1, b -> b.putInt(0, 11));
        store.writeUnlogged(1, b -> b.putInt(0, 22));
        store.force();
        // Reads the file directly: force must have reached the disk without any buffer close.
        try (BlockManager fresh = new BlockManager(dir, PAGE_SIZE);
                BufferManager freshBuffers = new BufferManager(fresh, lsn -> {}, 4)) {
            IndexPageStore reopened = new IndexPageStore(FILE, fresh, freshBuffers);
            assertEquals(2, reopened.pageCount());
            assertEquals(11, intAt(reopened, 0, 0));
            assertEquals(22, intAt(reopened, 1, 0));
        }
    }

    @Test
    void missingPageIsAStorageError() {
        assertThrows(StorageInternalException.class, () -> store.read(5, b -> b.getInt(0)));
    }
}
