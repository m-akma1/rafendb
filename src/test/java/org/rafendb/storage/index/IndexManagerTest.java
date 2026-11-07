package org.rafendb.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rafendb.storage.index.IndexFixtures.f;
import static org.rafendb.storage.index.IndexFixtures.floatCol;
import static org.rafendb.storage.index.IndexFixtures.i;
import static org.rafendb.storage.index.IndexFixtures.intCol;
import static org.rafendb.storage.index.IndexFixtures.nul;
import static org.rafendb.storage.index.IndexFixtures.rid;
import static org.rafendb.storage.index.IndexFixtures.s;
import static org.rafendb.storage.index.IndexFixtures.varcharCol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.WalSync;
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
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.storage.buffer.BufferManager;
import org.rafendb.storage.catalog.CatalogManager;
import org.rafendb.storage.file.BlockId;
import org.rafendb.storage.file.BlockManager;
import org.rafendb.storage.index.btree.BPlusTree;

/** Tests {@link IndexManager} over real pages and a catalog fake, until SM4's catalog is merged. */
class IndexManagerTest {
    private static final int PAGE_SIZE = 128;
    private static final IndexInfo EMP_ID =
            new IndexInfo("emp_id", "emp", "id", IndexType.B_PLUS_TREE);
    private static final IndexInfo EMP_NAME =
            new IndexInfo("emp_name", "emp", "name", IndexType.B_PLUS_TREE);

    @TempDir Path dir;

    private final List<Long> forced = new ArrayList<>();
    private FakeCatalog catalog;
    private BlockManager blocks;
    private FailingBuffers buffers;
    private IndexManager manager;

    /** A catalog holding only what the index module asks of it. */
    private static final class FakeCatalog extends CatalogManager {
        final Map<String, TableSchema> schemas = new LinkedHashMap<>();
        final Map<String, IndexInfo> indexes = new LinkedHashMap<>();
        Runnable beforeRegister = () -> {};
        int failedSaves;
        int saves;

        FakeCatalog(Path directory) {
            super(directory);
        }

        @Override
        public List<String> listTables() {
            return List.copyOf(schemas.keySet());
        }

        @Override
        public TableSchema getSchema(String table) {
            TableSchema schema = schemas.get(table);
            if (schema == null) {
                throw new StorageRuntimeException("unknown table: " + table);
            }
            return schema;
        }

        @Override
        public List<IndexInfo> listIndexes(String table) {
            getSchema(table);
            return indexes.values().stream().filter(x -> x.table().equals(table)).toList();
        }

        @Override
        public IndexInfo getIndex(String name) {
            IndexInfo index = indexes.get(name);
            if (index == null) {
                throw new StorageRuntimeException("unknown index: " + name);
            }
            return index;
        }

        @Override
        public String indexFile(String name) {
            return "idx_" + name + ".idx";
        }

        @Override
        public void registerIndex(IndexInfo index) {
            beforeRegister.run();
            indexes.put(index.name(), index);
        }

        @Override
        public void unregisterIndex(String name) {
            indexes.remove(name);
        }

        @Override
        public void save() throws StorageInternalException {
            if (failedSaves > 0) {
                failedSaves--;
                throw new StorageInternalException("cannot save catalog");
            }
            saves++;
        }
    }

    /** A buffer that fails every pin of chosen files, to simulate a failure inside one index. */
    private static final class FailingBuffers extends BufferManager {
        final Set<String> failing = new HashSet<>();
        Runnable onFailure = () -> {};

        FailingBuffers(BlockManager blocks, WalSync wal, int capacity) {
            super(blocks, wal, capacity);
        }

        @Override
        public java.nio.ByteBuffer pin(BlockId block) throws StorageInternalException {
            if (failing.contains(block.fileName())) {
                onFailure.run();
                throw new StorageInternalException("injected failure: " + block);
            }
            return super.pin(block);
        }
    }

    /** A cursor over a list, optionally failing after its rows. */
    private static final class ListCursor implements RecordCursor {
        private final List<StoredRow> rows;
        private final boolean failAtEnd;
        private int next;
        boolean closed;

        ListCursor(List<StoredRow> rows, boolean failAtEnd) {
            this.rows = rows;
            this.failAtEnd = failAtEnd;
        }

        @Override
        public StoredRow next() throws StorageInternalException {
            if (next < rows.size()) {
                return rows.get(next++);
            }
            if (failAtEnd) {
                throw new StorageInternalException("injected scan failure");
            }
            return null;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @BeforeEach
    void open() {
        catalog = new FakeCatalog(dir);
        catalog.schemas.put(
                "emp",
                new TableSchema(
                        "emp", List.of(intCol("id"), varcharCol("name", 8), floatCol("salary"))));
        catalog.schemas.put("dept", new TableSchema("dept", List.of(intCol("id"))));
        blocks = new BlockManager(dir, PAGE_SIZE);
        buffers = new FailingBuffers(blocks, forced::add, 32);
        manager = new IndexManager(catalog, blocks, buffers);
    }

    @AfterEach
    void close() throws StorageInternalException {
        buffers.failing.clear();
        buffers.close();
        blocks.close();
    }

    private static Row row(int id, String name, Float salary) {
        return new Row(
                List.of(
                        i(id),
                        name == null ? nul(DataType.VARCHAR) : s(name),
                        salary == null ? nul(DataType.FLOAT) : f(salary)));
    }

    private static List<StoredRow> emps(int count) {
        List<StoredRow> rows = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            rows.add(new StoredRow(rid(k, 0), row(k, "n" + (k % 10), k * 1.5f)));
        }
        return rows;
    }

