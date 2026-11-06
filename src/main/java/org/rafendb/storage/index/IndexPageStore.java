package org.rafendb.storage.index;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.storage.buffer.BufferManager;
import org.rafendb.storage.file.BlockId;
import org.rafendb.storage.file.BlockManager;

/**
 * The fixed-size pages of one index file, read and written through the shared buffer, so index
 * pages follow the same eviction and WAL rules as table pages.
 *
 * <p>Each call holds exactly one pin and releases it before returning, even on failure. An index
 * therefore never occupies more than one frame, on top of the table page the facade holds while
 * maintaining it. A page's bytes are valid only inside the callback; callers must copy what they
 * keep.
 *
 * <p>The file must already exist; creating and deleting it belongs to the caller. Pages are never
 * freed individually: a rebuild replaces the whole file. Each page is latched during a call, but
 * operations spanning several pages need the caller's own lock.
 */
public final class IndexPageStore {
    private final String fileName;
    private final BlockManager blocks;
    private final BufferManager buffers;

    /**
     * Attaches to an index file without touching it.
     *
     * @param fileName the existing file, as assigned by the catalog
     * @param blocks the database's block manager
     * @param buffers the database's buffer manager, over {@code blocks}
     * @throws NullPointerException if an argument is {@code null}
     */
    public IndexPageStore(String fileName, BlockManager blocks, BufferManager buffers) {
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.buffers = Objects.requireNonNull(buffers, "buffers");
    }

    /**
     * Returns the page size.
     *
     * @return the size in bytes
     */
    public int pageSize() {
        return blocks.blockSize();
    }

    /**
     * Returns the number of pages in the file.
     *
     * @return the count; pages are numbered from zero
     * @throws StorageInternalException on an I/O failure
     */
    public long pageCount() throws StorageInternalException {
        return blocks.size(fileName);
    }

    /**
     * Adds a zero-filled page at the end of the file.
     *
     * @return the new page's number
     * @throws StorageInternalException on an I/O failure
     */
    public long append() throws StorageInternalException {
        return blocks.append(fileName).number();
    }

    /**
     * Reads a page.
     *
     * @param <T> the result type
     * @param page the page number
     * @param reader receives a read-only view of the page at position zero
     * @return what {@code reader} returned, which must not refer to the view
     * @throws StorageInternalException if the page does not exist, on an I/O failure, or if every
     *     buffer frame is pinned
     */
    public <T> T read(long page, Function<ByteBuffer, T> reader) throws StorageInternalException {
        BlockId block = new BlockId(fileName, page);
        ByteBuffer bytes = buffers.pin(block);
        try {
            return reader.apply(bytes.asReadOnlyBuffer());
        } finally {
            buffers.unpin(block);
        }
    }

    /**
     * Changes a page as part of a logged operation. The page cannot reach the disk before the log
     * does, up to the highest LSN of all its changes. If {@code writer} throws, the page is not
     * marked dirty, so it must not have changed anything by then.
     *
     * @param page the page number
     * @param lsn the LSN of the operation's log record
     * @param writer receives a writable view of the page at position zero
     * @throws StorageInternalException if the page does not exist, on an I/O failure, or if every
     *     buffer frame is pinned
     */
    public void write(long page, long lsn, Consumer<ByteBuffer> writer)
            throws StorageInternalException {
        BlockId block = new BlockId(fileName, page);
        ByteBuffer bytes = buffers.pin(block);
        try {
            writer.accept(bytes);
            buffers.markDirty(block, lsn);
        } finally {
            buffers.unpin(block);
        }
    }

    /**
     * Changes a page without a log record, for building an index from its table: the result can be
     * rebuilt, so it needs no WAL of its own. An LSN from an earlier logged change is kept. If
     * {@code writer} throws, the page is not marked dirty, so it must not have changed anything by
     * then.
     *
     * @param page the page number
     * @param writer receives a writable view of the page at position zero
     * @throws StorageInternalException if the page does not exist, on an I/O failure, or if every
     *     buffer frame is pinned
     */
    public void writeUnlogged(long page, Consumer<ByteBuffer> writer)
            throws StorageInternalException {
        BlockId block = new BlockId(fileName, page);
        ByteBuffer bytes = buffers.pin(block);
        try {
            writer.accept(bytes);
            buffers.markUnloggedDirty(block);
        } finally {
            buffers.unpin(block);
        }
    }

    /**
     * Makes every page of the file durable. Pages the buffer evicted earlier are already on disk.
     * The caller must not hold a pin on any of them on another thread.
     *
     * @throws StorageInternalException on an I/O failure or if the log cannot be forced
     */
    public void force() throws StorageInternalException {
        long count = pageCount();
        for (long page = 0; page < count; page++) {
            buffers.flushPage(new BlockId(fileName, page));
        }
        blocks.force(fileName);
    }
}
