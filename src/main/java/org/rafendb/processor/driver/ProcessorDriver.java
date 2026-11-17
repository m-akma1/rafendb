package org.rafendb.processor.driver;

import java.util.List;
import org.rafendb.api.processor.Session;
import org.rafendb.common.Component;
import org.rafendb.common.error.RafenDBRuntimeException;
import org.rafendb.common.query.QueryResult;
import org.rafendb.driver.Driver;
import org.rafendb.driver.DriverException;
import org.rafendb.engine.Engine;
import org.rafendb.server.ResultRenderer;

/**
 * Driver for the Query Processor (QP-14). Lines that are not commands are run as SQL on one
 * session, and {@code count} and {@code fails} check results for test files.
 *
 * <p>The session comes from {@link Engine}, the same source as the server. There is no real Query
 * Processor yet, so for now this is the mock processor.
 */
public final class ProcessorDriver extends Driver {
    private Session session;

    /**
     * Starts the driver.
     *
     * @param args the command-line flags
     */
    public static void main(String[] args) {
        new ProcessorDriver().launch(args);
    }

    ProcessorDriver() {
        super(Component.QP);
    }

    @Override
    protected void setup() {
        session = Engine.withMocks().processor().openSession();
        command(
                "count",
                "count <n> <sql>",
                "run sql and check how many rows it returned or affected",
                this::count);
        command("fails", "fails <sql>", "run sql and check that it is rejected", this::fails);
    }

    /** Runs a plain SQL line and prints its result. */
    @Override
    protected void fallback(String sql) {
        out().println(ResultRenderer.render(session.execute(sql)));
    }

    @Override
    protected void help() {
        super.help();
        out().println("Any other line is run as SQL.");
        out().println("The processor is still the mock from Engine; no real Query Processor yet.");
    }

    @Override
    protected void close() {
        if (session != null) {
            session.close();
        }
    }

    private void count(List<String> args, String rest) {
        long expected;
        try {
            expected = Long.parseLong(args.getFirst());
        } catch (RuntimeException e) {
            throw new DriverException("usage: count <n> <sql>");
        }
        String sql = rest.substring(args.getFirst().length()).strip();
        if (sql.isEmpty()) {
            throw new DriverException("usage: count <n> <sql>");
        }
        long actual =
                switch (session.execute(sql)) {
                    case QueryResult.Rows rows -> rows.rows().size();
                    case QueryResult.Affected affected -> affected.count();
                    case QueryResult.Status status ->
                            throw new DriverException(sql + " returned a status, not a count");
                };
        check("count of " + sql, expected, actual);
    }

    private void fails(List<String> args, String rest) {
        if (rest.isEmpty()) {
            throw new DriverException("usage: fails <sql>");
        }
        boolean rejected;
        try {
            session.execute(rest);
            rejected = false;
        } catch (RafenDBRuntimeException e) {
            rejected = true;
        }
        check("rejected: " + rest, rejected);
    }
}
