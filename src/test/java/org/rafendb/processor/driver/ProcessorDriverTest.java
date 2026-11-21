package org.rafendb.processor.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the QP driver on the real engine with the sample database (customers, orders, order_items;
 * 60 rows each). Assertions use exit codes and the per-file pass/fail counts, never the wording of
 * a message.
 */
@Timeout(60)
class ProcessorDriverTest {
    private static final Pattern RESULT =
            Pattern.compile("RESULT \\S+: (\\d+) passed, (\\d+) failed");

    @TempDir Path dir;

    private String output = "";

    private int run(String input, String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code =
                new ProcessorDriver()
                        .run(
                                args,
                                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        output = bytes.toString(StandardCharsets.UTF_8);
        return code;
    }

    private Path scenario(String name, String text) throws IOException {
        return Files.writeString(dir.resolve(name), text);
    }

    /** The passed and failed counts of the first file result in the output. */
    private int[] counts() {
        Matcher matcher = RESULT.matcher(output);
        assertTrue(matcher.find(), output);
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    @Test
    void theScenarioFilesPass() {
        assertEquals(0, run("", "--run=test-scenarios/processor/all.txt"), output);
        assertEquals(0, run("", "--mock=sm", "--run=test-scenarios/processor/mock-sm.txt"), output);
    }

    @Test
    void everyRecordKindPassesWhenTheResultMatches() throws IOException {
        Path file =
                scenario(
                        "ok.test",
                        """
                        # the newest orders first; the order is checked
                        query
                        SELECT order_id FROM orders
                          ORDER BY order_id DESC LIMIT 3;
                        ----
                        60
                        59
                        58

                        # rowsort: written in another order, still equal
                        query rowsort
                        SELECT customer_id, name FROM customers WHERE customer_id < 3
                        ----
                        2|Customer 2
                        1 | Customer 1

                        query
                        SELECT * FROM customers WHERE customer_id = 999

                        statement count 20
                        DELETE FROM order_items WHERE gift = 'Y'

                        statement ok
                        BEGIN

                        statement error
                        SELECT * FROM nope

                        statement error
                        SELECT * FROM customers

                        statement ok
                        ABORT
                        """);

        assertEquals(0, run("test " + file + "\n"), output);
        assertEquals(8, counts()[0], output);
    }

    @Test
    void eachMismatchFailsAndTheFileGoesOn() throws IOException {
        Path file =
                scenario(
                        "bad.test",
                        """
                        query
                        SELECT order_id FROM orders ORDER BY order_id DESC LIMIT 2
                        ----
                        59
                        60

                        statement count 3
                        DELETE FROM order_items WHERE gift = 'Y'

                        statement error
                        SELECT * FROM customers

                        query
                        SELECT order_id FROM orders WHERE order_id = 1
                        ----
                        1
                        """);

        assertEquals(1, run("test " + file + "\n"), output);
        int[] counts = counts();
        assertEquals(1, counts[0], output);
        assertEquals(3, counts[1], output);
    }

    @Test
    void everyFileStartsFromTheSampleDatabaseUnlessDirIsGiven() throws IOException {
        Path file =
                scenario(
                        "insert.test",
                        """
                        statement count 1
                        INSERT INTO customers VALUES (61, 'New', 1.5, 'A')

                        query
                        SELECT customer_id FROM customers WHERE customer_id = 61
                        ----
                        61
                        """);
        String twice = "test " + file + "\ntest " + file + "\n";

        assertEquals(0, run(twice), output);

        String data = "--dir=" + dir.resolve("data");
        assertEquals(1, run(twice, data), output); // the second INSERT hits the first one's key
        assertTrue(Files.isDirectory(dir.resolve("data")));
    }

    @Test
    void sqlLinesPrintTheirResult() {
        assertEquals(0, run("SELECT name FROM customers WHERE customer_id = 7\n"), output);
        assertTrue(output.contains("Customer 7"), output);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "query sorted\nSELECT * FROM customers\n",
                "statement count x\nDELETE FROM orders\n",
                "statement ok\nBEGIN\n----\nBEGIN\n",
                "statement ok\n",
                "select * from customers\n"
            })
    void malformedFilesAreRejected(String text) throws IOException {
        assertEquals(1, run("test " + scenario("malformed.test", text) + "\n"), output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--mock=qo", "--mock=qp", "--protocol=nope", "--frames=0"})
    void badFlagsStopTheDriver(String flag) {
        assertEquals(1, run("", flag));
    }

    @Test
    void aScenarioFileIsNotRunLineByLine() throws IOException {
        Path file = scenario("plain.test", "statement ok\nBEGIN\n");
        assertEquals(1, run("", "--run=" + file));
        assertEquals(1, run("run " + file + "\n"));
        assertEquals(0, run("test " + file + "\n"), output);
    }
}