    private static ListCursor cursor(List<StoredRow> rows) {
        return new ListCursor(rows, false);
    }

    private void createEmpIndexes() throws StorageInternalException {
        manager.create(EMP_ID, cursor(emps(100)));
        manager.create(EMP_NAME, cursor(emps(100)));
    }

    // create

    @Test
    void createThenLookup() throws StorageInternalException {
        ListCursor rows = cursor(emps(100));
        manager.create(EMP_ID, rows);
        assertEquals(List.of(rid(42, 0)), manager.lookup("emp_id", i(42)));
        assertEquals(EMP_ID, catalog.indexes.get("emp_id"));
        assertEquals(1, catalog.saves);
        assertFalse(rows.closed, "the caller owns the cursor");
    }

    @Test
    void createSkipsNullKeys() throws StorageInternalException {
        manager.create(
                EMP_NAME,
                cursor(
                        List.of(
                                new StoredRow(rid(0, 0), row(0, null, null)),
                                new StoredRow(rid(0, 1), row(1, "a", null)))));
        assertEquals(List.of(rid(0, 1)), manager.range("emp_name", null, true, null, true));
    }

    @Test
    void createRejectsUsedName() throws StorageInternalException {
        manager.create(EMP_ID, cursor(emps(5)));
        StorageRuntimeException e =
                assertThrows(
                        StorageRuntimeException.class,
                        () -> manager.create(EMP_ID, cursor(emps(5))));
        assertTrue(e.getMessage().endsWith("index name already used: emp_id"), e.getMessage());
        assertEquals(List.of(rid(3, 0)), manager.lookup("emp_id", i(3)));
    }

    @Test
    void createRejectsMissingColumnWithoutRegistering() {
        StorageRuntimeException e =
                assertThrows(
                        StorageRuntimeException.class,
                        () ->
                                manager.create(
                                        new IndexInfo("x", "emp", "nope", IndexType.B_PLUS_TREE),
                                        cursor(List.of())));
        assertTrue(e.getMessage().endsWith("unknown column: nope in table emp"), e.getMessage());
        assertTrue(catalog.indexes.isEmpty());
    }

    @Test
    void createRejectsMissingTableWithoutRegistering() {
        assertThrows(
                StorageRuntimeException.class,
                () ->
                        manager.create(
                                new IndexInfo("x", "nope", "id", IndexType.B_PLUS_TREE),
                                cursor(List.of())));
        assertTrue(catalog.indexes.isEmpty());
    }

    @Test
    void createRejectsTooWideColumnBeforeTouchingFiles() {
        catalog.schemas.put("wide", new TableSchema("wide", List.of(varcharCol("v", 100))));
        assertThrows(
                StorageRuntimeException.class,
                () ->
                        manager.create(
                                new IndexInfo("w", "wide", "v", IndexType.B_PLUS_TREE),
                                cursor(List.of())));
        assertTrue(catalog.indexes.isEmpty());
        assertFalse(Files.exists(dir.resolve(catalog.indexFile("w"))));
    }

    @Test
    void failedBuildRegistersNothingAndFreesTheName() throws StorageInternalException {
        assertThrows(
                StorageInternalException.class,
                () -> manager.create(EMP_ID, new ListCursor(emps(50), true)));
        assertTrue(catalog.indexes.isEmpty());
        assertEquals(0, catalog.saves);
        assertFalse(Files.exists(dir.resolve(catalog.indexFile("emp_id"))));

        manager.create(EMP_ID, cursor(emps(5)));
        assertEquals(List.of(rid(3, 0)), manager.lookup("emp_id", i(3)));
    }

    @Test
    void createReplacesAnOrphanFileFromAnEarlierCrash() throws StorageInternalException {
        blocks.create(catalog.indexFile("emp_id"));
        blocks.append(catalog.indexFile("emp_id"));
        manager.create(EMP_ID, cursor(emps(5)));
        assertEquals(List.of(rid(3, 0)), manager.lookup("emp_id", i(3)));
    }

