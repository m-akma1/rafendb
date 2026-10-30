package org.rafendb.mock.storage;

import java.nio.ByteBuffer;

import org.rafendb.api.storage.RecordSerializer;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.Value;

/** 
 * Mock implementation of RecordSerializer returning valid dummy outputs.
 */
public class MockRecordSerializer implements RecordSerializer {
    
    @Override
    public byte[] encodeValue(Value value, Column column) {
        return new byte[0];
    }

    @Override
    public Value decodeValue(ByteBuffer bytes, Column column) {
        return new Value(column.type(), null);
    }

    @Override
    public byte[] serialize(Row row, TableSchema schema) {
        return new byte[0];
    }

    @Override
    public Row deserialize(ByteBuffer bytes, TableSchema schema) {
        return new Row(java.util.Collections.emptyList());
    }

    @Override
    public int encodedSize(Row row, TableSchema schema) {
        return 0;
    }
}