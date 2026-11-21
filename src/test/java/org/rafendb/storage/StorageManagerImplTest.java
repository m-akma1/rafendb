package org.rafendb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.engine.Engine;
import org.rafendb.mock.storage.MockStorageManager;

/** Tests the Storage Manager facade over the real block, buffer, record and index modules. */
class StorageManagerImplTest {
    private static final String TABLE = "t";

    @TempDir Path dir;

    private StorageManagerImpl sm;
    private long lsn;

    @BeforeEach
    void open() throws StorageInternalException {
        sm = (StorageManagerImpl) Engine.storage(dir, 32, l -> {});
        sm.createTable(
                new TableSchema(
                        TABLE,
                        List.of(
                                new Column("id", DataType.INTEGER, 0, false, true),
                                new Column("name", DataType.VARCHAR, 50, true, false))));
    }

    @AfterEach
    void close() throws StorageInternalException {
        sm.close();
    }

    private static Row row(int id, String name) {
        return new Row(
                List.of(
                        Value.ofInt(id),
                        name == null
                                ? Value.nullOf(DataType.VARCHAR)
                                : Value.ofText(DataType.VARCHAR, name)));
    }

    private static Value text(String s) {
        return Value.ofText(DataType.VARCHAR, s);
    }

    private Rid add(int id, String name) throws StorageInternalException {
        Row r = row(id, name);
        Rid rid = sm.reserveRid(TABLE, r);
        return sm.insert(TABLE, rid, r, ++lsn);
    }

    private List<StoredRow> scanAll() throws StorageInternalException {
        List<StoredRow> rows = new ArrayList<>();
        try (RecordCursor c = sm.scan(TABLE)) {
            StoredRow r;
            while ((r = c.next()) != null) {
                rows.add(r);
            }
        }
        return rows;
    }

    @Test
    void insertedRowIsFetchedAndScanned() throws StorageInternalException {
        Rid rid = add(1, "satu");
        assertEquals(Optional.of(new StoredRow(rid, row(1, "satu"))), sm.fetch(TABLE, rid));
        assertEquals(List.of(new StoredRow(rid, row(1, "satu"))), scanAll());
    }

    @Test
    void nullValuesSurviveARoundTrip() throws StorageInternalException {
        Rid rid = add(2, null);
        assertEquals(row(2, null), sm.fetch(TABLE, rid).orElseThrow().row());
    }

    @Test
    void rowImagesPreserveTypesUnicodeNullAndEmptyTextAcrossRestart()
            throws StorageInternalException {
        sm.createTable(
                new TableSchema(
                        "images",
                        List.of(
                                new Column("id", DataType.INTEGER, 0, false, true),
                                new Column("score", DataType.FLOAT, 0, false, false),
                                new Column("code", DataType.CHAR, 8, false, false),
                                new Column("text", DataType.VARCHAR, 50, false, false),
                                new Column("missing", DataType.VARCHAR, 50, true, false),
                                new Column("empty", DataType.VARCHAR, 50, true, false))));
        Row original =
                new Row(
                        List.of(
                                Value.ofInt(-42),
                                Value.ofFloat(3.75f),
                                Value.ofText(DataType.CHAR, "I\0F"),
                                text("Budi \u4f60\u597d \ud83d\ude00\0"),
                                Value.nullOf(DataType.VARCHAR),
                                text("")));
        byte[] image = sm.encodeRow("images", original);
        byte[] snapshot = image.clone();
        UUID generation = sm.getTableGeneration("images");
        sm.close();
        sm = (StorageManagerImpl) Engine.storage(dir, 32, l -> {});
        assertEquals(generation, sm.getTableGeneration("images"));
        assertEquals(original, sm.decodeRow("images", image));
        assertArrayEquals(snapshot, image);
    }

    @Test
    void decodedImageRestoresADeletedRowAndItsIndex() throws StorageInternalException {
        sm.createIndex(new IndexInfo("idx_name", TABLE, "name", IndexType.HASH));
        Rid rid = add(7, "Budi \u00e9");
        Row original = sm.fetch(TABLE, rid).orElseThrow().row();
        byte[] image = sm.encodeRow(TABLE, original);
        sm.delete(TABLE, rid, ++lsn);
        sm.applyRecovery(TABLE, rid, Optional.of(sm.decodeRow(TABLE, image)), ++lsn);
        assertEquals(original, sm.fetch(TABLE, rid).orElseThrow().row());
        assertEquals(List.of(rid), sm.lookupIndex("idx_name", original.get(1)));
    }

