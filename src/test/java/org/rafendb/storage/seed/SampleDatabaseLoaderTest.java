package org.rafendb.storage.seed;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.engine.Engine;

/** Checks that sample rows use the supplied logger before any logged page can be flushed. */
class SampleDatabaseLoaderTest {
    @TempDir Path dir;

    @Test
    void everySampleInsertIsLoggedBeforeItIsVisibleOrForced() throws Exception {
        Map<Long, ChangeRecord> log = new LinkedHashMap<>();
        List<Long> forced = new ArrayList<>();
        try (StorageManager storage =
                Engine.storage(
                        dir,
                        4,
                        lsn -> {
                            assertTrue(
                                    log.containsKey(lsn),
                                    "cannot force an LSN absent from the log");
                            forced.add(lsn);
                        })) {
            storage.loadSampleDatabase(
                    change -> {
                        String table = change.resource().table();
                        Rid rid = change.resource().rid();
                        assertTrue(change.beforeImage().isEmpty());
                        assertTrue(assertDoesNotThrow(() -> storage.fetch(table, rid)).isEmpty());
                        assertDoesNotThrow(
                                () -> storage.decodeRow(table, change.afterImage().orElseThrow()));
                        assertDoesNotThrow(() -> storage.getTableGeneration(table));
                        long lsn = 1000L + log.size() + 1;
                        log.put(lsn, change);
                        return lsn;
                    });
            assertEquals(180, log.size());
            assertFalse(forced.isEmpty());
            for (String table : SampleDatabaseLoader.seededTables()) {
                assertEquals(60, storage.getStats(table).recordCount());
            }
            assertEquals(
                    1,
                    storage.lookupIndex(
                                    "idx_customers_name",
                                    Value.ofText(DataType.VARCHAR, "Customer 1"))
                            .size());
            storage.loadSampleDatabase(
                    change -> {
                        throw new AssertionError("already populated");
                    });
            storage.loadSampleDatabase();
        }
        try (StorageManager reopened = Engine.storage(dir, 4, lsn -> {})) {
            assertEquals(60, reopened.getStats("customers").recordCount());
            assertEquals(
                    1,
                    reopened.lookupIndex(
                                    "idx_customers_name",
                                    Value.ofText(DataType.VARCHAR, "Customer 1"))
                            .size());
        }
    }

    @Test
    void missingLoggerFailsBeforeCreatingTables() throws Exception {
        try (StorageManager storage = Engine.storage(dir, 4, lsn -> {})) {
            assertThrows(StorageInternalException.class, storage::loadSampleDatabase);
            assertTrue(storage.listTables().isEmpty());
            assertThrows(NullPointerException.class, () -> storage.loadSampleDatabase(null));
            assertTrue(storage.listTables().isEmpty());
        }
    }

    @Test
    void loggingFailureCancelsTheCurrentReservationAndKeepsTheOriginalError() throws Exception {
        ChangeRecord[] attempted = new ChangeRecord[1];
        RuntimeException failure = new IllegalStateException("WAL unavailable");
        try (StorageManager storage = Engine.storage(dir, 4, lsn -> {})) {
            assertSame(
                    failure,
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    storage.loadSampleDatabase(
                                            change -> {
                                                attempted[0] = change;
                                                throw failure;
                                            })));
            String table = attempted[0].resource().table();
            Rid cancelled = attempted[0].resource().rid();
            Row row = storage.decodeRow(table, attempted[0].afterImage().orElseThrow());
            assertTrue(storage.fetch(table, cancelled).isEmpty());
            assertEquals(0, storage.getStats(table).recordCount());
            assertThrows(
                    StorageInternalException.class, () -> storage.insert(table, cancelled, row, 1));
        }
        try (StorageManager storage = Engine.storage(dir, 4, lsn -> {})) {
            String table = attempted[0].resource().table();
            Row row = storage.decodeRow(table, attempted[0].afterImage().orElseThrow());
            Rid next = storage.reserveRid(table, row);
            assertFalse(attempted[0].resource().rid().equals(next));
            storage.cancelReservation(table, next);
        }
    }

    @Test
    void nonpositiveLsnStopsLoadingWithoutPublishingTheRow() throws Exception {
        try (StorageManager storage = Engine.storage(dir, 4, lsn -> {})) {
            assertThrows(
                    StorageInternalException.class, () -> storage.loadSampleDatabase(change -> 0));
            assertEquals(0, storage.getStats("customers").recordCount());
            assertTrue(storage.fetch("customers", new Rid(0, 0)).isEmpty());
        }
    }

    @Test
    void partialFailureLeavesOnlyPreviouslyLoggedRowsForTheCallerToRollBack() throws Exception {
        int[] calls = {0};
        try (StorageManager storage = Engine.storage(dir, 4, lsn -> {})) {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            storage.loadSampleDatabase(
                                    change -> {
                                        if (++calls[0] == 3)
                                            throw new IllegalStateException("WAL unavailable");
                                        return calls[0];
                                    }));
            assertEquals(2, storage.getStats("customers").recordCount());
            assertTrue(storage.fetch("customers", new Rid(0, 2)).isEmpty());
            assertEquals(0, storage.getStats("orders").recordCount());
        }
    }
}
