package org.rafendb.mock.processor;

import java.util.List;
import org.rafendb.api.processor.Operator;
import org.rafendb.common.expr.ColumnRef;
import org.rafendb.common.tuple.Row;

/** In-memory fixture cursor for QP4 integration; does not implement scan, join, or sort. */
public final class MockOperator implements Operator {
    private final List<ColumnRef> schema;
    private final List<Row> rows;
    private int position;
    private boolean opened;

    /** Copies fixtures and rejects rows whose ordered columns differ from the schema. */
    public MockOperator(List<ColumnRef> schema, List<Row> rows) {
        this.schema = List.copyOf(schema);
        this.rows = List.copyOf(rows);
        for (Row row : this.rows) {
            if (!row.columns().equals(this.schema)) {
                throw new IllegalArgumentException("Row columns differ from operator schema");
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<ColumnRef> schema() {
        return schema;
    }

    /** {@inheritDoc} */
    @Override
    public void open() {
        position = 0;
        opened = true;
    }

    /** {@inheritDoc} */
    @Override
    public Row next() {
        if (!opened) throw new IllegalStateException("Cursor is not open");
        return position < rows.size() ? rows.get(position++) : null;
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        opened = false;
    }
}