    @Test
    void indexIsDurableBeforeItIsRegistered() throws StorageInternalException {
        List<Rid> seenOnDisk = new ArrayList<>();
        catalog.beforeRegister =
                () -> {
                    try (BlockManager fresh = new BlockManager(dir, PAGE_SIZE);
                            BufferManager freshBuffers = new BufferManager(fresh, lsn -> {}, 8)) {
                        IndexPageStore store =
                                new IndexPageStore(
                                        catalog.indexFile("emp_id"), fresh, freshBuffers);
                        seenOnDisk.addAll(new BPlusTree(store, intCol("id")).lookup(i(42)));
                    } catch (StorageInternalException e) {
                        throw new AssertionError(e);
                    }
                };
        manager.create(EMP_ID, cursor(emps(100)));
        assertEquals(List.of(rid(42, 0)), seenOnDisk);
    }

    @Test
    void createIsNotLoggedToTheWal() throws StorageInternalException {
        manager.create(EMP_ID, cursor(emps(100)));
        assertEquals(List.of(), forced);
    }

    @Test
    void indexSurvivesReopenWithoutRebuild() throws StorageInternalException {
        manager.create(EMP_ID, cursor(emps(100)));
        try (BlockManager fresh = new BlockManager(dir, PAGE_SIZE);
                BufferManager freshBuffers = new BufferManager(fresh, lsn -> {}, 8)) {
            IndexManager reopened = new IndexManager(catalog, fresh, freshBuffers);
            assertEquals(List.of(rid(42, 0)), reopened.lookup("emp_id", i(42)));
        }
    }

    @Test
    void saveFailureRollsBackAndRetainsTheFileOnlyIfRollbackAlsoFails()
            throws StorageInternalException {
        catalog.failedSaves = 1;
        assertThrows(StorageInternalException.class, () -> manager.create(EMP_ID, cursor(emps(5))));
        assertTrue(catalog.listIndexes("emp").isEmpty());
        assertFalse(Files.exists(dir.resolve(catalog.indexFile("emp_id"))));

        catalog.failedSaves = 2;
        StorageInternalException failure =
                assertThrows(
                        StorageInternalException.class,
                        () -> manager.create(EMP_ID, cursor(emps(5))));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(List.of(EMP_ID), catalog.listIndexes("emp"));
        assertTrue(Files.exists(dir.resolve(catalog.indexFile("emp_id"))));
        assertThrows(StorageRuntimeException.class, () -> manager.create(EMP_ID, cursor(emps(5))));
        assertEquals(List.of(rid(3, 0)), manager.lookup("emp_id", i(3)));
    }

    // lookup and range

    @Test
    void unknownIndexIsAnInternalError() {
        StorageInternalException e =
                assertThrows(StorageInternalException.class, () -> manager.lookup("nope", i(1)));
        assertTrue(e.getMessage().endsWith("unknown index: nope"), e.getMessage());
        assertThrows(
                StorageInternalException.class,
                () -> manager.range("nope", null, true, null, true));
    }

    @Test
    void rangeOnHashIsRejected() {
        catalog.indexes.put("h", new IndexInfo("h", "emp", "id", IndexType.HASH));
        StorageInternalException e =
                assertThrows(
                        StorageInternalException.class,
                        () -> manager.range("h", i(1), true, i(2), true));
        assertTrue(
                e.getMessage().endsWith("index does not support range search: h"), e.getMessage());
    }

    @Test
    void otherFamilyIsRejectedBeforeNullIsConsidered() throws StorageInternalException {
        manager.create(EMP_ID, cursor(emps(5)));
        assertThrows(StorageInternalException.class, () -> manager.lookup("emp_id", f(3.0f)));
        assertThrows(
                StorageInternalException.class,
                () -> manager.lookup("emp_id", nul(DataType.VARCHAR)));
        assertThrows(
                StorageInternalException.class,
                () -> manager.range("emp_id", s("a"), true, null, true));
        assertThrows(
                StorageInternalException.class,
                () -> manager.range("emp_id", nul(DataType.INTEGER), true, s("a"), true));
    }

    @Test
    void nullKeyOrBoundFindsNothing() throws StorageInternalException {
        manager.create(EMP_ID, cursor(emps(5)));
        assertEquals(List.of(), manager.lookup("emp_id", nul(DataType.INTEGER)));
        assertEquals(List.of(), manager.range("emp_id", nul(DataType.INTEGER), true, i(3), true));
        assertEquals(List.of(), manager.range("emp_id", i(1), true, nul(DataType.INTEGER), true));
    }

    @Test
    void missingKeyFindsNothing() throws StorageInternalException {
        manager.create(EMP_ID, cursor(emps(5)));
        assertEquals(List.of(), manager.lookup("emp_id", i(99)));
    }

