package org.rafendb.storage.record;

import java.util.Objects;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.storage.buffer.BufferManager;
import org.rafendb.storage.file.BlockManager;

/**
 * SM1 lazy cursor skeleton. Each instance owns its position, not a pin between next calls.
 * Every next call copies/decodes its row and unpins in finally before returning or throwing.
 * Never call the facade while holding a page latch. A new instance is needed for rescan;
 * the schema stays available even for an empty table. Not thread-safe.
 */
public class TableScan implements RecordCursor {
    protected final String fileName;
    protected final TableSchema schema;
    protected final BlockManager blocks;
    protected final BufferManager buffers;
    protected final RecordSerializer serializer;

    /** Wires a fresh scan without pinning pages or loading the table into memory. */
    public TableScan(
            String fileName,
            TableSchema schema,
            BlockManager blocks,
            BufferManager buffers,
            RecordSerializer serializer) {
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.buffers = Objects.requireNonNull(buffers, "buffers");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    /** {@inheritDoc} */
    @Override
    public StoredRow next() throws StorageInternalException {
        // TODO SM1: Pin blok, baca liveSlots/read, dekode record, lalu sertakan Rid-nya.
        // TODO SM1: Unpin di finally sebelum setiap return/error; simpan posisi, bukan pin.
        // TODO SM1: Lewati reservasi/tombstone; kembalikan null setelah EOF.
        throw new UnsupportedOperationException("SM1: TableScan.next");
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        // TODO SM1: Tandai cursor tutup secara idempotent; tidak ada pin antar-panggilan next.
        throw new UnsupportedOperationException("SM1: TableScan.close");
    }
}
