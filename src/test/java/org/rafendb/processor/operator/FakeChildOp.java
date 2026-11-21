package org.rafendb.processor.operator;

import java.util.List;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/** Operator palsu buat test: ngeluarin baris dari list, nyatet panggilan open/close/next. */
public final class FakeChildOp implements Operator {
    private final List<ColumnSchema> schema;
    private final List<ResultRow> rows;
    private int position;
    private boolean open;
    private boolean failNextOpen;
    private int openCount;
    private int closeCount;
    private int nextCount;

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
        openCount++;
        if (failNextOpen) {
            failNextOpen = false;
            open = false;
            throw new RuntimeException("child open failed");
        }
        position = 0;
        open = true;
    }

    @Override
    public List<ColumnSchema> schema() {
        return schema;
    }

    @Override
    public ResultRow next() {
        nextCount++;
        if (!open) {
            throw new IllegalStateException("FakeChildOp is not open");
        }
        return position < rows.size() ? rows.get(position++) : null;
    }

    @Override
    public void close() {
        open = false;
        closeCount++;
    }

    /** Berapa kali open() dipanggil, termasuk yang gagal. */
    public int getOpenCount() {
        return openCount;
    }

    /** Berapa kali close() dipanggil. */
    public int getCloseCount() {
        return closeCount;
    }

    /** Berapa kali next() dipanggil. */
    public int getNextCount() {
        return nextCount;
    }
}
