package org.rafendb.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.api.processor.Session;
import org.rafendb.common.Component;
import org.rafendb.common.query.QueryResult;

/** Starting and closing the engine on a temporary data directory. */
@Timeout(60)
class EngineTest {
    @TempDir Path dir;

    @Test
    void seededDataAndCommittedWritesSurviveARestart() throws Exception {
        try (Engine engine = Engine.start(config().withSeed(true))) {
            Session session = engine.processor().openSession();
            assertEquals(60, rows(session, "SELECT * FROM customers"));
            session.execute("INSERT INTO customers VALUES (61, 'New', 1.5, 'A')");
            session.close();
        }

        try (Engine engine = Engine.start(config())) {
            assertEquals(61, rows(engine.processor().openSession(), "SELECT * FROM customers"));
            assertEquals(Path.of(dir.toString()), engine.dataDirectory().orElseThrow());
        }
    }

    @Test
    void theDataDirectoryIsExclusiveUntilClosed() throws Exception {
        Engine first = Engine.start(config());

        assertThrows(Engine.StartupException.class, () -> Engine.start(config()));

        first.close();
        Engine.start(config()).close();
    }

    @ParameterizedTest(name = "--mock={0}")
    @CsvSource({
        "qp, SELECT * FROM customers, -1",
        "sm, SELECT * FROM prodi, 3",
        "frm, SELECT * FROM customers, 60",
        "ccm, SELECT * FROM customers, 60",
        "'sm,frm,ccm', SELECT * FROM prodi, 3"
    })
    void mocksAnswerQueriesWithoutTouchingTheDataDirectory(String mocks, String sql, int expected)
            throws Exception {
        Path data = dir.resolve("data");
        Path used;
        try (Engine engine =
                Engine.start(
                        EngineConfig.fromFlags(Flags.parse(new String[] {"--mock=" + mocks}))
                                .withDir(data)
                                .withSeed(true))) {
            int count = rows(engine.processor().openSession(), sql);
            if (expected >= 0) {
                assertEquals(expected, count);
            } else {
                assertTrue(count > 0);
            }
            used = engine.dataDirectory().orElse(null);
        }

        assertFalse(Files.exists(data), "--dir must not be created");
        if (used != null) {
            assertFalse(Files.exists(used), "the temporary directory is deleted on close");
        }
    }

    @ParameterizedTest
    @CsvSource({"--mock=qo", "--mock=all", "--mock=xx", "--protocol=x", "--frames=0"})
    void invalidFlagsAreRejected(String flag) {
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineConfig.fromFlags(Flags.parse(new String[] {flag})));
    }

    @Test
    void flagsOverrideTheDefaults() {
        EngineConfig config =
                EngineConfig.fromFlags(
                        Flags.parse(
                                new String[] {
                                    "--dir=elsewhere",
                                    "--mock=SM, frm",
                                    "--protocol=timestamp",
                                    "--frames=8",
                                    "--seed"
                                }));

        assertEquals(
                new EngineConfig(
                        Path.of("elsewhere"),
                        Set.of(Component.SM, Component.FRM),
                        ProtocolType.TIMESTAMP_BASED,
                        8,
                        true,
                        EngineConfig.defaults().drainTimeout()),
                config);
    }

    private EngineConfig config() {
        return EngineConfig.defaults().withDir(dir).withFrames(32);
    }

    private static int rows(Session session, String sql) {
        return ((QueryResult.Rows) session.execute(sql)).rows().size();
    }
}
