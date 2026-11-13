package org.rafendb.storage.index;

import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.util.Objects;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.storage.file.BlockId;
import org.rafendb.storage.file.BlockManager;

/**
 * A small durable record of whether the last session shut down cleanly, deciding if the indexes on
 * disk can be trusted at startup. It is one block of its own file: {@code int} magic, {@code byte}
 * state. It goes straight through the block manager, not the buffer, because it is written after
 * the buffer has closed.
 *
 * <p>The block is overwritten in place and forced. Every way that can go wrong in a crash reads as
 * not clean, and costs a rebuild: a forced write that did not finish leaves the old state only if
 * the session it starts had not begun changing anything yet, and a file whose directory entry was
 * lost reads as missing. Nothing can make stale indexes read as clean, because a session marks
 * itself not clean, durably, before any index changes.
 *
 * <p>One case is an error rather than a rebuild: a crash during the first creation of the file can
 * leave a partial block, which the block manager rejects. Deleting the file then recovers.
 *
 * <p>Not thread-safe; {@link IndexManager} holds its monitor around every call.
 */
final class IndexSessionMarker {
    /** The marker's file, in the database directory. */
    static final String FILE = "index.session";

    private static final int MAGIC = 0x52534553;
    private static final int MAGIC_OFFSET = 0;
    private static final int STATE_OFFSET = 4;
    private static final byte NOT_CLEAN = 1;
    private static final byte CLEAN = 2;
    private static final BlockId BLOCK = new BlockId(FILE, 0);

    private final BlockManager blocks;

    /**
     * Attaches to the database's marker without touching it.
     *
     * @param blocks the database's block manager
     * @throws NullPointerException if {@code blocks} is {@code null}
     */
    IndexSessionMarker(BlockManager blocks) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
    }

    /**
     * Reads whether the last session shut down cleanly.
     *
     * @return {@code true} only if the marker says so; a missing marker, or one allocated but never
     *     written, means {@code false}
     * @throws StorageInternalException on an I/O failure or if the marker is corrupt, which is never
     *     taken for a clean shutdown
     */
    boolean isClean() throws StorageInternalException {
        if (!hasBlock()) {
            return false;
        }
        ByteBuffer block = blocks.read(BLOCK);
        int magic = block.getInt(MAGIC_OFFSET);
        byte state = block.get(STATE_OFFSET);
        if (magic == 0 && state == 0) {
            return false;
        }
        if (magic != MAGIC || (state != CLEAN && state != NOT_CLEAN)) {
            throw new StorageInternalException("corrupt index session marker");
        }
        return state == CLEAN;
    }

    /**
     * Durably records a state, creating the marker if needed.
     *
     * @param clean whether the session has shut down cleanly
     * @throws StorageInternalException on an I/O failure; a state written before stays in force
     */
    void write(boolean clean) throws StorageInternalException {
        if (!hasBlock()) {
            if (!exists()) {
                blocks.create(FILE);
            }
            blocks.append(FILE);
        }
        ByteBuffer block = ByteBuffer.allocate(blocks.blockSize());
        block.putInt(MAGIC_OFFSET, MAGIC);
        block.put(STATE_OFFSET, clean ? CLEAN : NOT_CLEAN);
        blocks.write(BLOCK, block);
        blocks.force(FILE);
    }

    /**
     * Returns whether the marker's file has its block.
     *
     * @return {@code true} if the block can be read
     * @throws StorageInternalException on an I/O failure
     */
    private boolean hasBlock() throws StorageInternalException {
        return exists() && blocks.size(FILE) > 0;
    }

    /**
     * Returns whether the marker's file exists. The block manager reports a missing file only
     * through the cause of its error.
     *
     * @return {@code true} if it exists
     * @throws StorageInternalException on any I/O failure other than a missing file
     */
    private boolean exists() throws StorageInternalException {
        try {
            blocks.size(FILE);
            return true;
        } catch (StorageInternalException e) {
            if (e.getCause() instanceof NoSuchFileException) {
                return false;
            }
            throw e;
        }
    }
}
