package org.rafendb.processor.operator.write;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.RowImageCodec;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

class TextRowImageCodecTest {
    private final RowImageCodec codec = RowImageCodec.DEFAULT;

    // ------------------------------------------------------------------ helpers

    private static Row row(Value... values) {
        return new Row(List.of(values));
    }

    /**
     * Value.equals treats CHAR and VARCHAR as one family and compares two NULLs by family only,
     * so the type, the null flag and the payload are checked one by one here.
     */
    private static void assertSameRow(Row expected, Row actual) {
        assertEquals(expected.size(), actual.size(), "column count");
        for (int i = 0; i < expected.size(); i++) {
            Value e = expected.get(i);
            Value a = actual.get(i);
            String at = "column " + i;
            assertEquals(e.type(), a.type(), at + " type");
            assertEquals(e.isNull(), a.isNull(), at + " null flag");
            if (e.isNull()) continue;
            switch (e.type()) {
                case INTEGER -> assertEquals(e.asInt(), a.asInt(), at);
                case FLOAT ->
                        assertEquals(
                                Float.floatToIntBits(e.asFloat()),
                                Float.floatToIntBits(a.asFloat()),
                                at);
                case CHAR, VARCHAR -> assertEquals(e.asText(), a.asText(), at);
            }
        }
    }

    private void assertRoundTrip(Row row) {
        assertSameRow(row, codec.decode(codec.encode(row)));
    }