    @Test
    void rowCodecRejectsInvalidInputsAndCorruptImages() {
        byte[] image = sm.encodeRow(TABLE, row(1, "Budi"));
        assertThrows(NullPointerException.class, () -> sm.encodeRow(TABLE, null));
        assertThrows(NullPointerException.class, () -> sm.decodeRow(TABLE, null));
        assertThrows(NullPointerException.class, () -> sm.encodeRow(null, row(1, null)));
        assertThrows(NullPointerException.class, () -> sm.decodeRow(null, image));
        assertThrows(NullPointerException.class, () -> sm.getTableGeneration(null));
        assertThrows(StorageRuntimeException.class, () -> sm.encodeRow("missing", row(1, null)));
        assertThrows(StorageRuntimeException.class, () -> sm.decodeRow("missing", image));
        assertThrows(StorageRuntimeException.class, () -> sm.getTableGeneration("missing"));
        assertThrows(
                StorageRuntimeException.class,
                () -> sm.encodeRow(TABLE, new Row(List.of(Value.ofInt(1)))));
        assertThrows(StorageInternalException.class, () -> sm.decodeRow(TABLE, new byte[0]));
        assertThrows(
                StorageInternalException.class,
                () -> sm.decodeRow(TABLE, Arrays.copyOf(image, image.length - 1)));
        assertThrows(
                StorageInternalException.class,
                () -> sm.decodeRow(TABLE, Arrays.copyOf(image, image.length + 1)));
        byte[] invalidUtf8 = image.clone();
        invalidUtf8[invalidUtf8.length - 1] = (byte) 0xff;
        assertThrows(StorageInternalException.class, () -> sm.decodeRow(TABLE, invalidUtf8));
    }

    @Test
    void rejectedTextDoesNotChangeExistingRows() throws StorageInternalException {
        Rid rid = add(1, "original");
        Row malformed = row(1, "\uD800");
        assertThrows(StorageRuntimeException.class, () -> sm.encodeRow(TABLE, malformed));
        assertThrows(StorageRuntimeException.class, () -> sm.reserveRid(TABLE, malformed));
        assertThrows(StorageRuntimeException.class, () -> sm.update(TABLE, rid, malformed, ++lsn));
        assertThrows(
                StorageInternalException.class,
                () -> sm.applyRecovery(TABLE, rid, Optional.of(malformed), ++lsn));
        assertEquals(row(1, "original"), sm.fetch(TABLE, rid).orElseThrow().row());
    }

    @Test
    void recreatedTableHasANewGenerationEvenAtTheSameRid() throws StorageInternalException {
        UUID generation = sm.getTableGeneration(TABLE);
        Rid original = add(1, "old");
        TableSchema schema = sm.getSchema(TABLE);
        sm.dropTable(TABLE, false);
        sm.createTable(schema);
        assertFalse(generation.equals(sm.getTableGeneration(TABLE)));
        assertEquals(original, add(2, "new"));
    }

    @Test
    void mockAndRealStorageUseCompatibleImages() throws StorageInternalException {
        try (MockStorageManager mock = new MockStorageManager()) {
            sm.createTable(mock.getSchema("prodi"));
            sm.createTable(mock.getSchema("mahasiswa"));
            Row original =
                    new Row(
                            List.of(
                                    Value.ofInt(42),
                                    text("Budi \u4f60\u597d"),
                                    Value.nullOf(DataType.FLOAT),
                                    Value.ofText(DataType.CHAR, "IF")));
            byte[] image = mock.encodeRow("mahasiswa", original);
            assertArrayEquals(sm.encodeRow("mahasiswa", original), image);
            assertEquals(original, sm.decodeRow("mahasiswa", image));
            assertEquals(original, mock.decodeRow("mahasiswa", image));
            assertThrows(
                    StorageInternalException.class, () -> mock.decodeRow("mahasiswa", new byte[0]));
            assertThrows(
                    StorageRuntimeException.class, () -> mock.encodeRow("mahasiswa", row(1, null)));
            try (MockStorageManager other = new MockStorageManager()) {
                assertEquals(
                        mock.getTableGeneration("mahasiswa"),
                        other.getTableGeneration("mahasiswa"));
            }
            mock.loadSampleDatabase(
                    change -> {
                        throw new AssertionError("dummy tables already exist");
                    });
            assertThrows(NullPointerException.class, () -> mock.loadSampleDatabase(null));
        }
    }

