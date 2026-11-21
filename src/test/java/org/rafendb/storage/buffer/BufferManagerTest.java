package org.rafendb.storage.buffer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.common.error.RecoveryInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.storage.file.BlockId;
import org.rafendb.storage.file.BlockManager;

/** Buffer behavior against fake disk I/O and WAL sharing an ordered event log, like QP's fakes. */
class BufferManagerTest {
    private static final BlockId FIRST = new BlockId("table.tbl", 0);
    private static final BlockId SECOND = new BlockId("table.tbl", 1);
    @TempDir Path directory;
    private final List<String> events = new ArrayList<>();
    private final RecoveryInternalException walFailure =
            new RecoveryInternalException("WAL sync failed");
    private FakeBlocks blocks;
    private BufferManager buffers;
    private boolean failWal;

    private final class FakeBlocks extends BlockManager {
        final Map<BlockId, byte[]> pages = new HashMap<>();
        boolean failWrite;
        boolean failForce;

        FakeBlocks() {
            super(BufferManagerTest.this.directory, 128);
        }

        @Override
        public ByteBuffer read(BlockId block) {
            events.add("read " + block.number());
            return ByteBuffer.wrap(pages.getOrDefault(block, new byte[128]).clone());
        }

        @Override
        public void write(BlockId block, ByteBuffer contents) throws StorageInternalException {
            events.add("write " + block.number());
            if (failWrite) throw new StorageInternalException("disk write failed");
            byte[] image = new byte[128];
            contents.duplicate().clear().get(image);
            pages.put(block, image);
        }

        @Override
        public void force(String fileName) throws StorageInternalException {
            events.add("force " + fileName);
            if (failForce) throw new StorageInternalException("disk sync failed");
        }
    }

    @BeforeEach
    void open() {
        blocks = new FakeBlocks();
        buffers =
                new BufferManager(
                        blocks,
                        lsn -> {
                            events.add("wal " + lsn);
                            if (failWal) throw walFailure;
                        },
                        1);
    }

    @AfterEach
    void close() throws StorageInternalException {
        failWal = false;
        blocks.failWrite = false;
        blocks.failForce = false;
        buffers.close();
    }

    private void dirtyFirst() throws StorageInternalException {
        try {
            buffers.pin(FIRST).putInt(0, 42);
            buffers.markDirty(FIRST, 10);
        } finally {
            buffers.unpin(FIRST);
        }
        events.clear();
    }

    @Test
    void cachedPageIsReusedWithoutAnotherDiskRead() throws Exception {
        buffers.pin(FIRST);
        buffers.unpin(FIRST);
        buffers.pin(FIRST);
        buffers.unpin(FIRST);
        assertEquals(List.of("read 0"), events);
    }

    @Test
    void flushForcesTheHighestWalLsnBeforeWritingAndSyncingThePage() throws Exception {
        dirtyFirst();
        buffers.pin(FIRST);
        buffers.markDirty(FIRST, 7);
        buffers.markUnloggedDirty(FIRST);
        buffers.unpin(FIRST);

        buffers.flushPage(FIRST);

        assertEquals(List.of("wal 10", "write 0", "force table.tbl"), events);
        assertEquals(42, ByteBuffer.wrap(blocks.pages.get(FIRST)).getInt());
        events.clear();
        buffers.flushPage(FIRST);
        assertTrue(events.isEmpty(), "A clean page must not be written again");
    }

    @Test
    void dirtyEvictionPersistsWalAndDataBeforeLoadingTheReplacement() throws Exception {
        dirtyFirst();

        buffers.pin(SECOND);
        buffers.unpin(SECOND);

        assertEquals(List.of("wal 10", "write 0", "force table.tbl", "read 1"), events);
        assertEquals(42, ByteBuffer.wrap(blocks.pages.get(FIRST)).getInt());
    }

    @Test
    void failedWalSyncPreventsEvictionAndKeepsTheOriginalPageForRetry() throws Exception {
        dirtyFirst();
        failWal = true;

        var error = assertThrows(StorageInternalException.class, () -> buffers.pin(SECOND));

        assertSame(walFailure, error.getCause());
        assertEquals(List.of("wal 10"), events);
        assertTrue(blocks.pages.isEmpty());
        assertEquals(42, buffers.pin(FIRST).getInt(0));
        buffers.unpin(FIRST);
        failWal = false;
        events.clear();
        buffers.pin(SECOND);
        buffers.unpin(SECOND);
        assertEquals(List.of("wal 10", "write 0", "force table.tbl", "read 1"), events);
    }

    @Test
    void failedDiskWriteKeepsThePageDirtyAndDoesNotSyncTheFile() throws Exception {
        dirtyFirst();
        blocks.failWrite = true;

        assertThrows(StorageInternalException.class, () -> buffers.flushPage(FIRST));

        assertEquals(List.of("wal 10", "write 0"), events);
        assertTrue(blocks.pages.isEmpty());
        blocks.failWrite = false;
        events.clear();
        buffers.flushPage(FIRST);
        assertEquals(List.of("wal 10", "write 0", "force table.tbl"), events);
        assertEquals(42, ByteBuffer.wrap(blocks.pages.get(FIRST)).getInt());
    }

    @Test
    void failedDiskSyncKeepsThePageDirtyForAnotherFlush() throws Exception {
        dirtyFirst();
        blocks.failForce = true;
        assertThrows(StorageInternalException.class, () -> buffers.flushPage(FIRST));
        assertEquals(List.of("wal 10", "write 0", "force table.tbl"), events);

        blocks.failForce = false;
        events.clear();
        buffers.flushPage(FIRST);
        assertEquals(List.of("wal 10", "write 0", "force table.tbl"), events);
    }

    @Test
    void aPinnedPageCannotBeEvictedAndTheFailedRequestDoesNotLeakAPin() throws Exception {
        buffers.pin(FIRST);
        try {
            assertThrows(StorageInternalException.class, () -> buffers.pin(SECOND));
            assertEquals(List.of("read 0"), events);
        } finally {
            buffers.unpin(FIRST);
        }

        buffers.pin(SECOND);
        buffers.unpin(SECOND);
        assertEquals(List.of("read 0", "read 1"), events);
    }

    @Test
    void unloggedPageStillWritesAndSyncsWithoutInventingAWalLsn() throws Exception {
        buffers.pin(FIRST).putInt(0, 42);
        buffers.markUnloggedDirty(FIRST);
        buffers.unpin(FIRST);
        events.clear();

        buffers.flushAll();

        assertEquals(List.of("write 0", "force table.tbl"), events);
        assertEquals(42, ByteBuffer.wrap(blocks.pages.get(FIRST)).getInt());
    }

    @Test
    void flushingWhileHoldingAPinIsRejectedInsteadOfDeadlocking() throws Exception {
        buffers.pin(FIRST);
        try {
            assertThrows(StorageInternalException.class, buffers::flushAll);
        } finally {
            buffers.unpin(FIRST);
        }
        assertDoesNotThrow(buffers::flushAll);
    }

    @Test
    void failedCloseRejectsNewWorkButCanRetryItsFinalFlush() throws Exception {
        dirtyFirst();
        blocks.failForce = true;
        assertThrows(StorageInternalException.class, buffers::close);
        assertThrows(IllegalStateException.class, () -> buffers.pin(FIRST));

        blocks.failForce = false;
        events.clear();
        buffers.close();
        assertEquals(List.of("wal 10", "write 0", "force table.tbl"), events);
        events.clear();
        buffers.close();
        assertTrue(events.isEmpty());
    }
}