    private void assertRejected(String image) {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(image.getBytes(StandardCharsets.UTF_8)),
                "should reject: " + image);
    }

    // ------------------------------------------------------------ single values

    @Test
    void integers() {
        for (int v : new int[] {0, 1, -1, 42, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertRoundTrip(row(Value.ofInt(v)));
        }
    }

    @Test
    void floatsKeepExactBits() {
        float[] samples = {
            0.0f,
            1.5f,
            -2.25f,
            0.1f,
            Float.MIN_VALUE,
            Float.MAX_VALUE,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        };
        for (float v : samples) {
            assertRoundTrip(row(Value.ofFloat(v)));
        }
    }

    @Test
    void nullKeepsItsTypeForEveryType() {
        for (DataType t : DataType.values()) {
            assertRoundTrip(row(Value.nullOf(t)));
        }
    }

    @Test
    void emptyStringIsNotNull() {
        for (DataType t : new DataType[] {DataType.CHAR, DataType.VARCHAR}) {
            Row decoded = codec.decode(codec.encode(row(Value.ofText(t, ""))));
            assertFalse(decoded.get(0).isNull());
            assertEquals("", decoded.get(0).asText());
            assertEquals(t, decoded.get(0).type());
        }
    }

    @Test
    void charAndVarcharStayDistinct() {
        Row decoded =
                codec.decode(
                        codec.encode(
                                row(
                                        Value.ofText(DataType.CHAR, "same"),
                                        Value.ofText(DataType.VARCHAR, "same"))));
        assertEquals(DataType.CHAR, decoded.get(0).type());
        assertEquals(DataType.VARCHAR, decoded.get(1).type());
    }

    // -------------------------------------------------------------------- text

    @Test
    void trickyText() {
        String[] samples = {
            "a;b",
            "a:b",
            ";",
            ":",
            ";;",
            "RI1;",
            "N:VARCHAR;",
            "V:3:abc;",
            "I:5;",
            " leading and trailing ",
            "line1\nline2",
            "tab\there",
            "nul\0inside",
            "back\\slash",
            "\"quoted\" and 'single'",
            "caf\u00e9",
            "\u4e2d\u6587",
            "emoji \uD83D\uDE00 pair",
            "x".repeat(4000),
            "\u4e2d".repeat(1000)
        };
        for (String s : samples) {
            assertRoundTrip(row(Value.ofText(DataType.VARCHAR, s)));
            assertRoundTrip(row(Value.ofText(DataType.CHAR, s)));
        }
    }

    // ------------------------------------------------------------------- rows

    @Test
    void emptyRow() {
        assertRoundTrip(new Row(List.of()));
    }

    @Test
    void mixedRowMatchesDocumentedFormat() {
        Row r =
                row(
                        Value.ofInt(5),
                        Value.nullOf(DataType.VARCHAR),
                        Value.ofText(DataType.VARCHAR, "ab;c"),
                        Value.ofFloat(1.5f));
        byte[] image = codec.encode(r);
        assertEquals(
                "RI1;4;I:5;N:VARCHAR;V:4:ab;c;F:1069547520;",
                new String(image, StandardCharsets.UTF_8));
        assertSameRow(r, codec.decode(image));
    }

    @Test
    void emptyRowFormat() {
        assertEquals(
                "RI1;0;", new String(codec.encode(new Row(List.of())), StandardCharsets.UTF_8));
    }

    @Test
    void wideRow() {
        Value[] values = new Value[200];
        for (int i = 0; i < values.length; i++) {
            values[i] =
                    switch (i % 4) {
                        case 0 -> Value.ofInt(i);
                        case 1 -> Value.nullOf(DataType.FLOAT);
                        case 2 -> Value.ofText(DataType.VARCHAR, "v" + i);
                        default -> Value.ofText(DataType.CHAR, "c" + i);
                    };
        }
        assertRoundTrip(row(values));
    }

    @Test
    void encodeIsDeterministic() {
        Row r = row(Value.ofInt(1), Value.ofText(DataType.VARCHAR, "x"));
        assertArrayEquals(codec.encode(r), codec.encode(r));
    }

    // ------------------------------------- what FRM and the WAL do to the image

    private static Row hardRow() {
        return row(
                Value.ofInt(-7),
                Value.nullOf(DataType.CHAR),
                Value.ofText(DataType.VARCHAR, "nul\0 and emoji \uD83D\uDE00 and ; : \n"),
                Value.ofFloat(Float.MIN_VALUE));
    }

    /** FRManager.writeLog does new String(bytes, UTF_8); the WAL later gives that String back. */
    @Test
    void survivesFrmStringConversion() {
        Row r = hardRow();
        byte[] image = codec.encode(r);
        String asFrmString = new String(image, StandardCharsets.UTF_8);
        byte[] back = asFrmString.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(image, back);
        assertSameRow(r, codec.decode(back));
    }

    /** WALRecord stores images with DataOutputStream.writeUTF / readUTF. */
    @Test
    void survivesWalWriteUtf() throws IOException {
        Row r = hardRow();
        String image = new String(codec.encode(r), StandardCharsets.UTF_8);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(image);
        }
        String read;
        try (DataInputStream in =
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            read = in.readUTF();
        }
        assertEquals(image, read);
        assertSameRow(r, codec.decode(read.getBytes(StandardCharsets.UTF_8)));
    }

    // --------------------------------------------------- random (fixed seed)

    private static final String[] PIECES = {
        ";",
        ":",
        "RI1;",
        "N:VARCHAR;",
        "V:3:abc;",
        " ",
        "\n",
        "\t",
        "\0",
        "\\",
        "\"",
        "'",
        "\u00e9",
        "\u4e2d",
        "\uD83D\uDE00"
    };

    private static String randomText(Random rnd) {
        StringBuilder sb = new StringBuilder();
        int parts = rnd.nextInt(8);
        for (int i = 0; i < parts; i++) {
            if (rnd.nextInt(3) == 0) {
                sb.append((char) ('a' + rnd.nextInt(26)));
            } else {
                sb.append(PIECES[rnd.nextInt(PIECES.length)]);
            }
        }
        return sb.toString();
    }

    private static Value randomValue(Random rnd) {
        DataType type = DataType.values()[rnd.nextInt(DataType.values().length)];
        if (rnd.nextInt(5) == 0) return Value.nullOf(type);
        return switch (type) {
            case INTEGER -> Value.ofInt(rnd.nextInt());
            case FLOAT -> Value.ofFloat(Float.intBitsToFloat(rnd.nextInt()));
            case CHAR, VARCHAR -> Value.ofText(type, randomText(rnd));
        };
    }

    @Test
    void randomRows() {
        Random rnd = new Random(42);
        for (int n = 0; n < 5000; n++) {
            Value[] values = new Value[rnd.nextInt(13)];
            for (int i = 0; i < values.length; i++) {
                values[i] = randomValue(rnd);
            }
            Row r = row(values);
            assertRoundTrip(r);
            // the FRM String conversion must not change anything either
            byte[] image = codec.encode(r);
            assertArrayEquals(
                    image,
                    new String(image, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---------------------------------------------------------- bad input

    @Test
    void decodeRejectsForeignAndMalformedImages() {
        assertRejected("Alice:100"); // image from FR2's own tests
        assertRejected(""); // empty
        assertRejected("RI2;0;"); // other version
        assertRejected("RI1;"); // no count
        assertRejected("RI1;x;"); // count is not a number
        assertRejected("RI1;-1;"); // negative count
        assertRejected("RI1;1;"); // count says 1, no value follows
        assertRejected("RI1;0;extra"); // trailing data
        assertRejected("RI1;1;I:5;I:6;"); // more values than the count
        assertRejected("RI1;1;I:5"); // missing terminator
        assertRejected("RI1;1;I:abc;"); // not a number
        assertRejected("RI1;1;X:1;"); // unknown tag
        assertRejected("RI1;1;N:BOOLEAN;"); // unknown type
        assertRejected("RI1;1;V:99:abc;"); // length longer than the image
        assertRejected("RI1;1;V:-1:;"); // negative length
        assertRejected("RI1;1;V:3:abc"); // missing ';' after text
        assertRejected("RI1;1;V:3:abcd;"); // text longer than its length
    }

    @Test
    void decodeRejectsInvalidUtf8() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(new byte[] {(byte) 0xFF, (byte) 0xFE}));
    }

    @Test
    void encodeRejectsUnpairedSurrogate() {
        // would not survive the String round trip in FRM, so it must fail loudly
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.encode(row(Value.ofText(DataType.VARCHAR, "a\uD800b"))));
    }
}
