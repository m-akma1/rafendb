package org.rafendb.api.storage;

import java.nio.ByteBuffer;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.Value;

/** To serializes and deserializes a data row in an SQL table
 *
 */
public interface RecordSerializer {
    /**
     * Returns bytes representation of an SQL value that follows the column rule.
     *
     * @param value an SQL value
     * @param column column definition the value resides
     * @return byte[] representation of the value
     */
    public byte[] encodeValue(Value value, Column column);

    /**
     * Returns an SQL value from bytes representation that follows the column rule.
     *
     * @param bytes bytes representation of the value
     * @param column column definition the value resides
     * @return an SQL value the bytes represent
     */
    public Value decodeValue(ByteBuffer bytes, Column column);

    /**
     * Returns bytes representation of a row according to the table scheme.
     *
     * @param row a row of data in a table
     * @param column table scheme the row resides
     * @return byte[] representation of the row
     */
    public byte[] serialize(Row row, TableSchema schema);

    /**
     * Returns a table row from bytes representation according to the table scheme.
     *
     * @param bytes bytes representation of the row
     * @param column column definition the row resides
     * @return a table row the bytes represent
     */
    public Row deserialize(ByteBuffer bytes, TableSchema schema);

    /**
     * Returns the exact size of bytes that represents the row when serialized.
     *
     * @param row a row of data in a table
     * @param column table scheme the row resides
     * @return size of bytes of a row when serialized
     */
    public int encodedSize(Row row, TableSchema schema);
}
