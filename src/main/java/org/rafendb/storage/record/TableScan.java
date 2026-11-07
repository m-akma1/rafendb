package org.rafendb.storage.record;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.storage.buffer.BufferManager;
import org.rafendb.storage.file.BlockId;
import org.rafendb.storage.file.BlockManager;

/**
 * Reads the live records of one table lazily, block by block, in Rid order. No page stays pinned
 * between calls: each call pins one block, reads one record, and unpins it. Because the next
 * record is always looked up as "the first live slot after the last one returned", deleting
 * records while the scan is open does not disturb it. Owned by one thread.
 */
public class TableScan implements RecordCursor {
    private final String file;
    private final TableSchema schema;
    private final BlockManager blocks;
    private final BufferManager buffers;
    private final RecordSerializer serializer;
    private long block;
    private int lastSlot = -1;
    private boolean done;
    private boolean closed;

    /** Creates a cursor positioned before the first record of the table file. */
    public TableScan(
            String file,
            TableSchema schema,
            BlockManager blocks,
            BufferManager buffers,
            RecordSerializer serializer) {
        this.file = Objects.requireNonNull(file, "file");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.buffers = Objects.requireNonNull(buffers, "buffers");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    /** {@inheritDoc} */
    @Override
    public StoredRow next() throws StorageInternalException {
        if (closed) {
            throw new IllegalStateException("cursor is closed");
        }
        while (!done) {
            if (block >= blocks.size(file)) {
                done = true;
                return null;
            }
            StoredRow row = readNextInBlock();
            if (row != null) {
                return row;
            }
            block++;
            lastSlot = -1;
        }
        return null;
    }

    /** {@inheritDoc} Idempotent. No page is held between calls, so there is nothing to unpin. */
    @Override
    public void close() {
        closed = true;
    }

    /** Returns the first live record after the last returned slot in this block, or null. */
    private StoredRow readNextInBlock() throws StorageInternalException {
        BlockId id = new BlockId(file, block);
        ByteBuffer buffer = buffers.pin(id);
        try {
            SlottedPage page = new SlottedPage(buffer);
            if (!page.isFormatted()) {
                return null;
            }
            for (int slot : page.liveSlots()) {
                if (slot <= lastSlot) {
                    continue;
                }
                Optional<byte[]> bytes = page.read(slot);
                if (bytes.isEmpty()) {
                    continue;
                }
                lastSlot = slot;
                Row row = serializer.deserialize(ByteBuffer.wrap(bytes.get()), schema);
                return new StoredRow(new Rid(block, slot), row);
            }
            return null;
        } finally {
            buffers.unpin(id);
        }
    }
}
