package org.rafendb.common.tuple;

import java.util.Objects;

/**
 * A record read from storage together with its address.
 *
 * <p>Immutable and thread-safe.
 *
 * @param rid the record address
 * @param row the record values
 */
public record StoredRow(Rid rid, Row row) {
    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code rid} or {@code row} is {@code null}
     */
    public StoredRow {
        Objects.requireNonNull(rid, "rid");
        Objects.requireNonNull(row, "row");
    }
}
