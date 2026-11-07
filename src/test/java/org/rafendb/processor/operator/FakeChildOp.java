package org.rafendb.processor.operator;

import java.util.List;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/** Operator palsu buat test: ngeluarin baris dari list, nyatet panggilan close/schema. */
public final class FakeChildOp implements Operator {
    private final List<ColumnSchema> schema;
    private final List<ResultRow> rows;
    private int position;
    private boolean open;
    private boolean failNextOpen;
    private int closeCount;
    private int schemaCount;

    /** Bikin operator palsu dengan schema dan baris yang tetap. */
    public FakeChildOp(List<ColumnSchema> schema, List<ResultRow> rows) {
        this.schema = schema;
        this.rows = rows;
    }

    /** Bikin open() berikutnya gagal pakai RuntimeException. */
    public void failNextOpen() {
        failNextOpen = true;
    }

    @Override
    public void open() {
        if (failNextOpen) {
            failNextOpen = false;
            open = false;
            throw new RuntimeException("Child open failed");
        }
        position = 0;
        open = true;
    }

    @Override
    public List<ColumnSchema> schema() {
        schemaCount++;
        return schema;
    }

    @Override
    public ResultRow next() {
        if (!open) {
            throw new IllegalStateException("FakeChildOp is not open");
        }
        if (position >= rows.size()) {
            return null;
        }
        ResultRow row = rows.get(position);
        position++;
        return row;
    }

    @Override
    public void close() {
        open = false;
        closeCount++;
    }

    /** Berapa kali close() dipanggil. */
    public int getCloseCount() {
        return closeCount;
    }

    /** Berapa kali schema() dipanggil. */
    public int getSchemaCount() {
        return schemaCount;
    }
}
