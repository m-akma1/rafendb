package org.rafendb.processor.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.rafendb.api.processor.Session;
import org.rafendb.common.Component;
import org.rafendb.common.error.RafenDBRuntimeException;
import org.rafendb.common.query.QueryResult;
import org.rafendb.common.tuple.Row;
import org.rafendb.driver.Driver;
import org.rafendb.driver.DriverException;
import org.rafendb.engine.Engine;
import org.rafendb.engine.EngineConfig;
import org.rafendb.engine.Flags;
import org.rafendb.server.ResultRenderer;

/**
 * Driver for the Query Processor (QP-14). It runs SQL end to end on the real engine, with SM,
 * CCM or FRM mocked when {@code --mock} says so, and checks the results.
 *
 * <p>{@code test <file>} runs a scenario file (see {@link ScenarioFile}) on a fresh engine and
 * prints PASS or FAIL for each record, then {@code RESULT} for the file. Any other line is run as
 * SQL on an interactive session and its result is printed, as the client would.
 *
 * <p>Without {@code --dir}, every scenario file and the interactive session each get their own
 * temporary data directory with the sample database, deleted afterwards, so a scenario always
 * starts from the same state. With {@code --dir}, one engine on that directory is shared by the
 * whole run; the sample database is loaded only if it is empty.
 *
 * <p>The Query Optimizer cannot be mocked: its mock only echoes the SQL and plans nothing.
 */
public final class ProcessorDriver extends Driver {
    private static final String NOT_LINE_BY_LINE =
            "a scenario file (.test) runs with: test <file>, or --run on a file of test lines such"
                    + " as test-scenarios/processor/all.txt";

