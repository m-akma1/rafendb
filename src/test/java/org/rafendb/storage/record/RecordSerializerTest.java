package org.rafendb.storage.record;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/** Regression checks for text images shared by storage and recovery. */
class RecordSerializerTest {
    private final RecordSerializer serializer = new RecordSerializer();

    @Test
    void existingCharAndVarcharLayoutRemainsReadable() {
        TableSchema schema =
                new TableSchema(
                        "t",
                        List.of(
                                new Column("code", DataType.CHAR, 4, false, false),
                                new Column("name", DataType.VARCHAR, 8, false, false)));
        byte[] legacy = {0, 0, 7, 'I', 'F', 0, 0, 0, 2, 'h', 'i'};
        Row expected =
                new Row(
                        List.of(
                                Value.ofText(DataType.CHAR, "IF"),
                                Value.ofText(DataType.VARCHAR, "hi")));
        assertEquals(expected, serializer.deserialize(ByteBuffer.wrap(legacy), schema));
        assertArrayEquals(legacy, serializer.serialize(expected, schema));
    }

    @Test
    void textFamilyAndNullValidationFollowsTheColumnSchema() {
        TableSchema schema =
                new TableSchema(
                        "t",
                        List.of(
                                new Column("code", DataType.CHAR, 4, false, false),
                                new Column("name", DataType.VARCHAR, 8, true, false)));
        Row input =
                new Row(
                        List.of(
                                Value.ofText(DataType.VARCHAR, "IF"),
                                Value.nullOf(DataType.INTEGER)));
        Row expected =
                new Row(List.of(Value.ofText(DataType.CHAR, "IF"), Value.nullOf(DataType.VARCHAR)));
        byte[] image = serializer.serialize(input, schema);
        assertEquals(image.length, serializer.encodedSize(input, schema));
        assertEquals(expected, serializer.deserialize(ByteBuffer.wrap(image), schema));
    }

    @Test
    void unrepresentableTextIsRejectedInsteadOfSilentlyChanged() {
        Column code = new Column("code", DataType.CHAR, 8, false, false);
        Column text = new Column("text", DataType.VARCHAR, 8, false, false);
        assertThrows(
                IllegalArgumentException.class,
                () -> serializer.encodeValue(Value.ofText(DataType.CHAR, "A\0"), code));
        assertThrows(
                IllegalArgumentException.class,
                () -> serializer.encodeValue(Value.ofText(DataType.CHAR, "\0"), code));
        assertThrows(
                IllegalArgumentException.class,
                () -> serializer.encodeValue(Value.ofText(DataType.CHAR, "\uD800"), code));
        assertThrows(
                IllegalArgumentException.class,
                () -> serializer.encodeValue(Value.ofText(DataType.VARCHAR, "\uDC00"), text));
        assertEquals(
                Value.ofText(DataType.CHAR, "A\0B"),
                serializer.decodeValue(
                        ByteBuffer.wrap(
                                serializer.encodeValue(Value.ofText(DataType.CHAR, "A\0B"), code)),
                        code));
        assertEquals(
                Value.ofText(DataType.VARCHAR, "A\0"),
                serializer.decodeValue(
                        ByteBuffer.wrap(
                                serializer.encodeValue(
                                        Value.ofText(DataType.VARCHAR, "A\0"), text)),
                        text));
    }
}
