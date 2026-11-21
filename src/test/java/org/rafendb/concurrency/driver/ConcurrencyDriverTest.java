package org.rafendb.concurrency.driver;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConcurrencyDriverTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "begin T1\nwrite T1 a 1\n",
                "begin T1\ncommit T1\n",
                "begin T1\ncommit T1\nreset\nverify serializable\n"
            })
    void bothFileModesCountAnUnverifiedScenarioBeforeTheSummary(String input) throws Exception {
        final String message = "FAIL finish all transactions and run verify";
        for (boolean interactive : List.of(false, true)) {
            var result = runFile(input, interactive);
            String output = result.output();

            assertEquals(1, result.exitCode(), output);
            int failure = output.indexOf(message);
            assertTrue(failure >= 0 && failure < output.indexOf("RESULT:"), output);
            assertTrue(
                    output.lines()
                            .anyMatch(line -> line.matches("RESULT: [0-9]+ passed, 1 failed")),
                    output);
        }
    }

    @Test
    void mockedBatchRestoresAnUpdateAndRemovesAnAbortedZeroInsert() throws Exception {
        var result =
                runFile(
                        """
                        begin T1
                        write T1 a 17
                        commit T1
                        begin T2
                        write T2 a 24
                        write T2 b 0
                        abort T2
                        assert value a 17
                        verify serializable
                        """,
                        false);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("actual Optional[17]"), result.output());
        assertTrue(result.output().contains("actual Optional.empty"), result.output());
    }

    private RunResult runFile(String input, boolean interactive) throws Exception {
        Path script = Files.writeString(directory.resolve("scenario.txt"), input);
        String[] args =
                interactive
                        ? new String[] {"--mock=sm,frm"}
                        : new String[] {"--mock=sm,frm", "--run=" + script};
        String commands = interactive ? "run \"" + script + "\"\nquit\n" : "";
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, UTF_8)) {
            int exitCode =
                    new ConcurrencyDriver()
                            .run(args, new ByteArrayInputStream(commands.getBytes(UTF_8)), out);
            return new RunResult(exitCode, bytes.toString(UTF_8));
        }
    }

    private record RunResult(int exitCode, String output) {}
}