    @Test
    void cancelledReservationIsInvisibleAndCannotBeInserted() throws StorageInternalException {
        Row r = row(1, "a");
        Rid rid = sm.reserveRid(TABLE, r);
        sm.cancelReservation(TABLE, rid);
        assertTrue(scanAll().isEmpty());
        assertThrows(StorageInternalException.class, () -> sm.insert(TABLE, rid, r, ++lsn));
        sm.cancelReservation(TABLE, rid);
    }

    @Test
    void reservedSlotsAreNotReusedAfterCancel() throws StorageInternalException {
        Rid first = sm.reserveRid(TABLE, row(1, "a"));
        sm.cancelReservation(TABLE, first);
        Rid second = sm.reserveRid(TABLE, row(2, "b"));
        assertFalse(first.equals(second));
    }

    @Test
    void insertWithoutReservationIsRejected() {
        assertThrows(
                StorageInternalException.class,
                () -> sm.insert(TABLE, new Rid(0, 3), row(1, "a"), ++lsn));
    }

    @Test
    void rowThatDoesNotFitTheSchemaIsAClientError() {
        Row wrong = new Row(List.of(Value.ofInt(1)));
        assertThrows(StorageRuntimeException.class, () -> sm.reserveRid(TABLE, wrong));
    }

    @Test
    void unknownTableIsAClientError() {
        assertThrows(StorageRuntimeException.class, () -> sm.scan("nope"));
        assertThrows(StorageRuntimeException.class, () -> sm.getSchema("nope"));
    }

    @Test
    void updateReplacesTheRow() throws StorageInternalException {
        Rid rid = add(1, "lama");
        sm.update(TABLE, rid, row(1, "baru"), ++lsn);
        assertEquals(row(1, "baru"), sm.fetch(TABLE, rid).orElseThrow().row());
        assertEquals(1, scanAll().size());
    }

    @Test
    void updateOfADeletedRowIsRejected() throws StorageInternalException {
        Rid rid = add(1, "a");
        sm.delete(TABLE, rid, ++lsn);
        assertThrows(
                StorageInternalException.class, () -> sm.update(TABLE, rid, row(1, "b"), ++lsn));
    }

    @Test
    void deleteIsIdempotent() throws StorageInternalException {
        Rid rid = add(1, "a");
        assertTrue(sm.delete(TABLE, rid, ++lsn));
        assertFalse(sm.delete(TABLE, rid, ++lsn));
        assertTrue(sm.fetch(TABLE, rid).isEmpty());
        assertFalse(sm.delete(TABLE, new Rid(99, 0), ++lsn));
    }

    @Test
    void deleteDuringAScanDoesNotBreakTheScan() throws StorageInternalException {
        Rid a = add(1, "a");
        Rid b = add(2, "b");
        Rid c = add(3, "c");
        List<Rid> seen = new ArrayList<>();
        try (RecordCursor cursor = sm.scan(TABLE)) {
            seen.add(cursor.next().rid());
            sm.delete(TABLE, b, ++lsn);
            StoredRow r;
            while ((r = cursor.next()) != null) {
                seen.add(r.rid());
            }
            assertEquals(null, cursor.next());
        }
        assertEquals(List.of(a, c), seen);
    }

    @Test
    void manyRowsSpanSeveralBlocks() throws StorageInternalException {
        for (int i = 0; i < 300; i++) {
            add(i, "baris nomor " + i);
        }
        List<StoredRow> rows = scanAll();
        assertEquals(300, rows.size());
        assertTrue(rows.stream().anyMatch(r -> r.rid().block() > 0));
        assertEquals(300, sm.getStats(TABLE).recordCount());
    }

    @Test
    void statsFollowInsertsAndDeletes() throws StorageInternalException {
        Rid a = add(1, "a");
        add(2, "b");
        sm.delete(TABLE, a, ++lsn);
        assertEquals(1, sm.getStats(TABLE).recordCount());
    }

    @Test
    void clearTableDeletesEveryRow() throws StorageInternalException {
        add(1, "a");
        add(2, "b");
        add(3, "c");
        assertEquals(3, sm.clearTable(TABLE, row -> ++lsn));
        assertTrue(scanAll().isEmpty());
    }

