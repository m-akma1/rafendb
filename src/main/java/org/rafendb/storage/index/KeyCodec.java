package org.rafendb.storage.index;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.rafendb.common.schema.Column;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.Value;

/**
 * Encodes index keys and entries into page bytes. Every method reads or writes at the buffer's
 * position and advances it, so entries can be laid out back to back.
 *
 * <p>Format, big-endian: INTEGER as an {@code int} and FLOAT as a {@code float}, 4 bytes each;
 * CHAR and VARCHAR as a 2-byte length followed by that many UTF-8 bytes; a Rid as a {@code long}
 * block and an {@code int} slot. An entry is its key followed by its Rid. Keys are never NULL, so
 * there is no NULL marker.
 *
 * <p>This is the index's own codec, separate from the record serializer, so that index pages do
 * not depend on the record format.
 *
 * <p>Stateless and thread-safe; the buffers passed in are not.
 */
public final class KeyCodec {

    /** Bytes of an encoded Rid: an 8-byte block and a 4-byte slot. */
    public static final int RID_BYTES = 12;

    /** Bytes of an encoded INTEGER or FLOAT key. */
    private static final int NUMBER_BYTES = 4;

    /** Bytes of the length prefix of a text key. */
    private static final int LENGTH_BYTES = 2;

    private KeyCodec() {}

    /**
     * Writes a key.
     *
     * @param buf the buffer, with room for {@link #keySize} bytes
     * @param key a non-NULL key of the column's type family
     * @param column the indexed column
     * @throws IllegalArgumentException if {@code key} is text longer than {@code column}'s length
     *     in UTF-8 bytes; nothing is written then
     * @throws java.nio.BufferOverflowException if the buffer is too small
     */
    public static void writeKey(ByteBuffer buf, Value key, Column column) {
        switch (column.type()) {
            case INTEGER -> buf.putInt(key.asInt());
            case FLOAT -> buf.putFloat(key.asFloat());
            case CHAR, VARCHAR -> {
                byte[] bytes = utf8(key, column);
                buf.putShort((short) bytes.length);
                buf.put(bytes);
            }
        }
    }

    /**
     * Reads a key written by {@link #writeKey}.
     *
     * @param buf the buffer, positioned at the key
     * @param column the indexed column
     * @return the key, of type {@code column.type()}
     * @throws java.nio.BufferUnderflowException if the buffer ends inside the key
     */
    public static Value readKey(ByteBuffer buf, Column column) {
        return switch (column.type()) {
            case INTEGER -> Value.ofInt(buf.getInt());
            case FLOAT -> Value.ofFloat(buf.getFloat());
            case CHAR, VARCHAR -> {
                byte[] bytes = new byte[Short.toUnsignedInt(buf.getShort())];
                buf.get(bytes);
                yield Value.ofText(column.type(), new String(bytes, StandardCharsets.UTF_8));
            }
        };
    }

    /**
     * Returns the encoded size of a key.
     *
     * @param key a non-NULL key of the column's type family
     * @param column the indexed column
     * @return the size in bytes
     * @throws IllegalArgumentException if {@code key} is text longer than {@code column}'s length
     *     in UTF-8 bytes
     */
    public static int keySize(Value key, Column column) {
        return column.type().isText() ? LENGTH_BYTES + utf8(key, column).length : NUMBER_BYTES;
    }

    /**
     * Returns the largest encoded size of any key of a column.
     *
     * @param column the indexed column
     * @return the size in bytes
     */
    public static int maxKeySize(Column column) {
        return column.type().isText() ? LENGTH_BYTES + column.length() : NUMBER_BYTES;
    }

    /**
     * Writes an entry: its key, then its Rid.
     *
     * @param buf the buffer, with room for {@link #entrySize} bytes
     * @param e the entry, whose key is of the column's type family
     * @param column the indexed column
     * @throws IllegalArgumentException if the key is text longer than {@code column}'s length in
     *     UTF-8 bytes; nothing is written then
     * @throws java.nio.BufferOverflowException if the buffer is too small
     */
    public static void writeEntry(ByteBuffer buf, IndexEntry e, Column column) {
        writeKey(buf, e.key(), column);
        buf.putLong(e.rid().block());
        buf.putInt(e.rid().slot());
    }

    /**
     * Reads an entry written by {@link #writeEntry}.
     *
     * @param buf the buffer, positioned at the entry
     * @param column the indexed column
     * @return the entry
     * @throws java.nio.BufferUnderflowException if the buffer ends inside the entry
     */
    public static IndexEntry readEntry(ByteBuffer buf, Column column) {
        Value key = readKey(buf, column);
        return new IndexEntry(key, new Rid(buf.getLong(), buf.getInt()));
    }

    /**
     * Returns the encoded size of an entry.
     *
     * @param e the entry, whose key is of the column's type family
     * @param column the indexed column
     * @return the size in bytes
     * @throws IllegalArgumentException if the key is text longer than {@code column}'s length in
     *     UTF-8 bytes
     */
    public static int entrySize(IndexEntry e, Column column) {
        return keySize(e.key(), column) + RID_BYTES;
    }

    /**
     * Returns the largest encoded size of any entry of a column.
     *
     * @param column the indexed column
     * @return the size in bytes
     */
    public static int maxEntrySize(Column column) {
        return maxKeySize(column) + RID_BYTES;
    }

    /**
     * Returns a text key's UTF-8 bytes, checked against the column's length so that no entry is
     * larger than {@link #maxEntrySize}, which page layouts rely on.
     *
     * @param key a non-NULL text key
     * @param column the indexed text column
     * @return the bytes
     * @throws IllegalArgumentException if the bytes exceed {@code column.length()}
     */
    private static byte[] utf8(Value key, Column column) {
        byte[] bytes = key.asText().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > column.length()) {
            throw new IllegalArgumentException(
                    "key is "
                            + bytes.length
                            + " bytes, over the "
                            + column.length()
                            + " of column "
                            + column.name());
        }
        return bytes;
    }
}
