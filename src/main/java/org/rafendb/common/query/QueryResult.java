package org.rafendb.common.query;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.tuple.Row;

/**
 * Outcome of one successfully executed SQL statement, returned by {@link
 * org.rafendb.api.processor.Session#execute(String)} (QP-01). Failures are not results: they are
 * thrown as {@link org.rafendb.common.error.RafenDBRuntimeException}.
 *
 * <p>Immutable and thread-safe.
 */
public sealed interface QueryResult {

    /**
     * Result of a SELECT: column headers and rows (QP-05). An empty result still carries its
     * headers.
     *
     * @param columns the column headers in output order, copied and unmodifiable
     * @param rows the rows in output order, copied and unmodifiable; each has one value per column
     */
    record Rows(List<String> columns, List<Row> rows) implements QueryResult {
        /**
         * Copies the lists and checks every row's width.
         *
         * @throws NullPointerException if a list or any element is {@code null}
         * @throws IllegalArgumentException if a row's size differs from the number of columns
         */
        public Rows {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
            for (Row row : rows) {
                if (row.size() != columns.size()) {
                    throw new IllegalArgumentException(
                            "row has " + row.size() + " values, expected " + columns.size());
                }
            }
        }
    }

    /**
     * Result of INSERT, UPDATE or DELETE: the number of rows changed (QP-07, QP-10, QP-11).
     *
     * @param count the number of rows changed, never negative
     */
    record Affected(long count) implements QueryResult {
        /**
         * Checks the count.
         *
         * @throws IllegalArgumentException if {@code count} is negative
         */
        public Affected {
            if (count < 0) {
                throw new IllegalArgumentException("negative count: " + count);
            }
        }
    }

    /**
     * Result of a statement that returns no rows, such as BEGIN, COMMIT, ABORT or CREATE INDEX
     * (QP-12, QP-13).
     *
     * @param message the status line shown to the user
     */
    record Status(String message) implements QueryResult {
        /**
         * Checks the message.
         *
         * @throws NullPointerException if {@code message} is {@code null}
         */
        public Status {
            Objects.requireNonNull(message, "message");
        }
    }
}
