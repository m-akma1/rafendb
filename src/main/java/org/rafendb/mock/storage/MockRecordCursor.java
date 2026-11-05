package org.rafendb.mock.storage;

import java.util.List;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.tuple.StoredRow;

/**
 * Mock {@link RecordCursor} that returns dummy data: it walks a fixed list of rows once, in list
 * order, and returns {@code null} forever after the last one. Not thread-safe, like the interface
 * it implements.
 */
class MockRecordCursor implements RecordCursor {
    private final List<StoredRow> rows;
    private int position;
    private boolean closed;

    /**
     * Creates a cursor positioned before the first row.
     *
     * @param rows the rows to return, copied
     * @throws NullPointerException if {@code rows} or an element is {@code null}
     */
    MockRecordCursor(List<StoredRow> rows) {
        this.rows = List.copyOf(rows);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mock: returns the next fixed dummy row, then {@code null} forever; never throws {@link
     * StorageInternalException}.
     */
    @Override
    public StoredRow next() throws StorageInternalException {
        if (closed) {
            throw new IllegalStateException("cursor is closed");
        }
        return position < rows.size() ? rows.get(position++) : null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mock: only marks this cursor as closed.
     */
    @Override
    public void close() {
        closed = true;
    }
}
