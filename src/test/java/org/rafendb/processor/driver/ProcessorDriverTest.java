package org.rafendb.processor.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Runs the QP driver against the Engine's mock processor. Assertions use exit codes and data from
 * the processor, never the driver's wording.
 */
class ProcessorDriverTest {
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

    @Test
    void scenarioFilePasses() {
        assertEquals(0, run("", "--run=test-scenarios/processor/basic.txt"), output);
    }

    @Test
    void sqlLinesPrintTheirResult() {
        assertEquals(0, run("SELECT * FROM student\n"), output);
        assertTrue(output.contains("Alice"), output);
        assertTrue(output.contains("Bob"), output);
    }

    @Test
    void rejectedSqlFailsAndTheDriverGoesOn() {
        assertEquals(1, run("SELEC * FROM student\nSELECT * FROM student\n"));
        assertTrue(output.contains("Alice"), "the next line still ran");
    }

    @Test
    void countPassesOnlyOnTheRightNumber() {
        assertEquals(0, run("count 2 SELECT * FROM student\ncount 1 DELETE FROM student\n"));
        assertEquals(1, run("count 5 SELECT * FROM student\n"));
    }

    @Test
    void countRejectsBadInput() {
        for (String line :
                new String[] {
                    "count", "count 2", "count x SELECT * FROM student", "count 1 BEGIN"
                }) {
            assertEquals(1, run(line + "\n"), line);
        }
    }

    @Test
    void failsPassesOnlyWhenTheSqlIsRejected() {
        assertEquals(0, run("fails SELEC * FROM student\n"));
        assertEquals(1, run("fails SELECT * FROM student\n"));
        assertEquals(1, run("fails\n"));
    }

    @Test
    void helpWorksAndQpCannotBeMocked() {
        assertEquals(0, run("help\n"));
        assertEquals(0, run("", "--help"));
        assertEquals(1, run("", "--mock=qp"));
    }
}
