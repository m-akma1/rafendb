package org.rafendb.concurrency.driver;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void batchFailsWhenAScenarioEndsWithoutVerification(String input) throws Exception {
        var result = runBatch(input);

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(
                result.output().contains("FAIL finish all transactions and run verify"),
                result.output());
    }

    @Test
    void mockedBatchRestoresAnUpdateAndRemovesAnAbortedZeroInsert() throws Exception {
        var result =
                runBatch(
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
                        """);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("actual Optional[17]"), result.output());
        assertTrue(result.output().contains("actual Optional.empty"), result.output());
    }

    private RunResult runBatch(String input) throws Exception {
        Path script = Files.writeString(directory.resolve("scenario.txt"), input);
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, UTF_8)) {
            int exitCode =
                    new ConcurrencyDriver()
                            .run(
                                    new String[] {"--mock=sm,frm", "--run=" + script},
                                    new ByteArrayInputStream(new byte[0]),
                                    out);
            return new RunResult(exitCode, bytes.toString(UTF_8));
        }
    }

    private record RunResult(int exitCode, String output) {}
}
