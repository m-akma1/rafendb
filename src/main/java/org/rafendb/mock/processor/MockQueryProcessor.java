package org.rafendb.mock.processor;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.rafendb.api.processor.QueryProcessor;
import org.rafendb.api.processor.Session;
import org.rafendb.common.error.SyntaxException;
import org.rafendb.common.query.QueryResult;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/**
 * QP dummy with no dependencies: each session returns a canned result chosen by the statement's
 * first keyword (case-insensitive). Nothing is parsed, stored, locked or logged.
 *
 * <ul>
 *   <li>{@code SELECT}: {@link #SAMPLE_ROWS}, two rows of {@code (id, name)}.
 *   <li>{@code INSERT}, {@code UPDATE}, {@code DELETE}: {@code Affected(1)}.
 *   <li>{@code BEGIN}, {@code COMMIT}, {@code ABORT}: a {@code Status} naming the command.
 *   <li>{@code CREATE}: {@code Status("Index created")}.
 *   <li>Anything else, including an empty statement: throws {@link SyntaxException}.
 * </ul>
 *
 * <p>Thread-safe; each session is stateless apart from being open or closed.
 */
public final class MockQueryProcessor implements QueryProcessor {

    /** The result every {@code SELECT} returns. */
    public static final QueryResult.Rows SAMPLE_ROWS =
            new QueryResult.Rows(
                    List.of("id", "name"),
                    List.of(
                            new Row(
                                    List.of(
                                            Value.ofInt(1),
                                            Value.ofText(DataType.VARCHAR, "Alice"))),
                            new Row(
                                    List.of(
                                            Value.ofInt(2),
                                            Value.ofText(DataType.VARCHAR, "Bob")))));

    /** {@inheritDoc} */
    @Override
    public Session openSession() {
        return new MockSession();
    }

    /** A session that only tracks whether it is closed. */
    private static final class MockSession implements Session {
        private volatile boolean closed;

        /** {@inheritDoc} Returns a canned result; see {@link MockQueryProcessor}. */
        @Override
        public QueryResult execute(String sql) {
            Objects.requireNonNull(sql, "sql");
            if (closed) {
                throw new IllegalStateException("session is closed");
            }
            String keyword = firstKeyword(sql);
            return switch (keyword) {
                case "SELECT" -> SAMPLE_ROWS;
                case "INSERT", "UPDATE", "DELETE" -> new QueryResult.Affected(1);
                case "BEGIN" -> new QueryResult.Status("Transaction started");
                case "COMMIT" -> new QueryResult.Status("Transaction committed");
                case "ABORT" -> new QueryResult.Status("Transaction aborted");
                case "CREATE" -> new QueryResult.Status("Index created");
                default -> throw new SyntaxException("Unrecognized statement: '" + keyword + "'");
            };
        }

        /** {@inheritDoc} Only marks the session closed. */
        @Override
        public void close() {
            closed = true;
        }

        /** Returns the first word of the statement in upper case, or "" if there is none. */
        private static String firstKeyword(String sql) {
            String text = sql.strip();
            if (text.endsWith(";")) {
                text = text.substring(0, text.length() - 1).strip();
            }
            return text.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        }
    }
}
