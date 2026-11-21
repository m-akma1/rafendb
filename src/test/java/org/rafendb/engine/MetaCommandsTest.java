package org.rafendb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.rafendb.api.processor.Session;
import org.rafendb.common.error.GeneralRuntimeException;
import org.rafendb.common.tuple.Row;
import org.rafendb.storage.seed.SampleDatabaseLoader;

/** Meta-commands over a seeded engine on a temporary data directory. */
@Timeout(60)
class MetaCommandsTest {
    @TempDir Path dir;
    private Engine engine;
    private MetaCommands meta;

    @BeforeEach
    void start() throws Exception {
        engine = Engine.start(EngineConfig.defaults().withDir(dir).withFrames(32).withSeed(true));
        meta = engine.metaCommands();
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    @Test
    void listsTheTables() {
        List<String> sorted = SampleDatabaseLoader.seededTables().stream().sorted().toList();

        assertEquals(sorted, column(meta.run("\\dt").getFirst(), 0));
        assertEquals(sorted, column(meta.run("\\d").getFirst(), 0));
    }

    @Test
    void describeAndIndexListShowANewIndex() {
        Session session = engine.processor().openSession();
        assertFalse(indexNames("\\di customers").contains("customers_status_idx"));

        session.execute("CREATE INDEX customers_status_idx ON customers(status) USING HASH");

        assertTrue(indexNames("\\di customers").contains("customers_status_idx"));
        assertTrue(indexNames("\\di").contains("customers_status_idx"));
        assertFalse(indexNames("\\di orders").contains("customers_status_idx"));
        List<MetaCommands.Section> described = meta.run("\\d customers");
        assertEquals(
                List.of("customer_id", "name", "credit", "status"),
                column(described.getFirst(), 0));
        assertTrue(
                described.stream().anyMatch(s -> column(s, 0).contains("customers_status_idx")),
                "the index is listed under the table");
    }

    @Test
    void statisticsFollowCommittedWrites() {
        long before = recordCount();

        engine.processor()
                .openSession()
                .execute("INSERT INTO customers VALUES (61, 'New', 1.5, 'A')");

        assertEquals(before + 1, recordCount());
        List<MetaCommands.Section> stats = meta.run("\\stats customers");
        assertEquals(2, stats.size(), "one table row, then V(A,r) per column");
        assertEquals(column(meta.run("\\d customers").getFirst(), 0), column(stats.get(1), 0));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\\d nope",
                "\\di nope",
                "\\stats nope",
                "\\dt customers",
                "\\d customers orders",
                "\\nope"
            })
    void badCommandsAreRejected(String line) {
        assertThrows(GeneralRuntimeException.class, () -> meta.run(line));
    }

    @Test
    void withoutStorageEveryCommandFails() throws Exception {
        try (Engine mock =
                Engine.start(
                        EngineConfig.fromFlags(Flags.parse(new String[] {"--mock=qp"}))
                                .withDir(dir.resolve("unused")))) {
            assertThrows(GeneralRuntimeException.class, () -> mock.metaCommands().run("\\dt"));
        }
    }

    private long recordCount() {
        MetaCommands.Section row = meta.run("\\stats customers").getFirst();
        assertEquals("n_r", row.rows().columns().get(1));
        return Long.parseLong(row.rows().rows().getFirst().get(1).asText());
    }

    private List<String> indexNames(String line) {
        return column(meta.run(line).getFirst(), 0);
    }

    private static List<String> column(MetaCommands.Section section, int index) {
        return section.rows().rows().stream().map((Row r) -> r.get(index).asText()).toList();
    }
}
