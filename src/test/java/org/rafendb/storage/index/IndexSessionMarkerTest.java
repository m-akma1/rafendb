package org.rafendb.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.storage.file.BlockId;
import org.rafendb.storage.file.BlockManager;

/** Tests {@link IndexSessionMarker} on real files. */
class IndexSessionMarkerTest {
    private static final int BLOCK_SIZE = 128;

    @TempDir Path dir;

    private BlockManager blocks;
    private IndexSessionMarker marker;

    @BeforeEach
    void open() {
        blocks = new BlockManager(dir, BLOCK_SIZE);
        marker = new IndexSessionMarker(blocks);
    }

    @AfterEach
    void close() throws StorageInternalException {
        blocks.close();
    }

    @Test
    void missingMarkerIsNotClean() throws StorageInternalException {
        assertFalse(marker.isClean());
        assertFalse(Files.exists(dir.resolve(IndexSessionMarker.FILE)), "reading creates nothing");
    }

    @Test
    void writtenStateIsReadBack() throws StorageInternalException {
        marker.write(false);
        assertFalse(marker.isClean());
        marker.write(true);
        assertTrue(marker.isClean());
        marker.write(true);
        assertTrue(marker.isClean());
        marker.write(false);
        assertFalse(marker.isClean());
    }

    @Test
    void stateSurvivesReopen() throws StorageInternalException {
        marker.write(true);
        try (BlockManager fresh = new BlockManager(dir, BLOCK_SIZE)) {
            assertTrue(new IndexSessionMarker(fresh).isClean());
        }
    }

    @Test
    void everyWriteIsForcedAfterItHappens() throws StorageInternalException {
        List<String> calls = new ArrayList<>();
        try (BlockManager recording =
                new BlockManager(dir, BLOCK_SIZE) {
                    @Override
                    public synchronized void write(BlockId block, ByteBuffer contents)
                            throws StorageInternalException {
                        calls.add("write");
                        super.write(block, contents);
                    }

                    @Override
                    public synchronized void force(String fileName)
                            throws StorageInternalException {
                        calls.add("force");
                        super.force(fileName);
                    }
                }) {
            IndexSessionMarker m = new IndexSessionMarker(recording);
            m.write(false);
            m.write(true);
        }
        assertEquals(List.of("write", "force", "write", "force"), calls);
    }

    @Test
    void worksBeforeTheDirectoryExists() throws StorageInternalException {
        Path nested = dir.resolve("not").resolve("yet");
        try (BlockManager fresh = new BlockManager(nested, BLOCK_SIZE)) {
            IndexSessionMarker m = new IndexSessionMarker(fresh);
            assertFalse(m.isClean());
            m.write(true);
            assertTrue(m.isClean());
        }
    }

    @Test
    void emptyFileIsNotCleanAndCanBeWritten() throws StorageInternalException {
        blocks.create(IndexSessionMarker.FILE);
        assertFalse(marker.isClean());
        marker.write(true);
        assertTrue(marker.isClean());
    }

    @Test
    void blockAllocatedButNeverWrittenIsNotClean() throws StorageInternalException {
        blocks.create(IndexSessionMarker.FILE);
        blocks.append(IndexSessionMarker.FILE);
        assertFalse(marker.isClean());
    }

    @Test
    void wrongMagicIsAnErrorNotACleanShutdown() throws Exception {
        marker.write(true);
        byte[] bytes = Files.readAllBytes(dir.resolve(IndexSessionMarker.FILE));
        bytes[0] ^= 0x55;
        overwrite(bytes);
        assertThrows(StorageInternalException.class, marker::isClean);
    }

    @Test
    void unknownStateIsAnErrorNotACleanShutdown() throws Exception {
        marker.write(true);
        byte[] bytes = Files.readAllBytes(dir.resolve(IndexSessionMarker.FILE));
        bytes[4] = 99;
        overwrite(bytes);
        assertThrows(StorageInternalException.class, marker::isClean);
    }

    @Test
    void partialBlockIsAnError() throws Exception {
        overwrite(new byte[BLOCK_SIZE / 2]);
        assertThrows(StorageInternalException.class, marker::isClean);
    }

    /** Replaces the file's bytes, with a fresh handle so no open channel hides the change. */
    private void overwrite(byte[] bytes) throws Exception {
        blocks.closeFiles();
        Files.write(dir.resolve(IndexSessionMarker.FILE), bytes);
    }
}
