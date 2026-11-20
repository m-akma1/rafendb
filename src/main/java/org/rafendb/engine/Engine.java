package org.rafendb.engine;

import java.nio.file.Path;
import java.util.Objects;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.api.processor.QueryProcessor;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.api.storage.WalSync;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.mock.concurrency.MockConcurrencyControl;
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
    /*
     * TODO(QP1): real wiring, after PR #55 (FRM) and PR #54 (QO) are merged.
     *
     * Entry point: Engine.start(EngineConfig) and Engine.close(). EngineConfig holds dataDir,
     * mocks (Set<Component>, from --mock), concurrency (ProtocolType), bufferFrames,
     * seedSampleData and drainTimeout. ServerMain and the driver kit build it from their flags.
     *
     * start, in this order:
     *  1. Lock dataDir/.lock with FileChannel.tryLock; refuse a second engine on the same data.
     *  2. SM: Engine.storage(dataDir, bufferFrames, walHolder). walHolder is a late-bound
     *     WalSync, because FRM needs SM and SM needs FRM for forceThrough.
     *  3. FRM: new FRManager(new Logger(walPath), storage), bind walHolder, recoverFromCrash().
     *     With #55, FRM also takes the ConcurrencyControl for quiescent checkpoints, while CCM
     *     takes FRM: a second cycle that needs a setter or late binding from FRM or CCM.
     *  4. CCM: new ConcurrencyControlManager(new TransactionManager(), protocol, frm), then
     *     initializeAfterRecovery(info). FRM does not yet report RecoveryInfo (highest tx id
     *     and timestamp in the log); without it, tx ids restart at 1 and may collide.
     *  5. Seed: with #55, loadSampleDatabase(logInsert) runs inside a bootstrap transaction
     *     that the engine begins, logs each INSERT through, and commits or rolls back.
     *  6. QO: with #54, new QueryOptimizerImpl(parser, binder(catalog), joinOptimizer(storage),
     *     planBuilder), one per session unless it is thread-safe.
     *  7. QP: the real QueryProcessor once it exists; MockQueryProcessor until then.
     *  8. Print one line per component: real or mock.
     *
     * close, in reverse: ccm.pauseNewTransactions() and awaitNoActive(drainTimeout), optional
     * checkpoint (decision D3), storage.close(), frm.close(), release the lock.
     *
     */

    private final QueryProcessor processor;
    private final ProtocolType protocol;

    private Engine(QueryProcessor processor, ProtocolType protocol) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    /**
     * Builds an engine backed by {@link MockQueryProcessor}.
     *
     * @return the engine
     */
    public static Engine withMocks() {
        return new Engine(new MockQueryProcessor(), new MockConcurrencyControl().activeProtocol());
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

    /**
     * Returns the concurrency protocol the Concurrency Control Manager runs, announced to clients.
     *
     * @return the protocol, never {@code null}
     */
    public ProtocolType concurrencyProtocol() {
        return protocol;
    }
}
