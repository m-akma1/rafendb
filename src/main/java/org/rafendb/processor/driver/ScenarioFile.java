package org.rafendb.processor.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.driver.DriverException;

/**
 * Reads a QP driver scenario file ({@code .test}), in the sqllogictest format. Records are
 * separated by blank lines; a line starting with {@code #} is a comment. A record is one of:
 *
 * <pre>
 * statement ok          the SQL must succeed
 * statement count N     the SQL must report N affected rows
 * statement error       the SQL must fail with a user error
 * query [rowsort]       the SQL must return exactly the rows after "----", one per line, cells
 *                       separated by " | "; rowsort compares them in any order; no "----" means
 *                       no rows
 * </pre>
 *
 * The SQL follows the header line and may span several lines; a trailing {@code ;} is dropped.
 */
final class ScenarioFile {
    private ScenarioFile() {}

    /** What a record expects. */
    enum Kind {
        OK,
        COUNT,
        ERROR,
        QUERY
    }

    /**
     * One record of a scenario file.
     *
     * @param kind what the record checks
     * @param sql the statement, without a trailing {@code ;}
     * @param count the expected affected rows, for {@link Kind#COUNT}
     * @param rowsort whether the rows of a {@link Kind#QUERY} are compared in any order
     * @param expected the expected rows of a {@link Kind#QUERY}, cells joined with {@code " | "}
     * @param line the line number of the record's header, from 1
     */
    record Record(
            Kind kind, String sql, long count, boolean rowsort, List<String> expected, int line) {
        Record {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sql, "sql");
            expected = List.copyOf(expected);
        }
    }

    /**
     * Reads and parses a file.
     *
     * @param file the scenario file
     * @return its records, in file order
     * @throws IOException if the file cannot be read
     * @throws DriverException if a record is malformed; the message names the file and line
     */
    static List<Record> read(Path file) throws IOException {
        return parse(file.getFileName().toString(), Files.readAllLines(file));
    }

    /**
     * Parses the lines of a scenario.
     *
     * @param name the file name, for error messages
     * @param lines the lines of the file
     * @return the records, in order
     * @throws DriverException if a record is malformed
     */
    static List<Record> parse(String name, List<String> lines) {
        List<Record> records = new ArrayList<>();
        List<String> block = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= lines.size(); i++) {
            String line = i < lines.size() ? lines.get(i).strip() : "";
            if (line.startsWith("#")) {
                continue;
            }
            if (line.isEmpty()) {
                if (!block.isEmpty()) {
                    records.add(record(name, block, start));
                    block.clear();
                }
                continue;
            }
            if (block.isEmpty()) {
                start = i + 1;
            }
            block.add(line);
        }
        return records;
    }

    private static Record record(String name, List<String> block, int line) {
        String[] header = block.getFirst().split("\\s+");
        int separator = block.indexOf("----");
        List<String> sqlLines = block.subList(1, separator < 0 ? block.size() : separator);
        List<String> rows = separator < 0 ? List.of() : block.subList(separator + 1, block.size());
        String sql = String.join("\n", sqlLines).strip();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).strip();
        }
        if (sql.isEmpty()) {
            throw malformed(name, line, "the record has no SQL");
        }
        switch (header[0]) {
            case "statement" -> {
                if (separator >= 0) {
                    throw malformed(name, line, "a statement has no expected rows");
                }
                if (header.length == 2 && header[1].equals("ok")) {
                    return new Record(Kind.OK, sql, 0, false, List.of(), line);
                }
                if (header.length == 2 && header[1].equals("error")) {
                    return new Record(Kind.ERROR, sql, 0, false, List.of(), line);
                }
                if (header.length == 3 && header[1].equals("count")) {
                    return new Record(
                            Kind.COUNT, sql, count(name, line, header[2]), false, List.of(), line);
                }
                throw malformed(
                        name, line, "expected statement ok, statement error or statement count N");
            }
            case "query" -> {
                boolean rowsort = header.length == 2 && header[1].equals("rowsort");
                if (header.length > 2 || header.length == 2 && !rowsort) {
                    throw malformed(name, line, "expected query or query rowsort");
                }
                return new Record(
                        Kind.QUERY,
                        sql,
                        0,
                        rowsort,
                        rows.stream().map(ScenarioFile::normalize).toList(),
                        line);
            }
            default ->
                    throw malformed(
                            name,
                            line,
                            "a record starts with statement or query, not " + header[0]);
        }
    }

    /**
     * Joins the cells of one row the way the driver compares them: each cell trimmed, separated by
     * {@code " | "}.
     *
     * @param row a row as written in the file
     * @return the row in compared form
     */
    static String normalize(String row) {
        String[] cells = row.split("\\|", -1);
        List<String> trimmed = new ArrayList<>(cells.length);
        for (String cell : cells) {
            trimmed.add(cell.strip());
        }
        return String.join(" | ", trimmed);
    }

    private static long count(String name, int line, String text) {
        try {
            long count = Long.parseLong(text);
            if (count >= 0) {
                return count;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw malformed(name, line, "statement count needs a non-negative number, not " + text);
    }

    private static DriverException malformed(String name, int line, String message) {
        return new DriverException(name + ":" + line + ": " + message);
    }
}