    @Test
    void dropTableIsRefusedWhileACursorIsOpen() throws StorageInternalException {
        add(1, "a");
        RecordCursor cursor = sm.scan(TABLE);
        assertThrows(StorageInternalException.class, () -> sm.dropTable(TABLE, false));
        assertTrue(sm.listTables().contains(TABLE));
        cursor.close();
        sm.dropTable(TABLE, false);
        assertFalse(sm.listTables().contains(TABLE));
        assertThrows(StorageRuntimeException.class, () -> sm.scan(TABLE));
    }

    @Test
    void aTableCanBeRecreatedAfterDrop() throws StorageInternalException {
        add(1, "a");
        TableSchema schema = sm.getSchema(TABLE);
        sm.dropTable(TABLE, false);
        sm.createTable(schema);
        assertTrue(scanAll().isEmpty());
    }

    @Test
    void indexesFollowInsertUpdateAndDelete() throws StorageInternalException {
        sm.createIndex(new IndexInfo("idx_name", TABLE, "name", IndexType.HASH));
        sm.createIndex(new IndexInfo("idx_id", TABLE, "id", IndexType.B_PLUS_TREE));
        Rid rid = add(5, "lima");
        assertEquals(List.of(rid), sm.lookupIndex("idx_name", text("lima")));
        assertEquals(List.of(rid), sm.lookupIndex("idx_id", Value.ofInt(5)));
        sm.update(TABLE, rid, row(5, "baru"), ++lsn);
        assertTrue(sm.lookupIndex("idx_name", text("lima")).isEmpty());
        assertEquals(List.of(rid), sm.lookupIndex("idx_name", text("baru")));
        sm.delete(TABLE, rid, ++lsn);
        assertTrue(sm.lookupIndex("idx_name", text("baru")).isEmpty());
        assertTrue(sm.lookupIndex("idx_id", Value.ofInt(5)).isEmpty());
    }

    @Test
    void indexCreatedLaterCoversExistingRows() throws StorageInternalException {
        Rid a = add(1, "x");
        Rid b = add(2, "x");
        add(3, "y");
        sm.createIndex(new IndexInfo("idx_name", TABLE, "name", IndexType.HASH));
        assertEquals(List.of(a, b), sm.lookupIndex("idx_name", text("x")));
    }

    @Test
    void rangeSearchWorksOnATreeAndNotOnAHash() throws StorageInternalException {
        sm.createIndex(new IndexInfo("idx_id", TABLE, "id", IndexType.B_PLUS_TREE));
        sm.createIndex(new IndexInfo("idx_name", TABLE, "name", IndexType.HASH));
        add(1, "a");
        Rid two = add(2, "b");
        Rid three = add(3, "c");
        add(4, "d");
        assertEquals(
                List.of(two, three),
                sm.rangeIndex("idx_id", Value.ofInt(2), true, Value.ofInt(3), true));
        assertThrows(
                StorageInternalException.class,
                () -> sm.rangeIndex("idx_name", text("a"), true, text("z"), true));
    }

    @Test
    void dropTableRemovesItsIndexes() throws StorageInternalException {
        sm.createIndex(new IndexInfo("idx_name", TABLE, "name", IndexType.HASH));
        sm.dropTable(TABLE, false);
        assertThrows(StorageInternalException.class, () -> sm.lookupIndex("idx_name", text("a")));
    }

    @Test
    void recoveryRedoInsertsUpdatesAndDeletes() throws StorageInternalException {
        sm.createIndex(new IndexInfo("idx_name", TABLE, "name", IndexType.HASH));
        Rid rid = new Rid(0, 0);
        sm.applyRecovery(TABLE, rid, Optional.of(row(1, "a")), 10);
        assertEquals(row(1, "a"), sm.fetch(TABLE, rid).orElseThrow().row());
        assertEquals(List.of(rid), sm.lookupIndex("idx_name", text("a")));
        sm.applyRecovery(TABLE, rid, Optional.of(row(1, "b")), 11);
        assertTrue(sm.lookupIndex("idx_name", text("a")).isEmpty());
        assertEquals(List.of(rid), sm.lookupIndex("idx_name", text("b")));
        sm.applyRecovery(TABLE, rid, Optional.empty(), 12);
        assertTrue(sm.fetch(TABLE, rid).isEmpty());
        assertTrue(sm.lookupIndex("idx_name", text("b")).isEmpty());
    }

