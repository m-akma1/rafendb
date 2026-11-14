package org.rafendb.storage.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.Value;
import org.rafendb.engine.Engine;
import org.rafendb.storage.record.SlottedPage;

/** Checks persistent generations and compatibility with catalog version 1. */
class CatalogManagerTest {
    @TempDir Path dir;

    @Test
    void legacyGenerationStaysStableBeforeAndAfterUpgrade() throws Exception {
        writeLegacyDatabase();
        CatalogManager first = new CatalogManager(dir);
        first.load();
        UUID generation = first.getTableGeneration("legacy");
        CatalogManager second = new CatalogManager(dir);
        second.load();
        assertEquals(generation, second.getTableGeneration("legacy"));
        first.save();
        assertEquals(
                2, ByteBuffer.wrap(Files.readAllBytes(dir.resolve("catalog.snapshot"))).getInt(4));
        second.load();
        assertEquals(generation, second.getTableGeneration("legacy"));
    }

    @Test
    void legacyRowsSurviveOpeningAndSavingWithTheNewFacade() throws Exception {
        writeLegacyDatabase();
        UUID generation;
        try (StorageManager storage = Engine.storage(dir, 8, lsn -> {})) {
            generation = storage.getTableGeneration("legacy");
            assertEquals(
                    new Row(List.of(Value.ofInt(7))),
                    storage.fetch("legacy", new Rid(0, 0)).orElseThrow().row());
        }
        try (StorageManager storage = Engine.storage(dir, 8, lsn -> {})) {
            assertEquals(generation, storage.getTableGeneration("legacy"));
            assertEquals(
                    7, storage.fetch("legacy", new Rid(0, 0)).orElseThrow().row().get(0).asInt());
        }
    }

    @Test
    void failedReloadDoesNotPublishPartialGenerationState() throws Exception {
        writeLegacyDatabase();
        CatalogManager catalog = new CatalogManager(dir);
        catalog.load();
        UUID generation = catalog.getTableGeneration("legacy");
        catalog.save();
        Path snapshot = dir.resolve("catalog.snapshot");
        byte[] bytes = Files.readAllBytes(snapshot);
        Files.write(snapshot, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(StorageInternalException.class, catalog::load);
        assertEquals(generation, catalog.getTableGeneration("legacy"));
    }

    /** Writes an independent version-1 fixture with one INTEGER row at RID 0:0. */
    private void writeLegacyDatabase() throws IOException {
        try (DataOutputStream out =
                new DataOutputStream(Files.newOutputStream(dir.resolve("catalog.snapshot")))) {
            out.writeInt(0x52414643);
            out.writeInt(1);
            out.writeInt(1);
            writeString(out, "legacy");
            out.writeInt(1);
            writeString(out, "id");
            writeString(out, "INTEGER");
            out.writeInt(0);
            out.writeBoolean(false);
            out.writeBoolean(true);
            out.writeInt(0);
            writeString(out, "tbl_6c6567616379.dat");
            out.writeLong(1);
            out.writeLong(1);
            out.writeDouble(5);
            out.writeDouble(4096.0 / 5);
            out.writeInt(1);
            writeString(out, "id");
            out.writeLong(1);
            out.writeInt(0);
        }
        try (DataOutputStream out =
                new DataOutputStream(Files.newOutputStream(dir.resolve("alloc.hw")))) {
            out.writeInt(0x52414641);
            out.writeInt(1);
            out.writeInt(1);
            writeString(out, "legacy");
            out.writeLong(0);
            out.writeLong(1);
        }
        ByteBuffer page = ByteBuffer.allocate(4096);
        SlottedPage slots = new SlottedPage(page);
        slots.initialize();
        int slot = slots.reserve(5);
        slots.insert(slot, new byte[] {0, 0, 0, 0, 7});
        Files.write(dir.resolve("tbl_6c6567616379.dat"), page.array());
    }

    /** Writes the length-prefixed UTF-8 strings used by the legacy catalog. */
    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
