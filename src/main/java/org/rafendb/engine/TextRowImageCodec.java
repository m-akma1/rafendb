package org.rafendb.engine;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.RowImageCodec;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/**
 * Text image format, all valid UTF-8:
 *
 * <pre>
 * RI1;{count};{value};{value};...
 *
 * value = N:{TYPE};          NULL of that type        (N:VARCHAR;)
 *       | I:{int};           INTEGER                  (I:5;)
 *       | F:{floatBits};     FLOAT as raw int bits    (F:1069547520;)
 *       | C:{len}:{text};    CHAR, len = chars        (C:3:abc;)
 *       | V:{len}:{text};    VARCHAR, len = chars     (V:4:ab;c;)
 * </pre>
 *
 * Text is read by its length, so it may contain any character, including ';' and ':'.
 * Example: (5, NULL VARCHAR, "ab;c", 1.5f) becomes
 * {@code RI1;4;I:5;N:VARCHAR;V:4:ab;c;F:1069547520;}
 */
public final class TextRowImageCodec implements RowImageCodec {
    private static final String MAGIC = "RI1";

    @Override
    public byte[] encode(Row row) {
        Objects.requireNonNull(row, "row");
        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append(';').append(row.size()).append(';');
        for (Value v : row.values()) {
            DataType type = v.type();
            if (v.isNull()) {
                sb.append("N:").append(type.name()).append(';');
                continue;
            }
            switch (type) {
                case INTEGER -> sb.append("I:").append(v.asInt()).append(';');
                case FLOAT -> sb.append("F:").append(Float.floatToIntBits(v.asFloat())).append(';');
                case CHAR, VARCHAR -> {
                    String text = v.asText();
                    sb.append(type == DataType.CHAR ? 'C' : 'V')
                            .append(':')
                            .append(text.length())
                            .append(':')
                            .append(text)
                            .append(';');
                }
            }
        }
        try {
            ByteBuffer buf =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(sb));
            byte[] out = new byte[buf.remaining()];
            buf.get(out);
            return out;
        } catch (CharacterCodingException e) {
            // e.g. an unpaired surrogate: it would not survive the String round trip
            throw new IllegalArgumentException("Row text cannot be encoded as UTF-8", e);
        }
    }

    @Override
    public Row decode(byte[] image) {
        Objects.requireNonNull(image, "image");
        String s;
        try {
            s =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(image))
                            .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Row image is not valid UTF-8", e);
        }

        Cursor in = new Cursor(s);
        in.expect(MAGIC + ";");
        int count = in.readInt(';');
        if (count < 0) {
            throw in.bad("negative value count " + count);
        }
        List<Value> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            char tag = in.readChar();
            in.expect(":");
            switch (tag) {
                case 'N' -> values.add(Value.nullOf(parseType(in, in.readUntil(';'))));
                case 'I' -> values.add(Value.ofInt(in.readInt(';')));
                case 'F' -> values.add(Value.ofFloat(Float.intBitsToFloat(in.readInt(';'))));
                case 'C', 'V' -> {
                    int len = in.readInt(':');
                    String text = in.take(len);
                    in.expect(";");
                    values.add(Value.ofText(tag == 'C' ? DataType.CHAR : DataType.VARCHAR, text));
                }
                default -> throw in.bad("unknown value tag '" + tag + "'");
            }
        }
        in.requireEnd();
        return new Row(values);
    }

    private static DataType parseType(Cursor in, String name) {
        try {
            return DataType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw in.bad("unknown type '" + name + "'");
        }
    }

    /** Reads the image string from left to right; every failure names the offset. */
    private static final class Cursor {
        private final String s;
        private int pos;

        Cursor(String s) {
            this.s = s;
        }

        void expect(String literal) {
            if (!s.startsWith(literal, pos)) {
                throw bad("expected '" + literal + "'");
            }
            pos += literal.length();
        }

        /** Returns the text before the next {@code end}, and moves past {@code end}. */
        String readUntil(char end) {
            int i = s.indexOf(end, pos);
            if (i < 0) {
                throw bad("missing '" + end + "'");
            }
            String text = s.substring(pos, i);
            pos = i + 1;
            return text;
        }

        int readInt(char end) {
            String text = readUntil(end);
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw bad("not a number: '" + text + "'");
            }
        }

        char readChar() {
            if (pos >= s.length()) {
                throw bad("unexpected end of image");
            }
            return s.charAt(pos++);
        }

        /** Takes exactly {@code n} chars. */
        String take(int n) {
            if (n < 0 || n > s.length() - pos) {
                throw bad("invalid text length " + n);
            }
            String text = s.substring(pos, pos + n);
            pos += n;
            return text;
        }

        void requireEnd() {
            if (pos != s.length()) {
                throw bad("trailing data");
            }
        }

        IllegalArgumentException bad(String why) {
            return new IllegalArgumentException(
                    "Malformed row image at offset " + pos + ": " + why);
        }
    }
}