    @Test
    void recoveryIsIdempotent() throws StorageInternalException {
        Rid rid = new Rid(0, 0);
        for (int i = 0; i < 3; i++) {
            sm.applyRecovery(TABLE, rid, Optional.of(row(1, "a")), 10);
        }
        assertEquals(1, scanAll().size());
        assertEquals(1, sm.getStats(TABLE).recordCount());
        for (int i = 0; i < 3; i++) {
            sm.applyRecovery(TABLE, rid, Optional.empty(), 11);
        }
        assertTrue(scanAll().isEmpty());
        assertEquals(0, sm.getStats(TABLE).recordCount());
    }

    @Test
    void recoveryCompletesAReservedSlot() throws StorageInternalException {
        Row r = row(1, "a");
        Rid rid = sm.reserveRid(TABLE, r);
        sm.applyRecovery(TABLE, rid, Optional.of(r), 10);
        assertEquals(r, sm.fetch(TABLE, rid).orElseThrow().row());
    }

    @Test
    void recoveryRestoresARowThatWasDeleted() throws StorageInternalException {
        Rid rid = add(1, "a");
        sm.delete(TABLE, rid, ++lsn);
        sm.applyRecovery(TABLE, rid, Optional.of(row(1, "a")), ++lsn);
        assertEquals(row(1, "a"), sm.fetch(TABLE, rid).orElseThrow().row());
    }

    @Test
    void recoveryOfAnAbsentRowOnAnEmptyTableChangesNothing() throws StorageInternalException {
        sm.applyRecovery(TABLE, new Rid(0, 0), Optional.empty(), 10);
        sm.applyRecovery(TABLE, new Rid(7, 3), Optional.empty(), 11);
        assertTrue(scanAll().isEmpty());
    }

    @Test
    void recoveredSlotsAreNotHandedOutAgain() throws StorageInternalException {
        Rid recovered = new Rid(0, 4);
        sm.applyRecovery(TABLE, recovered, Optional.of(row(1, "a")), 10);
        sm.applyRecovery(TABLE, recovered, Optional.empty(), 11);
        Rid next = sm.reserveRid(TABLE, row(2, "b"));
        assertFalse(recovered.equals(next));
    }

    @Test
    void dataAndIndexesSurviveACleanRestart() throws StorageInternalException {
        sm.createIndex(new IndexInfo("idx_name", TABLE, "name", IndexType.HASH));
        sm.createIndex(new IndexInfo("idx_id", TABLE, "id", IndexType.B_PLUS_TREE));
        Rid a = add(1, "a");
        Rid b = add(2, "b");
        sm.delete(TABLE, a, ++lsn);
        sm.close();

        sm = (StorageManagerImpl) Engine.storage(dir, 32, l -> {});
        assertEquals(List.of(b), scanAll().stream().map(StoredRow::rid).toList());
        assertEquals(List.of(b), sm.lookupIndex("idx_name", text("b")));
        assertEquals(List.of(b), sm.lookupIndex("idx_id", Value.ofInt(2)));
        assertTrue(sm.lookupIndex("idx_name", text("a")).isEmpty());
        assertEquals(1, sm.getStats(TABLE).recordCount());
    }

    @Test
    void sampleDatabaseLoadsAndIsQueryable() throws StorageInternalException {
        sm.dropTable(TABLE, false);
        sm.loadSampleDatabase(change -> ++lsn);
        assertTrue(sm.listTables().containsAll(List.of("customers", "orders", "order_items")));
        try (RecordCursor c = sm.scan("customers")) {
            assertTrue(c.next() != null);
        }
        IndexInfo byName = sm.listIndexes("customers").get(0);
        assertEquals(1, sm.lookupIndex(byName.name(), text("Customer 1")).size());
    }

    @Test
    void closedManagerRejectsCallsAndClosesTwice() throws StorageInternalException {
        sm.close();
        assertThrows(IllegalStateException.class, () -> sm.listTables());
        assertThrows(IllegalStateException.class, () -> sm.scan(TABLE));
        assertThrows(IllegalStateException.class, () -> sm.encodeRow(TABLE, row(1, null)));
        assertThrows(IllegalStateException.class, () -> sm.decodeRow(TABLE, new byte[0]));
        assertThrows(IllegalStateException.class, () -> sm.getTableGeneration(TABLE));
        assertThrows(IllegalStateException.class, () -> sm.loadSampleDatabase(change -> 1));
        sm.close();
    }
}
