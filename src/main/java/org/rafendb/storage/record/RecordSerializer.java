package org.rafendb.storage.record;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/**
 * Serializes and deserializes a data row in an SQL table.
 *
 * Serialized row format:
 * - A null bitmap with one bit per column (1 = NULL, 0 = NOT NULL).
 * - A compact VARCHAR offset table with one 2-byte offset for each non-NULL
 *   VARCHAR column, ordered by schema column order.
 * - A fixed-length block containing INTEGER, FLOAT, and CHAR fields in schema
 *   column order. NULL fixed-length fields still reserve their space.
 * - Variable-length VARCHAR data stored sequentially at the end of the record.
 *
 * VARCHAR offsets are unsigned 2-byte big-endian values relative to the start
 * of the record. Each points to the corresponding VARCHAR length prefix.
 *
 * The {@code encodeValue} and {@code decodeValue} methods handle the raw byte
 * formatting for a given type (including VARCHAR's 2-byte length prefix). NULL
 * tracking is handled by {@code serialize} and {@code deserialize} via the
 * null bitmap.
 */
public class RecordSerializer {
    private static final int MAX_VARCHAR_BYTES = 0xFFFF;
    private static final int MAX_RECORD_OFFSET = 0xFFFF;

    /**
     * Returns the raw bytes representing the data of an SQL value.
     * NULL flags are not prepended here, as they are handled in the record's
     * null bitmap.
     *
     * @param value an SQL value
     * @param column column definition the value resides in
     * @return byte[] representation of the raw data (empty if NULL)
     * @throws IllegalArgumentException if the value does not fit the column
     */
    public byte[] encodeValue(Value value, Column column) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(column, "column");
        checkType(value, column);

        Object raw = raw(value);
        if (raw == null) {
            if (!column.nullable()) {
                throw new IllegalArgumentException(
                        "column '" + column.name() + "' does not accept NULL");
            }
            return new byte[0];
        }

