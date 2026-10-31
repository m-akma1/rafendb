package org.rafendb.recovery.checkpoint;

import org.rafendb.recovery.log.Logger;

/**
 * Mock dan kerangka awal Checkpoint Manager untuk Milestone 1 (FR-06).
 *
 * <p>Menyediakan implementasi mock metode pembuatan checkpoint sebelum implementasi
 * checkpointing penuh diintegrasikan pada milestone berikutnya.
 */
public class CheckpointManager {

    private final Logger logger;

    public CheckpointManager() {
        this(null);
    }

    public CheckpointManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Mock eksekusi pembuatan checkpoint.
     *
     * @return true pada implementasi mock
     */
    public boolean createCheckpoint() {
        return true;
    }
}
