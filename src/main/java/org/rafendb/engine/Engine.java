package org.rafendb.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.api.optimizer.QueryOptimizer;
import org.rafendb.api.processor.QueryProcessor;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.api.storage.StorageMetadata;
import org.rafendb.api.storage.WalSync;
import org.rafendb.common.Component;
import org.rafendb.common.error.RecoveryInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.log.LogType;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.TxId;
import org.rafendb.concurrency.ConcurrencyControlManager;
import org.rafendb.concurrency.protocol.LockBasedProtocol;
import org.rafendb.concurrency.protocol.Protocol;
import org.rafendb.concurrency.protocol.TimestampBasedProtocol;
import org.rafendb.concurrency.txn.TransactionManager;
import org.rafendb.mock.concurrency.MockConcurrencyControl;
import org.rafendb.mock.processor.MockQueryProcessor;
import org.rafendb.mock.recovery.MockRecoveryManager;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.optimizer.QueryOptimizerImpl;
import org.rafendb.optimizer.binder.QueryBinder;
import org.rafendb.optimizer.catalog.CachingCatalog;
import org.rafendb.optimizer.catalog.Catalog;
import org.rafendb.optimizer.catalog.StorageManagerCatalog;
import org.rafendb.optimizer.cost.CostModel;
import org.rafendb.optimizer.cost.StatsCostModel;
import org.rafendb.optimizer.join.JoinAccessPathOptimizer;
import org.rafendb.optimizer.parser.QueryParser;
import org.rafendb.optimizer.plan.InitialPlanBuilder;
import org.rafendb.processor.session.QueryProcessorImpl;
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
 * Builds component implementations and owns their startup and shutdown order. The server starts
 * one with {@link #start(EngineConfig)}; the standalone SM driver uses {@link #storageDriver(Path,
 * int)}. The client only connects over sockets and never opens database files.
 *
 * <p>Startup: lock the data directory (or use a temporary one when SM, FRM, or CCM is mocked, so a
 * mock run never touches real data), open the WAL and storage, run crash recovery, optionally load
 * the sample database in one logged transaction, start CCM from the recovered transaction ids, then
 * build the Query Processor, which gives each session its own optimizer.
 *
 * <p>Shutdown ({@link #close()}): stop new transactions and wait for active ones, take a checkpoint
 * if they all finished, close storage before its WAL, then release the directory.
 */
public final class Engine implements AutoCloseable {
    private static final Set<Component> DATA_COMPONENTS =
            EnumSet.of(Component.SM, Component.FRM, Component.CCM);

    private final QueryProcessor processor;
    private final ProtocolType protocol;
    private final Path dataDirectory;
    private final Resources resources;
    private boolean closed;

    private Engine(
            QueryProcessor processor,
            ProtocolType protocol,
            Path dataDirectory,
            Resources resources) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.dataDirectory = dataDirectory;
        this.resources = resources;
    }

    /** The engine could not start. The message is the one line to show the user. */
    public static final class StartupException extends Exception {
        private static final long serialVersionUID = 1L;

        StartupException(String message) {
            super(message);
        }

        StartupException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** An engine over {@link MockQueryProcessor} that opens nothing ({@code --mock=qp}). */
    private static Engine mockProcessorOnly() {
        return new Engine(new MockQueryProcessor(), ProtocolType.LOCK_BASED, null, null);
    }

    /**
     * Starts the components in order; see the class description. On failure, whatever was opened is
     * closed again.
     *
     * @param config the settings
     * @return the running engine; close it to shut down
     * @throws StartupException if the directory is in use, recovery fails, seeding fails, or a
     *     component cannot be opened
     */
    public static Engine start(EngineConfig config) throws StartupException {
        Objects.requireNonNull(config, "config");
        if (config.mocks().contains(Component.QP)) {
            log(
                    "Query Processor: mock; no data is opened"
                            + (config.mocks().size() > 1 ? " (other mocks are ignored)." : "."));
            return mockProcessorOnly();
        }
        Resources resources = new Resources(config.drainTimeout());
        try {
            return open(config, resources);
        } catch (Exception e) {
            StartupException failure =
                    e instanceof StartupException startup
                            ? startup
                            : new StartupException(
                                    "The engine could not start: " + e.getMessage(), e);
            resources.close(false, (what, error) -> failure.addSuppressed(error));
            throw failure;
        }
    }

    private static Engine open(EngineConfig config, Resources r) throws Exception {
        Set<Component> mocks = config.mocks();
        Path dir;
        if (Collections.disjoint(mocks, DATA_COMPONENTS)) {
            dir = config.dir();
            r.lock = lockDirectory(dir);
            if (r.lock == null) {
                throw new StartupException(
                        "Data directory " + dir + " is in use by another process.");
            }
        } else {
            dir = Files.createTempDirectory("rafendb-");
            r.tempDir = dir;
            log(
                    "Mocked "
                            + names(mocks)
                            + ": using a temporary directory; "
                            + config.dir()
                            + " is not touched.");
        }

        RecoveryManager frm;
        WalSync wal;
        if (mocks.contains(Component.FRM)) {
            try (MockRecoveryManager mock = new MockRecoveryManager()) {
                frm = mock;
                wal = mock;
            }
        } else {
            r.logger = new Logger(dir.resolve("wal.log"));
            wal = r.logger;
            frm = null; // set once storage exists
        }
        r.storage =
                mocks.contains(Component.SM)
                        ? new MockStorageManager()
                        : storage(dir, config.bufferFrames(), wal);

        long nextTx = 1;
        if (r.logger != null) {
            try (FRManager real = new FRManager(r.logger, r.storage)) {
                r.frm = real;
                frm = real;
                if (!real.recoverFromCrash()) {
                    throw new StartupException("Crash recovery of " + dir + " failed.");
                }
            }
            r.storage.flush();
            nextTx = r.logger.getHighestTransactionId() + 1;
        }
        String seeded = seed(config, r.storage, frm, nextTx);

        ConcurrencyControl ccm;
        ProtocolType protocol;
        if (mocks.contains(Component.CCM)) {
            ccm = new MockConcurrencyControl();
            protocol = ccm.activeProtocol();
            if (config.protocol() != protocol) {
                log("--protocol is ignored with a mock CCM.");
            }
        } else {
            ConcurrencyControlManager real =
                    new ConcurrencyControlManager(
                            new TransactionManager(), protocol(config.protocol()), frm);
            real.initializeAfterRecovery(
                    r.frm != null ? r.frm.getRecoveryInfo() : new RecoveryInfo(0, 0));
            ccm = real;
            protocol = config.protocol();
        }
        r.ccm = ccm;

        StorageManager storage = r.storage;
        QueryProcessor processor = new QueryProcessorImpl(ccm, storage, () -> optimizer(storage));
        log(
                "Engine ready: SM "
                        + kind(mocks, Component.SM)
                        + ", FRM "
                        + kind(mocks, Component.FRM)
                        + ", CCM "
                        + (mocks.contains(Component.CCM) ? "mock" : protocol)
                        + ", QP real; data in "
                        + dir
                        + (r.tempDir != null ? " (temporary)" : "")
                        + "; "
                        + seeded
                        + ".");
        return new Engine(processor, protocol, dir, r);
    }

    /**
     * Loads the sample database in one transaction logged through FRM, before CCM hands out ids.
     * On failure the transaction is rolled back and the sample tables, created without logging,
     * are dropped again.
     */
    private static String seed(
            EngineConfig config, StorageManager storage, RecoveryManager frm, long nextTx)
            throws StartupException {
        if (!config.seed()) {
            return "not seeded";
        }
        if (config.mocks().contains(Component.SM)) {
            log("--seed is ignored with a mock SM.");
            return "not seeded";
        }
        if (!storage.listTables().isEmpty()) {
            log("--seed skipped: the database is not empty.");
            return "not seeded";
        }
        TxId tx = new TxId(nextTx);
        try {
            if (!frm.beginTransaction(tx)) {
                throw new IllegalStateException("FRM refused to begin " + tx);
            }
            storage.loadSampleDatabase(
                    change -> {
                        try {
                            return frm.writeLog(LogPayload.ofChange(tx, LogType.INSERT, change));
                        } catch (RecoveryInternalException e) {
                            throw new IllegalStateException("cannot log the sample data", e);
                        }
                    });
            if (!frm.commitTransaction(tx)) {
                throw new IllegalStateException("FRM refused to commit " + tx);
            }
            return "sample database loaded";
        } catch (StorageInternalException | RuntimeException e) {
            StartupException failure =
                    new StartupException(
                            "Loading the sample database failed: " + e.getMessage(), e);
            try {
                frm.rollbackTransaction(tx);
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
            }
            List<String> tables = new ArrayList<>(SampleDatabaseLoader.seededTables());
            Collections.reverse(tables);
            for (String table : tables) {
                try {
                    if (storage.listTables().contains(table)) {
                        storage.dropTable(table, true);
                    }
                } catch (StorageInternalException | RuntimeException drop) {
                    failure.addSuppressed(drop);
                }
            }
            try {
                storage.flush();
            } catch (StorageInternalException | RuntimeException flush) {
                failure.addSuppressed(flush);
            }
            throw failure;
        }
    }

    private static Protocol protocol(ProtocolType type) {
        return switch (type) {
            case LOCK_BASED -> new LockBasedProtocol();
            case TIMESTAMP_BASED -> new TimestampBasedProtocol();
        };
    }

    /**
     * Shuts the engine down: stop new transactions and wait for active ones (up to the drain
     * timeout), take a checkpoint if they all finished, close storage before its WAL, then release
     * the directory or delete the temporary one. Never throws; a failed step is printed and the
     * rest still run. Idempotent.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (resources == null) {
            return;
        }
        resources.close(
                true, (what, error) -> System.err.println("Engine: " + what + " failed: " + error));
        log("Engine closed.");
    }

    /**
     * Builds one Query Optimizer over the storage's metadata. It is not thread-safe and caches
     * metadata, so each session gets its own.
     *
     * @param storage where table schemas, statistics, and indexes are read from
     * @return a new optimizer
     */
    public static QueryOptimizer optimizer(StorageMetadata storage) {
        Catalog catalog = new CachingCatalog(new StorageManagerCatalog(storage));
        CostModel costs = new StatsCostModel(catalog);
        return new QueryOptimizerImpl(
                new QueryParser(),
                new QueryBinder(catalog),
                new JoinAccessPathOptimizer(storage, costs),
                new InitialPlanBuilder(costs));
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
        FileChannel lockFile = lockDirectory(directory);
        if (lockFile == null) {
            throw new IllegalStateException("database directory is already in use: " + directory);
        }
        Logger logger = null;
        StorageManager storage = null;
        try {
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

    /**
     * Returns where the data lives: the configured directory, or a temporary one when SM, FRM, or
     * CCM is mocked.
     *
     * @return the directory, or empty when the Query Processor is a mock and nothing is opened
     */
    public Optional<Path> dataDirectory() {
        return Optional.ofNullable(dataDirectory);
    }

    /**
     * Creates the directory and takes its exclusive {@code .lock}, held while the channel is open.
     *
     * @return the open channel, or {@code null} if another process (or this one) holds the lock
     */
    private static FileChannel lockDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        FileChannel channel =
                FileChannel.open(
                        directory.resolve(".lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null; // held by this JVM already
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            return null;
        }
        return channel;
    }

    private static String names(Set<Component> mocks) {
        return String.join(
                ",", mocks.stream().map(c -> c.shortName().toLowerCase(Locale.ROOT)).toList());
    }

    private static String kind(Set<Component> mocks, Component component) {
        return mocks.contains(component) ? "mock" : "real";
    }

    private static void log(String line) {
        System.out.println(line);
    }

    /** What a started engine holds, closed in reverse order of opening. */
    private static final class Resources {
        private final Duration drainTimeout;
        private FileChannel lock;
        private Path tempDir;
        private Logger logger;
        private StorageManager storage;
        private FRManager frm;
        private ConcurrencyControl ccm;

        Resources(Duration drainTimeout) {
            this.drainTimeout = drainTimeout;
        }

        interface Failure {
            void report(String what, Exception error);
        }

        private interface Step {
            void run() throws Exception;
        }

        /**
         * Closes everything that was opened. A graceful close drains transactions and takes a
         * checkpoint first; a failed startup does not.
         */
        void close(boolean graceful, Failure failure) {
            if (graceful && ccm != null) {
                boolean drained = false;
                try {
                    ccm.pauseNewTransactions();
                    drained = ccm.awaitNoActive(drainTimeout);
                    if (!drained) {
                        log(
                                "Active transactions did not finish within "
                                        + drainTimeout.toSeconds()
                                        + " s; no checkpoint taken.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.report("waiting for active transactions", e);
                } catch (RuntimeException e) {
                    failure.report("waiting for active transactions", e);
                }
                if (drained && frm != null) {
                    step(
                            "taking a checkpoint",
                            () -> {
                                if (frm.createCheckpoint(ccm)) {
                                    log("Checkpoint taken.");
                                } else {
                                    log("Checkpoint failed; the next start replays more WAL.");
                                }
                                ccm.pauseNewTransactions(); // the checkpoint resumed admission
                            },
                            failure);
                }
            }
            if (storage != null) {
                step("closing storage", storage::close, failure);
            }
            if (logger != null) {
                step("closing the WAL", logger::close, failure);
            }
            if (lock != null) {
                step("releasing the data directory", lock::close, failure);
            }
            if (tempDir != null) {
                step("deleting the temporary directory", () -> deleteTree(tempDir), failure);
            }
        }

        private static void step(String what, Step step, Failure failure) {
            try {
                step.run();
            } catch (Exception e) {
                failure.report(what, e);
            }
        }

        private static void deleteTree(Path root) throws IOException {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
