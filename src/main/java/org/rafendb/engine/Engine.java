package org.rafendb.engine;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.api.processor.QueryProcessor;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.api.storage.WalSync;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.mock.concurrency.MockConcurrencyControl;
import org.rafendb.mock.processor.MockQueryProcessor;
import org.rafendb.recovery.FRManager;
import org.rafendb.recovery.log.Logger;
import org.rafendb.storage.StorageManagerImpl;
import org.rafendb.storage.buffer.BufferManager;
import org.rafendb.storage.catalog.CatalogManager;
import org.rafendb.storage.file.BlockManager;
import org.rafendb.storage.index.IndexManager;
import org.rafendb.storage.record.RecordSerializer;
import org.rafendb.storage.seed.SampleDatabaseLoader;

/**
 * Builds component implementations and owns their startup order. The server currently uses
 * {@link #withMocks()}; the standalone SM driver uses {@link #storageDriver(Path, int)} for real
 * storage and recovery. The client only connects over sockets and never opens database files.
 */
public final class Engine {
    /*
     * TODO(QP1): connect ServerMain to the real QP, QO and CCM. The persistent SM driver's
     * resource graph below is already available, but does not start a server or manage sessions.
     *
     * The real server must start in this order:
     *  1. Lock the database directory so two processes cannot mutate the same files.
     *  2. Open the persistent WAL and storage, injecting that WAL's forceThrough into SM.
     *     storageDriver() already loads the catalog and initializes indexes before recovery.
     *  3. Run crash recovery; reject client admission if recovery fails.
     *  4. Initialize the configured CCM protocol with recovered transaction/logical timestamp
     *     high-water marks. Connect FRM checkpoint admission control to this same CCM.
     *  5. If sample data is requested, load it exclusively with a logged bootstrap transaction;
     *     its caller must commit or roll back, and handle unlogged DDL on failure.
     *  6. Build QO over this storage's metadata, then QP over the shared QO, SM, CCM and FRM.
     *  7. Accept clients and give each its own QP session. Announce the actual CCM protocol.
     *
     * Shutdown must stop new transactions, wait for active work to finish, then close storage
     * before WAL and release the directory lock. Client history/config (--home) stays separate.
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
     * Opens the standalone storage driver's persistent data and WAL, exclusively, and recovers
     * before returning. This does not start the server or initialize its component graph.
     */
    public static DriverStorage storageDriver(Path directory, int bufferFrames) throws Exception {
        Files.createDirectories(directory);
        FileChannel lockFile =
                FileChannel.open(
                        directory.resolve(".lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        Logger logger = null;
        StorageManager storage = null;
        try {
            if (lockFile.tryLock() == null) {
                throw new IllegalStateException(
                        "database directory is already in use: " + directory);
            }
            logger = new Logger(directory.resolve("wal.log"));
            storage = storage(directory, bufferFrames, logger);
            FRManager recovery = new FRManager(logger, storage);
            if (!recovery.recoverFromCrash()) {
                throw new StorageInternalException("storage driver crash recovery failed");
            }
            storage.flush();
            return new DriverStorage(
                    storage, recovery, logger.getHighestTransactionId(), logger, lockFile);
        } catch (Exception failure) {
            for (AutoCloseable resource : new AutoCloseable[] {storage, logger, lockFile}) {
                if (resource != null) {
                    try {
                        resource.close();
                    } catch (Exception cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            throw failure;
        }
    }

    /** Resources of one exclusive driver run; closes storage before its WAL and directory lock. */
    public record DriverStorage(
            StorageManager storage,
            RecoveryManager recovery,
            long maxTransactionId,
            AutoCloseable wal,
            FileChannel lockFile)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try (lockFile;
                    wal;
                    storage) {
                // Resources close in reverse order, including when a flush fails.
            }
        }
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
