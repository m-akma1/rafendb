package org.rafendb.engine;

import java.nio.file.Path;
import java.util.Objects;
import org.rafendb.api.processor.QueryProcessor;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.api.storage.WalSync;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.mock.processor.MockQueryProcessor;
import org.rafendb.storage.StorageManagerImpl;
import org.rafendb.storage.buffer.BufferManager;
import org.rafendb.storage.catalog.CatalogManager;
import org.rafendb.storage.file.BlockManager;
import org.rafendb.storage.index.IndexManager;
import org.rafendb.storage.record.RecordSerializer;
import org.rafendb.storage.seed.SampleDatabaseLoader;

/**
 * Composition root: builds the components, wires them together and hands the {@link
 * QueryProcessor} to the server. For now only the mock QP is wired; real components and {@code
 * --mock} flags are added here once they exist.
 */
public final class Engine {
    private final QueryProcessor processor;

    private Engine(QueryProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    /**
     * Builds an engine backed by {@link MockQueryProcessor}.
     *
     * @return the engine
     */
    public static Engine withMocks() {
        return new Engine(new MockQueryProcessor());
    }

    /**
     * Wires the M1 storage graph with 4096-byte pages and the caller's FRM WAL implementation.
     * Loads the catalog and prepares indexes before FRM recovery; admission remains the startup
     * coordinator's job. Recovery must use applyRecovery so rebuilt indexes follow undo/redo.
     * Module TODOs fail explicitly until their owners implement them.
     */
    public static StorageManager storage(Path directory, int bufferFrames, WalSync wal)
            throws StorageInternalException {
        BlockManager blocks = new BlockManager(directory, 4096);
        BufferManager buffers = new BufferManager(blocks, wal, bufferFrames);
        CatalogManager catalog = new CatalogManager(directory);
        IndexManager indexes = new IndexManager(catalog, blocks, buffers);
        StorageManagerImpl storage =
                new StorageManagerImpl(
                        catalog,
                        new RecordSerializer(),
                        blocks,
                        buffers,
                        indexes,
                        new SampleDatabaseLoader());
        catalog.load();
        storage.initializeIndexes();
        return storage;
    }

    /**
     * Returns the query processor clients talk to.
     *
     * @return the query processor, never {@code null}
     */
    public QueryProcessor processor() {
        return processor;
    }
}
