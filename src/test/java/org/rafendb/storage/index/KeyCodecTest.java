package org.rafendb.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.rafendb.storage.index.IndexFixtures.ch;
import static org.rafendb.storage.index.IndexFixtures.charCol;
import static org.rafendb.storage.index.IndexFixtures.f;
import static org.rafendb.storage.index.IndexFixtures.floatCol;
import static org.rafendb.storage.index.IndexFixtures.i;
import static org.rafendb.storage.index.IndexFixtures.intCol;
import static org.rafendb.storage.index.IndexFixtures.rid;
import static org.rafendb.storage.index.IndexFixtures.s;
import static org.rafendb.storage.index.IndexFixtures.varcharCol;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.rafendb.common.schema.Column;
import org.rafendb.common.type.Value;

/** Tests {@link KeyCodec}: round trips, sizes, and relative buffer positions. */
class KeyCodecTest {

    @Test
    void roundTripsEveryType() {
        for (Map.Entry<Value, Column> c :
                List.of(
                        Map.entry(i(-7), intCol("a")),
                        Map.entry(f(1.5f), floatCol("b")),
                        Map.entry(ch("héllo"), charCol("c", 10)),
                        Map.entry(s("日本"), varcharCol("d", 10)),
                        Map.entry(s(""), varcharCol("e", 1)))) {
            ByteBuffer buf = ByteBuffer.allocate(64);
            KeyCodec.writeKey(buf, c.getKey(), c.getValue());
            assertEquals(KeyCodec.keySize(c.getKey(), c.getValue()), buf.position());
            buf.flip();
            assertEquals(c.getKey(), KeyCodec.readKey(buf, c.getValue()));
            assertEquals(0, buf.remaining());
        }
    }

    @Test
    void readKeyTakesTheColumnType() {
        ByteBuffer buf = ByteBuffer.allocate(16);
        KeyCodec.writeKey(buf, s("ab"), charCol("c", 4));
        buf.flip();
        assertEquals(ch("ab").type(), KeyCodec.readKey(buf, charCol("c", 4)).type());
    }

    @Test
    void entryRoundTrip() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        IndexEntry e = new IndexEntry(i(42), rid(3, 7));
        KeyCodec.writeEntry(buf, e, intCol("a"));
        assertEquals(4 + KeyCodec.RID_BYTES, buf.position());
        assertEquals(KeyCodec.entrySize(e, intCol("a")), buf.position());
        buf.flip();
        assertEquals(e, KeyCodec.readEntry(buf, intCol("a")));
    }

    @Test
    void readsSequentialEntriesFromAnyPosition() {
        Column col = varcharCol("d", 10);
        List<IndexEntry> entries =
                List.of(
                        new IndexEntry(s("b"), rid(0, 1)),
                        new IndexEntry(s("日本"), rid(5, 0)),
                        new IndexEntry(s("zz"), rid(1, 9)));
        ByteBuffer buf = ByteBuffer.allocate(128);
        buf.position(7);
        for (IndexEntry e : entries) {
            KeyCodec.writeEntry(buf, e, col);
        }
        buf.position(7);
        for (IndexEntry e : entries) {
            assertEquals(e, KeyCodec.readEntry(buf, col));
        }
    }

    @Test
    void maxSizesBoundActualSizes() {
        assertEquals(2 + 10, KeyCodec.maxKeySize(varcharCol("d", 10)));
        assertEquals(4, KeyCodec.maxKeySize(floatCol("b")));
        assertEquals(4 + 12, KeyCodec.maxEntrySize(intCol("a")));
        assertEquals(2 + 4000 + 12, KeyCodec.maxEntrySize(varcharCol("d", 4000)));
        // Size counts UTF-8 bytes, not characters: "日本" is 6 bytes.
        assertEquals(2 + 6, KeyCodec.keySize(s("日本"), varcharCol("d", 10)));
    }

    @Test
    void largeRidComponentsSurvive() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        IndexEntry e = new IndexEntry(i(1), rid(Integer.MAX_VALUE + 1L, Integer.MAX_VALUE));
        KeyCodec.writeEntry(buf, e, intCol("a"));
        buf.flip();
        assertEquals(e, KeyCodec.readEntry(buf, intCol("a")));
    }

    @Test
    void textLongerThanColumnRejected() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        // 3 characters but 4 UTF-8 bytes, over a 3-byte column.
        assertThrows(
                IllegalArgumentException.class,
                () -> KeyCodec.writeKey(buf, s("éab"), varcharCol("d", 3)));
        assertEquals(0, buf.position());
    }
}