    private EngineConfig config;
    private Path dataDir;
    private Engine shared;
    private Path sharedTemp;
    private Session interactive;

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
        if (mocked(Component.QO)) {
            throw new DriverException(
                    "the Query Optimizer cannot be mocked: its mock plans nothing (use sm, ccm, frm)");
        }
        List<String> flags = new ArrayList<>(List.of("--seed"));
        String dir = option("dir");
        if (dir != null) {
            dataDir = Path.of(dir);
            flags.add("--dir=" + dir);
        }
        for (String name : List.of("protocol", "frames")) {
            String value = option(name);
            if (value != null) {
                flags.add("--" + name + "=" + value);
            }
        }
        List<String> mocks = new ArrayList<>();
        for (Component mock : List.of(Component.SM, Component.CCM, Component.FRM)) {
            if (mocked(mock)) {
                mocks.add(mock.shortName());
            }
        }
        if (!mocks.isEmpty()) {
            flags.add("--mock=" + String.join(",", mocks));
        }
        try {
            config = EngineConfig.fromFlags(Flags.parse(flags.toArray(String[]::new)));
        } catch (IllegalArgumentException e) {
            throw new DriverException(e.getMessage());
        }
        // The kit's --run and run read a file line by line, which would run every header and
        // expected row as SQL; a scenario file needs test <file>.
        String run = option("run");
        if (run != null && run.endsWith(".test")) {
            throw new DriverException(NOT_LINE_BY_LINE);
        }
        command(
                "test",
                "test <file>",
                "run a scenario file on a fresh engine and check every record",
                (args, rest) -> test(Path.of(rest)));
        for (String header : List.of("query", "statement")) {
            command(
                    header,
                    header + " ...",
                    "only inside a scenario file",
                    (args, rest) -> {
                        throw new DriverException(NOT_LINE_BY_LINE);
                    });
        }
    }

    /** Runs a line that is no command as SQL on the interactive session and prints its result. */
    @Override
    protected void fallback(String sql) throws Exception {
        if (interactive == null) {
            interactive = engine().processor().openSession();
        }
        out().println(ResultRenderer.render(interactive.execute(sql)));
    }

    @Override
    protected void help() {
        super.help();
        out().println("Options:");
        out().println("  --dir=<path>        run on this data directory instead of a fresh one");
        out().println("  --protocol=<name>   concurrency protocol, as for the server");
        out().println("  --frames=<n>        SM buffer pool size, as for the server");
        out().println("Any other line is run as SQL and its result printed.");
        out().println("Scenario files: statement ok | statement error | statement count N |");
        out().println("query [rowsort] ... ---- expected rows, cells separated by \" | \".");
    }

    @Override
    protected void close() throws Exception {
        if (interactive != null) {
            interactive.close();
        }
        if (shared != null) {
            shared.close();
            delete(sharedTemp);
        }
    }

    /** Runs every record of a scenario file and prints the file's result. */
    private void test(Path file) throws Exception {
        if (file.toString().isEmpty()) {
            throw new DriverException("usage: test <file>");
        }
        List<ScenarioFile.Record> records = ScenarioFile.read(file);
        String name = file.getFileName().toString();
        int passed = 0;
        Path temp = dataDir == null ? Files.createTempDirectory("rafendb-qp-driver-") : null;
        Engine engine = temp == null ? engine() : Engine.start(config.withDir(temp));
        try (Session session = engine.processor().openSession()) {
            for (ScenarioFile.Record record : records) {
                String failure = run(session, record);
                if (check(
                        name + ":" + record.line() + " " + firstLine(record.sql()),
                        failure == null)) {
                    passed++;
                } else {
                    out().println(failure);
                }
            }
        } finally {
            if (temp != null) {
                engine.close();
                delete(temp);
            }
        }
        out().println(
                        "RESULT "
                                + name
                                + ": "
                                + passed
                                + " passed, "
                                + (records.size() - passed)
                                + " failed");
    }

    /**
     * Runs one record.
     *
     * @return null if it passed, otherwise why it failed, indented for printing under the FAIL line
     */
    private static String run(Session session, ScenarioFile.Record record) {
        QueryResult result;
        try {
            result = session.execute(record.sql());
        } catch (RafenDBRuntimeException e) {
            return record.kind() == ScenarioFile.Kind.ERROR ? null : "  error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "  internal error: " + e; // never what statement error expects
        }
        return switch (record.kind()) {
            case OK -> null;
            case ERROR -> "  expected an error, but it succeeded: " + summary(result);
            case COUNT ->
                    result instanceof QueryResult.Affected affected
                                    && affected.count() == record.count()
                            ? null
                            : "  expected "
                                    + record.count()
                                    + " affected rows, actual: "
                                    + summary(result);
            case QUERY -> {
                if (!(result instanceof QueryResult.Rows rows)) {
                    yield "  expected rows, actual: " + summary(result);
                }
                List<String> actual = lines(rows);
                List<String> expected = record.expected();
                if (record.rowsort()) {
                    actual = actual.stream().sorted().toList();
                    expected = expected.stream().sorted().toList();
                }
                yield actual.equals(expected)
                        ? null
                        : "  expected:\n" + indent(expected) + "\n  actual:\n" + indent(actual);
            }
        };
    }

    /** Each row as its cells, formatted like the client, joined with {@code " | "}. */
    private static List<String> lines(QueryResult.Rows rows) {
        List<String> lines = new ArrayList<>(rows.rows().size());
        for (Row row : rows.rows()) {
            lines.add(String.join(" | ", row.values().stream().map(ResultRenderer::cell).toList()));
        }
        return lines;
    }

    private static String summary(QueryResult result) {
        return switch (result) {
            case QueryResult.Rows rows -> rows.rows().size() + " rows";
            case QueryResult.Affected affected -> affected.count() + " affected rows";
            case QueryResult.Status status -> status.message();
        };
    }

    private static String indent(List<String> lines) {
        return lines.isEmpty() ? "    (no rows)" : "    " + String.join("\n    ", lines);
    }

    private static String firstLine(String sql) {
        int end = sql.indexOf('\n');
        return end < 0 ? sql : sql.substring(0, end) + " ...";
    }

    /** The engine shared by the interactive session, and by every file when {@code --dir} is set. */
    private Engine engine() throws Exception {
        if (shared == null) {
            if (dataDir == null) {
                sharedTemp = Files.createTempDirectory("rafendb-qp-driver-");
                shared = Engine.start(config.withDir(sharedTemp));
            } else {
                shared = Engine.start(config);
            }
        }
        return shared;
    }

    private static void delete(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