    @Test
    void resultsAreUnmodifiable() throws StorageInternalException {
        manager.create(EMP_ID, cursor(emps(5)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> manager.lookup("emp_id", i(1)).add(rid(0, 0)));
    }

    @Test
    void agreesWithTheMockOnItsDummyData() throws StorageInternalException {
        try (MockStorageManager mock = new MockStorageManager()) {
            catalog.schemas.put("mahasiswa", mock.getSchema("mahasiswa"));
            IndexInfo nim =
                    new IndexInfo("mahasiswa_nim_idx", "mahasiswa", "nim", IndexType.B_PLUS_TREE);
            try (RecordCursor rows = mock.scan("mahasiswa")) {
                manager.create(nim, rows);
            }
            int position = mock.getSchema("mahasiswa").columnIndex("nim");
            List<Value> keys = new ArrayList<>();
            try (RecordCursor rows = mock.scan("mahasiswa")) {
                for (StoredRow r = rows.next(); r != null; r = rows.next()) {
                    keys.add(r.row().get(position));
                }
            }
            assertFalse(keys.isEmpty());
            for (Value key : keys) {
                assertEquals(mock.lookupIndex(nim.name(), key), manager.lookup(nim.name(), key));
                assertEquals(
                        mock.rangeIndex(nim.name(), key, true, null, true),
                        manager.range(nim.name(), key, true, null, true));
                assertEquals(
                        mock.rangeIndex(nim.name(), null, true, key, false),
                        manager.range(nim.name(), null, true, key, false));
            }
            assertEquals(
                    assertThrows(
                                    StorageInternalException.class,
                                    () -> mock.lookupIndex(nim.name(), f(1f)))
                            .getMessage(),
                    assertThrows(
                                    StorageInternalException.class,
                                    () -> manager.lookup(nim.name(), f(1f)))
                            .getMessage());
        }
    }

    // maintenance

    @Test
    void onInsertAddsToEveryIndexOfTheTable() throws StorageInternalException {
        createEmpIndexes();
        manager.onInsert("emp", rid(500, 0), row(500, "new", 1f), 9);
        assertEquals(List.of(rid(500, 0)), manager.lookup("emp_id", i(500)));
        assertEquals(List.of(rid(500, 0)), manager.lookup("emp_name", s("new")));
    }

    @Test
    void onInsertSkipsNullAndOtherTables() throws StorageInternalException {
        createEmpIndexes();
        manager.onInsert("emp", rid(500, 0), row(500, null, null), 9);
        assertEquals(List.of(rid(500, 0)), manager.lookup("emp_id", i(500)));
        assertEquals(100, manager.range("emp_name", null, true, null, true).size());
        // "dept" has no index: nothing happens at all.
        manager.onInsert("dept", rid(1, 1), new Row(List.of(i(1))), 9);
        manager.onDelete("dept", rid(1, 1), new Row(List.of(i(1))), 9);
        manager.onUpdate("dept", rid(1, 1), new Row(List.of(i(1))), new Row(List.of(i(2))), 9);
        assertEquals(1, manager.lookup("emp_id", i(5)).size());
    }

    @Test
    void repeatedMaintenanceChangesNothing() throws StorageInternalException {
        createEmpIndexes();
        Row r = row(500, "new", 1f);
        Row r2 = row(501, "newer", 1f);
        manager.onInsert("emp", rid(500, 0), r, 9);
        manager.onInsert("emp", rid(500, 0), r, 9);
        assertEquals(1, manager.lookup("emp_id", i(500)).size());

        manager.onUpdate("emp", rid(500, 0), r, r2, 10);
        manager.onUpdate("emp", rid(500, 0), r, r2, 10);
        assertEquals(List.of(), manager.lookup("emp_id", i(500)));
        assertEquals(1, manager.lookup("emp_id", i(501)).size());

        manager.onDelete("emp", rid(500, 0), r2, 11);
        manager.onDelete("emp", rid(500, 0), r2, 11);
        assertEquals(List.of(), manager.lookup("emp_id", i(501)));
    }

    @Test
    void onDeleteRemovesOnlyThatRecord() throws StorageInternalException {
        createEmpIndexes();
        int before = manager.lookup("emp_name", s("n3")).size();
        manager.onDelete("emp", rid(3, 0), row(3, "n3", 4.5f), 9);
        assertEquals(before - 1, manager.lookup("emp_name", s("n3")).size());
        assertEquals(List.of(), manager.lookup("emp_id", i(3)));
        assertEquals(List.of(rid(13, 0)), manager.lookup("emp_id", i(13)));
    }

    @Test
    void onUpdateMovesOnlyChangedKeys() throws StorageInternalException {
        createEmpIndexes();
        manager.onUpdate("emp", rid(3, 0), row(3, "n3", 4.5f), row(3, "zzz", 4.5f), 9);
        assertEquals(List.of(rid(3, 0)), manager.lookup("emp_id", i(3)));
        assertEquals(List.of(rid(3, 0)), manager.lookup("emp_name", s("zzz")));
        assertFalse(manager.lookup("emp_name", s("n3")).contains(rid(3, 0)));
    }

    @Test
    void updateWithSameKeysDirtiesNothing() throws StorageInternalException {
        createEmpIndexes();
        buffers.flushAll();
        forced.clear();
        manager.onUpdate("emp", rid(3, 0), row(3, "n3", 4.5f), row(3, "n3", 99f), 9);
        buffers.flushAll();
        assertEquals(List.of(), forced);
    }

    @Test
    void updateNullTransitionsLeaveNoStaleEntry() throws StorageInternalException {
        createEmpIndexes();
        manager.onUpdate("emp", rid(3, 0), row(3, "n3", 4.5f), row(3, null, 4.5f), 9);
        assertFalse(manager.lookup("emp_name", s("n3")).contains(rid(3, 0)));
        assertEquals(99, manager.range("emp_name", null, true, null, true).size());

        manager.onUpdate("emp", rid(3, 0), row(3, null, 4.5f), row(3, "back", 4.5f), 10);
        assertEquals(List.of(rid(3, 0)), manager.lookup("emp_name", s("back")));
        assertEquals(100, manager.range("emp_name", null, true, null, true).size());
    }

    @Test
    void maintenanceCarriesTheOperationLsn() throws StorageInternalException {
        createEmpIndexes();
        manager.onInsert("emp", rid(500, 0), row(500, "new", 1f), 77);
        buffers.flushAll();
        assertEquals(List.of(77L), forced.stream().distinct().toList());
    }

    @Test
    void indexEqualsFullScan() throws StorageInternalException {
        createEmpIndexes();
        Map<Rid, Row> table = new TreeMap<>();
        for (StoredRow r : emps(100)) {
            table.put(r.rid(), r.row());
        }
        Random random = new Random(11);
        for (int op = 0; op < 3000; op++) {
            Rid at = rid(random.nextInt(200), 0);
            Row now = table.get(at);
            String name = random.nextInt(5) == 0 ? null : "n" + random.nextInt(20);
            Row next = row(random.nextInt(150), name, 1f);
            if (now == null) {
                manager.onInsert("emp", at, next, op + 1L);
                table.put(at, next);
            } else if (random.nextBoolean()) {
                manager.onUpdate("emp", at, now, next, op + 1L);
                table.put(at, next);
            } else {
                manager.onDelete("emp", at, now, op + 1L);
                table.remove(at);
            }
            if (op % 100 == 0) {
                assertMatchesScan(table);
            }
        }
        assertMatchesScan(table);
    }

    /** Checks both indexes of "emp" against a scan of its rows; Rids in lookups sort for equality. */
    private void assertMatchesScan(Map<Rid, Row> table) throws StorageInternalException {
        for (int id = 0; id < 150; id += 7) {
            Value key = i(id);
            assertEquals(
                    ridsWhere(table, 0, key), sorted(manager.lookup("emp_id", key)), "id " + id);
        }
        for (int n = 0; n < 20; n++) {
            Value key = s("n" + n);
            assertEquals(
                    ridsWhere(table, 1, key), sorted(manager.lookup("emp_name", key)), "n" + n);
        }
        int live = 0;
        for (Row r : table.values()) {
            if (!r.get(1).isNull()) {
                live++;
            }
        }
        assertEquals(live, manager.range("emp_name", null, true, null, true).size());
    }

    private static List<Rid> ridsWhere(Map<Rid, Row> table, int position, Value key) {
        return table.entrySet().stream()
                .filter(e -> key.equals(e.getValue().get(position)))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static List<Rid> sorted(List<Rid> rids) {
        return rids.stream().sorted().toList();
    }

    // partial failure

    @Test
    void failedInsertUndoesEarlierIndexes() throws StorageInternalException {
        createEmpIndexes();
        buffers.failing.add(catalog.indexFile("emp_name"));
        assertThrows(
                StorageInternalException.class,
                () -> manager.onInsert("emp", rid(500, 0), row(500, "new", 1f), 9));
        buffers.failing.clear();
        assertEquals(List.of(), manager.lookup("emp_id", i(500)));
    }

    @Test
    void failedUpdateRestoresEarlierIndexes() throws StorageInternalException {
        createEmpIndexes();
        buffers.failing.add(catalog.indexFile("emp_name"));
        assertThrows(
                StorageInternalException.class,
                () -> manager.onUpdate("emp", rid(3, 0), row(3, "n3", 1f), row(500, "new", 1f), 9));
        buffers.failing.clear();
        assertEquals(List.of(rid(3, 0)), manager.lookup("emp_id", i(3)));
        assertEquals(List.of(), manager.lookup("emp_id", i(500)));
    }

    @Test
    void failedDeleteRestoresEarlierIndexes() throws StorageInternalException {
        createEmpIndexes();
        buffers.failing.add(catalog.indexFile("emp_name"));
        assertThrows(
                StorageInternalException.class,
                () -> manager.onDelete("emp", rid(3, 0), row(3, "n3", 1f), 9));
        buffers.failing.clear();
        assertEquals(List.of(rid(3, 0)), manager.lookup("emp_id", i(3)));
    }

    @Test
    void theIndexThatFailedRefusesToAnswer() throws StorageInternalException {
        createEmpIndexes();
        buffers.failing.add(catalog.indexFile("emp_name"));
        assertThrows(
                StorageInternalException.class,
                () -> manager.onInsert("emp", rid(500, 0), row(500, "new", 1f), 9));
        buffers.failing.clear();
        // It might be half changed, so a wrong answer is worse than an error.
        assertThrows(StorageInternalException.class, () -> manager.lookup("emp_name", s("n1")));
        assertThrows(
                StorageInternalException.class,
                () -> manager.range("emp_name", null, true, null, true));
        // Later writes must not fail because of it: the rebuild will catch up.
        manager.onInsert("emp", rid(501, 0), row(501, "other", 1f), 10);
        assertEquals(List.of(rid(501, 0)), manager.lookup("emp_id", i(501)));
    }

    @Test
    void failedUndoAlsoDamagesThatIndexAndKeepsTheOriginalError() throws StorageInternalException {
        createEmpIndexes();
        String first = catalog.indexFile("emp_id");
        buffers.failing.add(catalog.indexFile("emp_name"));
        buffers.onFailure = () -> buffers.failing.add(first);
        StorageInternalException e =
                assertThrows(
                        StorageInternalException.class,
                        () -> manager.onInsert("emp", rid(500, 0), row(500, "new", 1f), 9));
        assertEquals(1, e.getSuppressed().length);
        assertTrue(e.getMessage().contains(catalog.indexFile("emp_name")), e.getMessage());
        buffers.failing.clear();
        buffers.onFailure = () -> {};
        assertThrows(StorageInternalException.class, () -> manager.lookup("emp_id", i(500)));
        assertThrows(StorageInternalException.class, () -> manager.lookup("emp_name", s("n1")));
    }

    // session marker

    @Test
    void firstSessionAsksForARebuild() throws StorageInternalException {
        assertTrue(manager.beginSession());
    }

    @Test
    void cleanShutdownNeedsNoRebuild() throws StorageInternalException {
        manager.beginSession();
        manager.markClean();
        assertFalse(manager.beginSession());
    }

    @Test
    void sessionWithoutCleanShutdownAsksForARebuild() throws StorageInternalException {
        manager.beginSession();
        manager.markClean();
        manager.beginSession();
        // No markClean: the process died. Beginning again must not read the old clean state.
        assertTrue(manager.beginSession());
    }

    @Test
    void beginningMarksNotCleanDurablyBeforeAnythingChanges() throws StorageInternalException {
        manager.beginSession();
        manager.markClean();
        manager.beginSession();
        try (BlockManager fresh = new BlockManager(dir, PAGE_SIZE)) {
            assertFalse(new IndexSessionMarker(fresh).isClean());
        }
    }

    @Test
    void markCleanIsIdempotentAndWorksAfterTheBufferCloses() throws StorageInternalException {
        manager.beginSession();
        buffers.close();
        manager.markClean();
        manager.markClean();
        assertFalse(manager.beginSession());
    }

    @Test
    void damagedIndexKeepsTheMarkerNotCleanWithoutFailingTheShutdown()
            throws StorageInternalException {
        manager.beginSession();
        createEmpIndexes();
        buffers.failing.add(catalog.indexFile("emp_name"));
        assertThrows(
                StorageInternalException.class,
                () -> manager.onInsert("emp", rid(500, 0), row(500, "new", 1f), 9));
        buffers.failing.clear();

        manager.markClean();

        assertTrue(manager.beginSession());
    }

    @Test
    void rebuildingTheDamagedIndexAllowsACleanShutdownAgain() throws StorageInternalException {
        manager.beginSession();
        createEmpIndexes();
        buffers.failing.add(catalog.indexFile("emp_name"));
        assertThrows(
                StorageInternalException.class,
                () -> manager.onInsert("emp", rid(500, 0), row(500, "new", 1f), 9));
        buffers.failing.clear();
        manager.rebuild("emp", cursor(emps(10)));

        manager.markClean();

        assertFalse(manager.beginSession());
    }

    // rebuild, drop, invalidate

    @Test
    void rebuildReplacesEveryIndexOfTheTable() throws StorageInternalException {
        createEmpIndexes();
        ListCursor rows = cursor(emps(10));
        manager.rebuild("emp", rows);
        assertEquals(10, manager.range("emp_id", null, true, null, true).size());
        assertEquals(10, manager.range("emp_name", null, true, null, true).size());
        assertEquals(List.of(), manager.lookup("emp_id", i(50)));
        assertFalse(rows.closed, "the caller owns the cursor");
        assertEquals(List.of(), forced);
    }

    @Test
    void rebuildIsDurable() throws StorageInternalException {
        createEmpIndexes();
        manager.rebuild("emp", cursor(emps(10)));
        try (BlockManager fresh = new BlockManager(dir, PAGE_SIZE);
                BufferManager freshBuffers = new BufferManager(fresh, lsn -> {}, 8)) {
            IndexManager reopened = new IndexManager(catalog, fresh, freshBuffers);
            assertEquals(List.of(rid(7, 0)), reopened.lookup("emp_id", i(7)));
        }
    }

    @Test
    void rebuildOfATableWithoutIndexesDoesNotScan() throws StorageInternalException {
        manager.rebuild("dept", new ListCursor(List.of(), true));
    }

    @Test
    void rebuildClearsDamage() throws StorageInternalException {
        createEmpIndexes();
        buffers.failing.add(catalog.indexFile("emp_name"));
        assertThrows(
                StorageInternalException.class,
                () -> manager.onInsert("emp", rid(500, 0), row(500, "new", 1f), 9));
        buffers.failing.clear();
        manager.rebuild("emp", cursor(emps(10)));
        assertEquals(1, manager.lookup("emp_name", s("n3")).size());
    }

    @Test
    void failedRebuildDamagesTheIndexes() throws StorageInternalException {
        createEmpIndexes();
        assertThrows(
                StorageInternalException.class,
                () -> manager.rebuild("emp", new ListCursor(emps(5), true)));
        assertThrows(StorageInternalException.class, () -> manager.lookup("emp_id", i(1)));
        manager.rebuild("emp", cursor(emps(5)));
        assertEquals(List.of(rid(1, 0)), manager.lookup("emp_id", i(1)));
    }

    @Test
    void dropRemovesOnlyThatTablesFiles() throws StorageInternalException {
        createEmpIndexes();
        IndexInfo dept = new IndexInfo("dept_id", "dept", "id", IndexType.B_PLUS_TREE);
        manager.create(dept, cursor(List.of(new StoredRow(rid(0, 0), new Row(List.of(i(1)))))));
        manager.dropIndexesOf("emp");
        assertFalse(Files.exists(dir.resolve(catalog.indexFile("emp_id"))));
        assertFalse(Files.exists(dir.resolve(catalog.indexFile("emp_name"))));
        assertEquals(List.of(rid(0, 0)), manager.lookup("dept_id", i(1)));
    }

    @Test
    void droppedNameCanBeUsedAgainWithFreshContents() throws StorageInternalException {
        manager.create(EMP_ID, cursor(emps(50)));
        manager.dropIndexesOf("emp");
        catalog.indexes.remove("emp_id"); // the facade's catalog.dropTable does this
        manager.create(EMP_ID, cursor(emps(5)));
        assertEquals(List.of(), manager.lookup("emp_id", i(40)));
    }

    @Test
    void dropOfATableWithoutIndexesDoesNothing() throws StorageInternalException {
        manager.dropIndexesOf("dept");
    }

    @Test
    void invalidateAllForgetsDamage() throws StorageInternalException {
        createEmpIndexes();
        buffers.failing.add(catalog.indexFile("emp_name"));
        assertThrows(
                StorageInternalException.class,
                () -> manager.onInsert("emp", rid(500, 0), row(500, "new", 1f), 9));
        buffers.failing.clear();
        manager.invalidateAll();
        assertEquals(1, manager.lookup("emp_id", i(7)).size());
        manager.lookup("emp_name", s("n1"));
    }

    // hash

    private static final IndexInfo EMP_NAME_HASH =
            new IndexInfo("emp_name_h", "emp", "name", IndexType.HASH);

    private static List<Rid> sortedLookup(IndexManager m, String index, Value key)
            throws StorageInternalException {
        return m.lookup(index, key).stream().sorted().toList();
    }

    @Test
    void hashIndexIsBuiltRegisteredAndSearched() throws StorageInternalException {
        manager.create(EMP_NAME_HASH, cursor(emps(100)));
        assertEquals(EMP_NAME_HASH, catalog.indexes.get("emp_name_h"));
        assertEquals(1, catalog.saves);
        assertEquals(10, manager.lookup("emp_name_h", s("n3")).size());
        assertEquals(List.of(), manager.lookup("emp_name_h", s("nope")));
        assertEquals(List.of(), manager.lookup("emp_name_h", nul(DataType.VARCHAR)));
        assertThrows(StorageInternalException.class, () -> manager.lookup("emp_name_h", i(1)));
        assertEquals(List.of(), forced);
    }

    @Test
    void hashIndexRefusesRanges() throws StorageInternalException {
        manager.create(EMP_NAME_HASH, cursor(emps(5)));
        StorageInternalException e =
                assertThrows(
                        StorageInternalException.class,
                        () -> manager.range("emp_name_h", s("a"), true, s("z"), true));
        assertTrue(
                e.getMessage().endsWith("index does not support range search: emp_name_h"),
                e.getMessage());
    }

    @Test
    void hashIndexSurvivesReopenWithoutRebuild() throws StorageInternalException {
        manager.create(EMP_NAME_HASH, cursor(emps(100)));
        try (BlockManager fresh = new BlockManager(dir, PAGE_SIZE);
                BufferManager freshBuffers = new BufferManager(fresh, lsn -> {}, 8)) {
            IndexManager reopened = new IndexManager(catalog, fresh, freshBuffers);
            assertEquals(10, reopened.lookup("emp_name_h", s("n3")).size());
        }
    }

    @Test
    void hashIndexIsMaintainedAlongsideATree() throws StorageInternalException {
        manager.create(EMP_ID, cursor(emps(20)));
        manager.create(EMP_NAME_HASH, cursor(emps(20)));
        manager.onInsert("emp", rid(500, 0), row(500, "new", 1f), 9);
        assertEquals(List.of(rid(500, 0)), manager.lookup("emp_name_h", s("new")));
        assertEquals(List.of(rid(500, 0)), manager.lookup("emp_id", i(500)));

        manager.onUpdate("emp", rid(500, 0), row(500, "new", 1f), row(500, "newer", 1f), 10);
        assertEquals(List.of(), manager.lookup("emp_name_h", s("new")));
        assertEquals(List.of(rid(500, 0)), manager.lookup("emp_name_h", s("newer")));

        manager.onDelete("emp", rid(500, 0), row(500, "newer", 1f), 11);
        assertEquals(List.of(), manager.lookup("emp_name_h", s("newer")));
    }

    @Test
    void failureInAHashIndexUndoesTheTree() throws StorageInternalException {
        manager.create(EMP_ID, cursor(emps(20)));
        manager.create(EMP_NAME_HASH, cursor(emps(20)));
        buffers.failing.add(catalog.indexFile("emp_name_h"));
        assertThrows(
                StorageInternalException.class,
                () -> manager.onInsert("emp", rid(500, 0), row(500, "new", 1f), 9));
        buffers.failing.clear();
        assertEquals(List.of(), manager.lookup("emp_id", i(500)));
        assertThrows(StorageInternalException.class, () -> manager.lookup("emp_name_h", s("n1")));
    }

    @Test
    void hashIndexIsRebuiltAndDropped() throws StorageInternalException {
        manager.create(EMP_NAME_HASH, cursor(emps(100)));
        manager.rebuild("emp", cursor(emps(10)));
        assertEquals(1, manager.lookup("emp_name_h", s("n3")).size());
        manager.dropIndexesOf("emp");
        assertFalse(Files.exists(dir.resolve(catalog.indexFile("emp_name_h"))));
    }

    @Test
    void hashAgreesWithTheMockOnItsDummyData() throws StorageInternalException {
        try (MockStorageManager mock = new MockStorageManager()) {
            catalog.schemas.put("prodi", mock.getSchema("prodi"));
            IndexInfo kode = new IndexInfo("prodi_kode_idx", "prodi", "kode", IndexType.HASH);
            try (RecordCursor rows = mock.scan("prodi")) {
                manager.create(kode, rows);
            }
            int position = mock.getSchema("prodi").columnIndex("kode");
            try (RecordCursor rows = mock.scan("prodi")) {
                for (StoredRow r = rows.next(); r != null; r = rows.next()) {
                    Value key = r.row().get(position);
                    assertEquals(
                            mock.lookupIndex(kode.name(), key).stream().sorted().toList(),
                            sortedLookup(manager, kode.name(), key));
                }
            }
        }
    }

    @Test
    void hashEqualsFullScan() throws StorageInternalException {
        manager.create(EMP_NAME_HASH, cursor(emps(100)));
        Map<Rid, Row> table = new TreeMap<>();
        for (StoredRow r : emps(100)) {
            table.put(r.rid(), r.row());
        }
        Random random = new Random(23);
        for (int op = 0; op < 3000; op++) {
            Rid at = rid(random.nextInt(200), 0);
            Row now = table.get(at);
            String name = random.nextInt(5) == 0 ? null : "n" + random.nextInt(20);
            Row next = row(random.nextInt(150), name, 1f);
            if (now == null) {
                manager.onInsert("emp", at, next, op + 1L);
                table.put(at, next);
            } else if (random.nextBoolean()) {
                manager.onUpdate("emp", at, now, next, op + 1L);
                table.put(at, next);
            } else {
                manager.onDelete("emp", at, now, op + 1L);
                table.remove(at);
            }
            if (op % 100 == 0) {
                for (int n = 0; n < 20; n++) {
                    Value key = s("n" + n);
                    assertEquals(
                            ridsWhere(table, 1, key), sortedLookup(manager, "emp_name_h", key));
                }
            }
        }
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> manager.lookup("emp_id", null));
        assertThrows(NullPointerException.class, () -> manager.lookup(null, i(1)));
        assertThrows(NullPointerException.class, () -> manager.create(null, cursor(List.of())));
        assertThrows(NullPointerException.class, () -> manager.create(EMP_ID, null));
    }

    /** Keeps {@link Column} and the fixture columns in step with what the manager resolves. */
    @Test
    void usesTheColumnOfTheSchemaNotOfTheIndexName() throws StorageInternalException {
        Column salary = floatCol("salary");
        IndexInfo info = new IndexInfo("emp_salary", "emp", salary.name(), IndexType.B_PLUS_TREE);
        manager.create(info, cursor(emps(5)));
        assertEquals(List.of(rid(2, 0)), manager.lookup("emp_salary", f(3.0f)));
    }
}
