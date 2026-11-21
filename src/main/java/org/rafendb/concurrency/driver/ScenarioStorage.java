package org.rafendb.concurrency.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.concurrency.verify.SerializabilityVerifier.Access;
import org.rafendb.engine.Engine;

/** Maps each scenario table to one integer row in a temporary real database. */
final class ScenarioStorage extends Scenario {
    private final Path directory;
    private final Engine.DriverStorage resources;
    private final StorageManager storage;

    private ScenarioStorage(Path directory, Engine.DriverStorage resources) {
        this.directory = directory;
        this.resources = resources;
        this.storage = resources.storage();
    }

    static ScenarioStorage open() throws Exception {
        Path directory = Files.createTempDirectory("rafendb-ccm-");
        try {
            return new ScenarioStorage(directory, Engine.storageDriver(directory, 16));
        } catch (Exception failure) {
            deleteAfterFailure(directory, failure);
            throw failure;
        }
    }

    @Override
    RecoveryManager recovery() {
        return resources.recovery();
    }

    @Override
    synchronized Optional<Integer> storedValue(String table) throws StorageInternalException {
        StoredRow stored = storage.listTables().contains(table) ? findRow(table) : null;
        return stored == null ? Optional.empty() : Optional.of(stored.row().get(0).asInt());
    }

    @Override
    synchronized void write(ConcurrencyLogging logging, Access access, int value)
            throws ConcurrencyInternalException, StorageInternalException {
        String table = access.resource().table();
        boolean exists = storage.listTables().contains(table);
        StoredRow before = exists ? findRow(table) : null;
        if (!exists) {
            storage.createTable(
                    new TableSchema(
                            table,
                            List.of(new Column("value", DataType.INTEGER, 0, false, false))));
        }
        writeRow(logging, access, before, new Row(List.of(Value.ofInt(value))));
    }

    private StoredRow findRow(String table) throws StorageInternalException {
        try (var cursor = storage.scan(table)) {
            return cursor.next();
        }
    }

    private void writeRow(ConcurrencyLogging logging, Access access, StoredRow before, Row after)
            throws ConcurrencyInternalException, StorageInternalException {
        String table = access.resource().table();
        Optional<byte[]> afterImage = Optional.of(storage.encodeRow(table, after));
        if (before != null) {
            ResourceId row = ResourceId.ofRow(table, before.rid());
            Optional<byte[]> beforeImage = Optional.of(storage.encodeRow(table, before.row()));
            long lsn =
                    logging.logChange(access.tx(), new ChangeRecord(row, beforeImage, afterImage));
            storage.update(table, before.rid(), after, lsn);
            return;
        }

        Rid rid = storage.reserveRid(table, after);
        try {
            ResourceId row = ResourceId.ofRow(table, rid);
            long lsn =
                    logging.logChange(
                            access.tx(), new ChangeRecord(row, Optional.empty(), afterImage));
            storage.insert(table, rid, after, lsn);
        } catch (ConcurrencyInternalException
                | StorageInternalException
                | RuntimeException failure) {
            try {
                storage.cancelReservation(table, rid);
            } catch (StorageInternalException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    void close() throws Exception {
        try {
            resources.close();
        } catch (Exception failure) {
            deleteAfterFailure(directory, failure);
            throw failure;
        }
        deleteDirectory(directory);
    }

    private static void deleteAfterFailure(Path directory, Exception failure) {
        try {
            deleteDirectory(directory);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        List<Path> paths;
        try (var tree = Files.walk(directory)) {
            paths = tree.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