        switch (column.type()) {
            case INTEGER -> {
                byte[] body = new byte[4];
                putInt(body, 0, asInteger(raw, column));
                return body;
            }
            case FLOAT -> {
                byte[] body = new byte[4];
                putInt(body, 0, Float.floatToIntBits(asFloat(raw, column)));
                return body;
            }
            case CHAR -> {
                byte[] text = textBytes(raw, column);
                byte[] body = new byte[column.length()];
                System.arraycopy(text, 0, body, 0, text.length);
                return body;
            }
            case VARCHAR -> {
                byte[] text = textBytes(raw, column);
                byte[] body = new byte[2 + text.length];
                setU16(body, 0, text.length);
                System.arraycopy(text, 0, body, 2, text.length);
                return body;
            }
            default ->
                    throw new IllegalArgumentException("Unsupported data type: " + column.type());
        }
    }

    /**
     * Returns an SQL value from its raw byte representation.
     * Reads from the current position of the buffer and moves the position past
     * the value.
     *
     * @param bytes bytes representation of the value's data
     * @param column column definition the value resides in
     * @return an SQL value the bytes represent
     * @throws IllegalArgumentException if the bytes are truncated or invalid
     */
    public Value decodeValue(ByteBuffer bytes, Column column) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(column, "column");

        Object result;
        switch (column.type()) {
            case INTEGER -> {
                require(bytes, 4, column, "INTEGER");
                result = getInt(bytes);
            }
            case FLOAT -> {
                require(bytes, 4, column, "FLOAT");
                result = Float.intBitsToFloat(getInt(bytes));
            }
            case CHAR -> {
                int length = column.length();
                require(bytes, length, column, "CHAR");

                byte[] raw = new byte[length];
                bytes.get(raw);

                int end = length;
                while (end > 0 && raw[end - 1] == 0x00) {
                    end--;
                }
                result = decodeUtf8(raw, end, column);
            }
            case VARCHAR -> {
                require(bytes, 2, column, "VARCHAR length");
                int length = getU16(bytes);

                if (length > column.length()) {
                    throw new IllegalArgumentException(
                            "VARCHAR length "
                                    + length
                                    + " exceeds column '"
                                    + column.name()
                                    + "' limit "
                                    + column.length());
                }

                require(bytes, length, column, "VARCHAR text");
                byte[] raw = new byte[length];
                bytes.get(raw);
                result = decodeUtf8(raw, length, column);
            }
            default ->
                    throw new IllegalArgumentException("Unsupported data type: " + column.type());
        }

        return new Value(column.type(), result);
    }

    /**
     * Returns the bytes representation of a row.
     *
     * Structure: NULL Bitmap, Compact VARCHAR Offset Table, Fixed-Length
     * Fields, Variable-Length Data.
     *
     * The offset table contains one entry for each non-NULL VARCHAR column,
     * ordered by schema column order. Each offset is relative to the beginning
     * of the record and points to the VARCHAR length prefix.
     *
     * @param row a row of data in a table
     * @param schema table schema the row resides in
     * @return byte[] representation of the row
     * @throws IllegalArgumentException if the row does not match the schema
     */
    public byte[] serialize(Row row, TableSchema schema) {
        List<Value> values = valuesOf(row, schema);
        List<Column> columns = schema.columns();
        int numCols = columns.size();
        int bitmapSize = (numCols + 7) / 8;

        byte[][] encodedValues = new byte[numCols][];
        int nonNullVarcharCount = 0;
        int fixedSize = 0;
        int variableSize = 0;

        for (int i = 0; i < numCols; i++) {
            Column col = columns.get(i);
            Value val = values.get(i);
            byte[] encoded = encodeValue(val, col);
            encodedValues[i] = encoded;

            fixedSize = addExact(fixedSize, fixedFieldSize(col), "Fixed-length fields");
            if (col.type() == DataType.VARCHAR && raw(val) != null) {
                nonNullVarcharCount++;
                variableSize = addExact(variableSize, encoded.length, "Variable-length data");
            }
        }

        int offsetTableSize = multiplyExact(nonNullVarcharCount, 2);
        int fixedStart = addExact(bitmapSize, offsetTableSize, "Record header");
        int variableStart = addExact(fixedStart, fixedSize, "Variable data start");
        int totalSize = addExact(variableStart, variableSize, "Record size");

        // Every VARCHAR offset must fit in an unsigned 2-byte value.
        int variablePos = variableStart;
        for (int i = 0; i < numCols; i++) {
            if (columns.get(i).type() == DataType.VARCHAR && raw(values.get(i)) != null) {
                if (variablePos > MAX_RECORD_OFFSET) {
                    throw new IllegalArgumentException(
                            "Record exceeds maximum VARCHAR offset of "
                                    + MAX_RECORD_OFFSET
                                    + " bytes");
                }
                variablePos =
                        addExact(variablePos, encodedValues[i].length, "Variable-length data");
            }
        }

        byte[] result = new byte[totalSize];
        int fixedPos = fixedStart;
        int offsetIndex = 0;
        variablePos = variableStart;

        for (int i = 0; i < numCols; i++) {
            Column col = columns.get(i);
            Value val = values.get(i);
            byte[] encoded = encodedValues[i];

            if (raw(val) == null) {
                result[i / 8] |= (byte) (1 << (i % 8));
                fixedPos += fixedFieldSize(col);
                continue;
            }

            if (col.type() == DataType.VARCHAR) {
                setU16(result, bitmapSize + offsetIndex * 2, variablePos);
                offsetIndex++;

                System.arraycopy(encoded, 0, result, variablePos, encoded.length);
                variablePos += encoded.length;
            } else {
                System.arraycopy(encoded, 0, result, fixedPos, encoded.length);
                fixedPos += encoded.length;
            }
        }

        return result;
    }

    /**
     * Returns a table row from its bytes representation using the layout:
     * NULL Bitmap, Compact VARCHAR Offset Table, Fixed-Length Fields,
     * Variable-Length Data.
     *
     * The offset table contains one entry for each non-NULL VARCHAR column,
     * ordered by schema column order. The remaining buffer bytes must contain
     * exactly one complete record. To deserialize a record inside a page, the
     * caller must provide a buffer bounded to that record.
     *
     * @param bytes bytes representation of exactly one record
     * @param schema table schema the row resides in
     * @return a table row the bytes represent
     * @throws IllegalArgumentException if the record is truncated, malformed,
     *                                  or contains trailing bytes
     */
    public Row deserialize(ByteBuffer bytes, TableSchema schema) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(schema, "schema");

        List<Column> columns = schema.columns();
        int numCols = columns.size();
        int bitmapSize = (numCols + 7) / 8;
        int startPos = bytes.position();

        require(bytes, bitmapSize, null, "NULL bitmap");

        int nonNullVarcharCount = 0;
        int fixedSize = 0;
        for (int i = 0; i < numCols; i++) {
            Column col = columns.get(i);
            boolean isNull = isNull(bytes, startPos, i);

            if (isNull && !col.nullable()) {
                throw new IllegalArgumentException(
                        "NULL found in non-nullable column '" + col.name() + "'");
            }
            if (col.type() == DataType.VARCHAR && !isNull) {
                nonNullVarcharCount++;
            }
            fixedSize = addExact(fixedSize, fixedFieldSize(col), "Fixed-length fields");
        }

        int offsetTableSize = multiplyExact(nonNullVarcharCount, 2);
        int fixedStart = addExact(bitmapSize, offsetTableSize, "Record header");
        int variableStart = addExact(fixedStart, fixedSize, "Variable data start");
        require(bytes, variableStart, null, "record header and fixed fields");

        List<Value> values = new ArrayList<>(numCols);
        int fixedPos = startPos + fixedStart;
        int offsetIndex = 0;
        int expectedVariableOffset = variableStart;

        for (int i = 0; i < numCols; i++) {
            Column col = columns.get(i);
            boolean isNull = isNull(bytes, startPos, i);

            if (isNull) {
                values.add(new Value(col.type(), null));
                fixedPos += fixedFieldSize(col);
                continue;
            }

            switch (col.type()) {
                case INTEGER, FLOAT, CHAR -> {
                    ByteBuffer fixedBytes = bytes.duplicate();
                    fixedBytes.position(fixedPos);
                    values.add(decodeValue(fixedBytes, col));
                    fixedPos += fixedFieldSize(col);
                }
                case VARCHAR -> {
                    int offset = getU16(bytes, startPos + bitmapSize + offsetIndex * 2);
                    offsetIndex++;

                    if (offset != expectedVariableOffset) {
                        throw new IllegalArgumentException(
                                "Invalid VARCHAR offset for column '"
                                        + col.name()
                                        + "': expected "
                                        + expectedVariableOffset
                                        + ", got "
                                        + offset);
                    }

                    long absoluteOffset = (long) startPos + offset;
                    if (absoluteOffset < (long) startPos + variableStart
                            || absoluteOffset + 2 > bytes.limit()) {
                        throw new IllegalArgumentException(
                                "Invalid VARCHAR offset for column '" + col.name() + "'");
                    }

                    ByteBuffer variableBytes = bytes.duplicate();
                    variableBytes.position((int) absoluteOffset);
                    Value value = decodeValue(variableBytes, col);
                    values.add(value);

                    int consumed = variableBytes.position() - (int) absoluteOffset;
                    expectedVariableOffset =
                            addExact(expectedVariableOffset, consumed, "VARCHAR record offset");
                }
                default ->
                        throw new IllegalArgumentException("Unsupported data type: " + col.type());
            }
        }

        long recordEnd = (long) startPos + expectedVariableOffset;
        if (recordEnd > bytes.limit()) {
            throw new IllegalArgumentException("Truncated record");
        }
        if (recordEnd < bytes.limit()) {
            throw new IllegalArgumentException(
                    "Trailing " + (bytes.limit() - recordEnd) + " bytes after record");
        }

        bytes.position((int) recordEnd);
        return new Row(values);
    }

    /**
     * Returns the exact size of bytes that represents the row when serialized.
     *
     * @param row a row of data in a table
     * @param schema table schema the row resides in
     * @return exact serialized record size in bytes
     * @throws IllegalArgumentException if the row does not match the schema
     */
    public int encodedSize(Row row, TableSchema schema) {
        List<Value> values = valuesOf(row, schema);
        List<Column> columns = schema.columns();
        int numCols = columns.size();
        int bitmapSize = (numCols + 7) / 8;

        byte[][] encodedValues = new byte[numCols][];
        int nonNullVarcharCount = 0;
        int fixedSize = 0;
        int variableSize = 0;

        for (int i = 0; i < numCols; i++) {
            Column col = columns.get(i);
            Value val = values.get(i);
            byte[] encoded = encodeValue(val, col);
            encodedValues[i] = encoded;

            fixedSize = addExact(fixedSize, fixedFieldSize(col), "Fixed-length fields");
            if (col.type() == DataType.VARCHAR && raw(val) != null) {
                nonNullVarcharCount++;
                variableSize = addExact(variableSize, encoded.length, "Variable-length data");
            }
        }

        int offsetTableSize = multiplyExact(nonNullVarcharCount, 2);
        int fixedStart = addExact(bitmapSize, offsetTableSize, "Record header");
        int variableStart = addExact(fixedStart, fixedSize, "Variable data start");

        int variablePos = variableStart;
        for (int i = 0; i < numCols; i++) {
            if (columns.get(i).type() == DataType.VARCHAR && raw(values.get(i)) != null) {
                if (variablePos > MAX_RECORD_OFFSET) {
                    throw new IllegalArgumentException(
                            "Record exceeds maximum VARCHAR offset of "
                                    + MAX_RECORD_OFFSET
                                    + " bytes");
                }
                variablePos =
                        addExact(variablePos, encodedValues[i].length, "Variable-length data");
            }
        }

        return addExact(variableStart, variableSize, "Record size");
    }

    private static List<Value> valuesOf(Row row, TableSchema schema) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(schema, "schema");

        List<Value> values = row.values();
        int expected = schema.columns().size();
        if (values.size() != expected) {
            throw new IllegalArgumentException(
                    "row has "
                            + values.size()
                            + " values but table '"
                            + schema.table()
                            + "' has "
                            + expected
                            + " columns");
        }
        return values;
    }

    private static int fixedFieldSize(Column column) {
        return switch (column.type()) {
            case INTEGER, FLOAT -> 4;
            case CHAR -> {
                if (column.length() <= 0) {
                    throw new IllegalArgumentException(
                            "Invalid CHAR length for column '" + column.name() + "'");
                }
                yield column.length();
            }
            case VARCHAR -> {
                if (column.length() <= 0) {
                    throw new IllegalArgumentException(
                            "Invalid VARCHAR length for column '" + column.name() + "'");
                }
                yield 0;
            }
            default ->
                    throw new IllegalArgumentException("Unsupported data type: " + column.type());
        };
    }

    private static boolean isNull(ByteBuffer bytes, int startPos, int columnIndex) {
        int bitmapByte = bytes.get(startPos + columnIndex / 8) & 0xFF;
        return (bitmapByte & (1 << (columnIndex % 8))) != 0;
    }

    private static void checkType(Value value, Column column) {
        if (value.type() != column.type()) {
            throw new IllegalArgumentException(
                    "column '"
                            + column.name()
                            + "' is "
                            + column.type()
                            + " but value is "
                            + value.type());
        }
    }

    private static Object raw(Value value) {
        return value.value();
    }

    private static int asInteger(Object raw, Column column) {
        if (raw instanceof Integer i) {
            return i;
        }
        throw new IllegalArgumentException(
                "column '"
                        + column.name()
                        + "' expects Integer but got "
                        + raw.getClass().getSimpleName());
    }

    private static float asFloat(Object raw, Column column) {
        if (raw instanceof Float f) {
            return f;
        }
        throw new IllegalArgumentException(
                "column '"
                        + column.name()
                        + "' expects Float but got "
                        + raw.getClass().getSimpleName());
    }

    private static byte[] textBytes(Object raw, Column column) {
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException(
                    "column '"
                            + column.name()
                            + "' expects String but got "
                            + raw.getClass().getSimpleName());
        }

        byte[] text = s.getBytes(StandardCharsets.UTF_8);
        int limit = column.length();
        if (column.type() == DataType.VARCHAR) {
            limit = Math.min(limit, MAX_VARCHAR_BYTES);
        }
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "column '" + column.name() + "' has invalid length " + column.length());
        }
        if (text.length > limit) {
            throw new IllegalArgumentException(
                    "text for column '"
                            + column.name()
                            + "' is "
                            + text.length
                            + " bytes, limit is "
                            + limit);
        }
        return text;
    }

    private static String decodeUtf8(byte[] raw, int length, Column column) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, 0, length))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    "invalid UTF-8 in column '" + column.name() + "'", e);
        }
    }

    private static void require(ByteBuffer bytes, int needed, Column column, String what) {
        if (needed < 0 || bytes.remaining() < needed) {
            String colName = column != null ? " for column '" + column.name() + "'" : "";
            throw new IllegalArgumentException(
                    "truncated "
                            + what
                            + colName
                            + ": need "
                            + needed
                            + " bytes, have "
                            + bytes.remaining());
        }
    }

    private static void putInt(byte[] dst, int offset, int data) {
        for (int i = 0; i < 4; i++) {
            dst[offset + i] = (byte) ((data >>> (8 * (3 - i))) & 0xFF);
        }
    }

    private static int getInt(ByteBuffer bytes) {
        return ((bytes.get() & 0xFF) << 24)
                | ((bytes.get() & 0xFF) << 16)
                | ((bytes.get() & 0xFF) << 8)
                | (bytes.get() & 0xFF);
    }

    private static void setU16(byte[] dst, int offset, int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(
                    "Value does not fit in an unsigned 2-byte integer: " + value);
        }
        dst[offset] = (byte) (value >>> 8);
        dst[offset + 1] = (byte) value;
    }

    private static int getU16(ByteBuffer bytes, int offset) {
        return ((bytes.get(offset) & 0xFF) << 8) | (bytes.get(offset + 1) & 0xFF);
    }

    private static int getU16(ByteBuffer bytes) {
        return ((bytes.get() & 0xFF) << 8) | (bytes.get() & 0xFF);
    }

    private static int addExact(int a, int b, String what) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(what + " is too large", e);
        }
    }

    private static int multiplyExact(int a, int b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Record layout is too large", e);
        }
    }
}
