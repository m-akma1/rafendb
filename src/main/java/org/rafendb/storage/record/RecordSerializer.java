package org.rafendb.storage.record;

import java.nio.ByteBuffer;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.Value;

/**
 * SM3 internal record-codec skeleton from the M1 design. Stateless/thread-safe; buffers are
 * caller-owned. Separate from KeyCodec, which deliberately has a different index-entry format.
 * No serializer/slotted-page interfaces are added to api.storage.
 * The internal serialize result deliberately uses byte[] rather than the M1 diagram's ByteBuffer,
 * matching SlottedPage.write/restore without exposing a page view. Read passes ByteBuffer.wrap
 * over SlottedPage.read's copied bytes; encodedSize equals serialize.length.
 * Record format v1: ceil(columnCount/8) NULL-bitmap bytes (bit i%8, byte i/8, 1 means NULL),
 * then non-NULL values in schema order. INTEGER/FLOAT are 4-byte big-endian values; CHAR/VARCHAR
 * use an unsigned 2-byte UTF-8 byte length followed by those exact bytes, without CHAR padding.
 * The page header stores format version 1; the codec consumes only the supplied record slice.
 */
public class RecordSerializer {
    /** Encodes one non-NULL value using record format v1; row NULLs live only in the bitmap. */
    public byte[] encodeValue(Value value, Column column) {
        // TODO SM3: Encode angka big-endian dan teks UTF-8 berprefix panjang unsigned short.
        throw new UnsupportedOperationException("SM3: RecordSerializer.encodeValue");
    }

    /** Decodes from the current position, advancing by one value; rejects corrupt bytes. */
    public Value decodeValue(ByteBuffer bytes, Column column) throws StorageInternalException {
        // TODO SM3: Validasi panjang byte dan dekode sesuai skema kolom.
        throw new UnsupportedOperationException("SM3: RecordSerializer.decodeValue");
    }

    /** Encodes a whole row, including its NULL bitmap, in schema order. */
    public byte[] serialize(Row row, TableSchema schema) {
        // TODO SM3: Tulis bitmap NULL lalu nilai non-NULL; ukuran harus sama dengan encodedSize.
        throw new UnsupportedOperationException("SM3: RecordSerializer.serialize");
    }

    /** Decodes a record-sized buffer slice; SQL NULL is distinct from record absence. */
    public Row deserialize(ByteBuffer bytes, TableSchema schema) throws StorageInternalException {
        // TODO SM3: Dekode tiap kolom tanpa membaca byte milik record sebelah.
        throw new UnsupportedOperationException("SM3: RecordSerializer.deserialize");
    }

    /** Returns exactly the byte count serialize would produce, including NULL/length metadata. */
    public int encodedSize(Row row, TableSchema schema) {
        // TODO SM3: Samakan encodedSize dengan serialize agar reservasi tidak kurang.
        throw new UnsupportedOperationException("SM3: RecordSerializer.encodedSize");
    }
}
