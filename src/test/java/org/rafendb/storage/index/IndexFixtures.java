package org.rafendb.storage.index;

import org.rafendb.common.schema.Column;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/** Short factories for values, Rids and nullable columns, shared by the index tests. */
public final class IndexFixtures {

    private IndexFixtures() {}

    /**
     * Returns an INTEGER value.
     *
     * @param v the number
     * @return the value
     */
    public static Value i(int v) {
        return Value.ofInt(v);
    }

    /**
     * Returns a FLOAT value.
     *
     * @param v the number
     * @return the value
     */
    public static Value f(float v) {
        return Value.ofFloat(v);
    }

    /**
     * Returns a VARCHAR value.
     *
     * @param v the text
     * @return the value
     */
    public static Value s(String v) {
        return Value.ofText(DataType.VARCHAR, v);
    }

    /**
     * Returns a CHAR value.
     *
     * @param v the text
     * @return the value
     */
    public static Value ch(String v) {
        return Value.ofText(DataType.CHAR, v);
    }

    /**
     * Returns SQL NULL of a type.
     *
     * @param t the type
     * @return the NULL value
     */
    public static Value nul(DataType t) {
        return Value.nullOf(t);
    }

    /**
     * Returns a Rid.
     *
     * @param block the block number
     * @param slot the slot number
     * @return the Rid
     */
    public static Rid rid(long block, int slot) {
        return new Rid(block, slot);
    }

    /**
     * Returns a nullable INTEGER column.
     *
     * @param n the column name
     * @return the column
     */
    public static Column intCol(String n) {
        return new Column(n, DataType.INTEGER, 0, true, false);
    }

    /**
     * Returns a nullable FLOAT column.
     *
     * @param n the column name
     * @return the column
     */
    public static Column floatCol(String n) {
        return new Column(n, DataType.FLOAT, 0, true, false);
    }

    /**
     * Returns a nullable CHAR column.
     *
     * @param n the column name
     * @param len the length in UTF-8 bytes
     * @return the column
     */
    public static Column charCol(String n, int len) {
        return new Column(n, DataType.CHAR, len, true, false);
    }

    /**
     * Returns a nullable VARCHAR column.
     *
     * @param n the column name
     * @param len the maximum length in UTF-8 bytes
     * @return the column
     */
    public static Column varcharCol(String n, int len) {
        return new Column(n, DataType.VARCHAR, len, true, false);
    }
}
