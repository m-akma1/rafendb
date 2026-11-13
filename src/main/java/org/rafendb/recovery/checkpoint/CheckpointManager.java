package org.rafendb.recovery.checkpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;

/**
 * Creates quiescent checkpoints for the Failure Recovery Manager.
 *
 * <p>A checkpoint does not stop transactions that are already running. It only closes admission
 * for new transactions, waits for the existing transactions to finish, and resumes admission in a
 * {@code finally} block. The pointer file is updated only after both checkpoint records and the
 * storage flush are durable.
 */
public final class CheckpointManager {

    private static final int CHECKPOINT_TRANSACTION = 0;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);

    private final Logger logger;
    private final ConcurrencyControl concurrencyControl;
    private final StorageManager storageManager;
    private final Duration awaitTimeout;
    private final Path checkpointPointer;
    private final Object checkpointLock = new Object();

    /** Creates a logger-only checkpoint manager with an in-memory WAL. */
    public CheckpointManager() {
        this(new Logger());
    }

    /**
     * Creates a logger-only checkpoint manager. This constructor is retained for the recovery
     * driver and creates durable checkpoint markers when the logger has a file path.
     *
     * @param logger WAL logger, not null
     */
    public CheckpointManager(Logger logger) {
        this(logger, null, null, DEFAULT_TIMEOUT, pointerFor(logger));
    }

    /**
     * Creates a checkpoint manager connected to CCM and SM.
     *
     * @param logger WAL logger, not null
     * @param concurrencyControl CCM admission and active-transaction tracker, not null
     * @param storageManager storage manager to flush, not null
     */
    public CheckpointManager(
            Logger logger, ConcurrencyControl concurrencyControl, StorageManager storageManager) {
        this(logger, concurrencyControl, storageManager, DEFAULT_TIMEOUT, pointerFor(logger));
    }

    /**
     * Creates a checkpoint manager with an explicit wait timeout.
     *
     * @param logger WAL logger, not null
     * @param concurrencyControl CCM admission and active-transaction tracker, not null
     * @param storageManager storage manager to flush, not null
     * @param awaitTimeout maximum time to wait for active transactions, not null and non-negative
     */
    public CheckpointManager(
            Logger logger,
            ConcurrencyControl concurrencyControl,
            StorageManager storageManager,
            Duration awaitTimeout) {
        this(logger, concurrencyControl, storageManager, awaitTimeout, pointerFor(logger));
    }

    CheckpointManager(
            Logger logger,
            ConcurrencyControl concurrencyControl,
            StorageManager storageManager,
            Duration awaitTimeout,
            Path checkpointPointer) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        if ((concurrencyControl == null) != (storageManager == null)) {
            throw new NullPointerException(
                    "concurrencyControl and storageManager must be supplied together");
        }
        this.concurrencyControl = concurrencyControl;
        this.storageManager = storageManager;
        this.awaitTimeout = Objects.requireNonNull(awaitTimeout, "awaitTimeout must not be null");
        if (awaitTimeout.isNegative()) {
            throw new IllegalArgumentException("awaitTimeout must not be negative");
        }
        this.checkpointPointer =
                Objects.requireNonNull(checkpointPointer, "checkpointPointer must not be null");
    }

    /**
     * Executes one quiescent checkpoint.
     *
     * @return true only when the complete checkpoint and its pointer are durable
     */
    public boolean createCheckpoint() {
        synchronized (checkpointLock) {
            boolean paused = false;
            try {
                if (concurrencyControl != null) {
                    concurrencyControl.pauseNewTransactions();
                    paused = true;
                    if (!concurrencyControl.awaitNoActive(awaitTimeout)) {
                        return false;
                    }
                }

                logger.logTransactionEvent(CHECKPOINT_TRANSACTION, LogType.CHECKPOINT_BEGIN);
                long beginLsn = logger.getCurrentLSN();
                logger.forceThrough(beginLsn);

                if (storageManager != null) {
                    storageManager.flush();
                }

                logger.logTransactionEvent(CHECKPOINT_TRANSACTION, LogType.CHECKPOINT_END);
                long endLsn = logger.getCurrentLSN();
                logger.forceThrough(endLsn);
                writePointerAtomically(endLsn);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (StorageInternalException | IOException | RuntimeException e) {
                return false;
            } finally {
                if (paused) {
                    concurrencyControl.resumeNewTransactions();
                }
            }
        }
    }

    /**
     * Returns the LSN recorded as the last complete checkpoint, or zero when none exists.
     *
     * @return last complete checkpoint LSN
     */
    public long getLastCheckpointLsn() {
        try {
            if (!Files.exists(checkpointPointer)) {
                return 0;
            }
            String value = Files.readString(checkpointPointer, StandardCharsets.US_ASCII).trim();
            return value.isEmpty() ? 0 : Long.parseLong(value);
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** @return the file containing the last-complete-checkpoint pointer */
    public Path getCheckpointPointer() {
        return checkpointPointer;
    }

    private void writePointerAtomically(long lsn) throws IOException {
        Path parent = checkpointPointer.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary =
                checkpointPointer.resolveSibling(
                        checkpointPointer.getFileName()
                                + ".tmp-"
                                + Thread.currentThread().threadId());
        try {
            Files.writeString(
                    temporary,
                    Long.toString(lsn) + System.lineSeparator(),
                    StandardCharsets.US_ASCII);
            try {
                Files.move(
                        temporary,
                        checkpointPointer,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, checkpointPointer, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path pointerFor(Logger logger) {
        String logFilePath = logger.getLogFilePath();
        if (logFilePath == null || logFilePath.isEmpty()) {
            return Path.of(System.getProperty("java.io.tmpdir"), "rafendb-checkpoint.pointer");
        }
        return Path.of(logFilePath + ".checkpoint");
    }
}
