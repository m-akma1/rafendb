package org.rafendb.storage.buffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.rafendb.api.storage.WalSync;
import org.rafendb.common.error.RafenDBInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.storage.file.BlockId;
import org.rafendb.storage.file.BlockManager;

/**
 * SM2 shared Clock buffer. Thread-safe metadata; pin additionally acquires a reentrant
 * exclusive page latch, released by the matching unpin on the same thread. Callers mark changes
 * dirty and unpin in finally. Cursors must unpin before returning a row; callers must never
 * enter the facade while holding a page latch or wait for a latch under the pool monitor.
 * The injected block manager is shared with indexes and is closed only by the facade.
 */
public class BufferManager implements AutoCloseable {
    protected final BlockManager blocks;
    protected final WalSync wal;
    protected final int capacity;
    private final Map<BlockId, Frame> cached = new HashMap<>();
    private final Frame[] frames;
    private final ReentrantReadWriteLock boundary = new ReentrantReadWriteLock(true);
    private int clock;
    private boolean closed;

    /** One resident page; pins/reference are pool-protected, mutations are page-latched. */
    private static final class Frame {
        final BlockId block;
        final ByteBuffer bytes;
        final ReentrantLock latch = new ReentrantLock();
        int pins;
        boolean referenced;
        boolean dirty;
        boolean logged;
        long maxLsn;

        /** Attaches a newly read page without changing its bytes. */
        Frame(BlockId block, ByteBuffer bytes) {
            this.block = block;
            this.bytes = bytes;
        }
    }

    /** Wires physical I/O and WAL without loading pages. */
    public BufferManager(BlockManager blocks, WalSync wal, int capacity) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.wal = Objects.requireNonNull(wal, "wal");
        if (capacity <= 0) throw new IllegalArgumentException("buffer capacity must be positive");
        this.capacity = capacity;
        this.frames = new Frame[capacity];
    }

    /**
     * Pins/latches a page and returns a view of its shared bytes, position zero, big-endian. The view
     * is valid only until unpin. Throws StorageInternalException when all frames are pinned.
     */
    public ByteBuffer pin(BlockId block) throws StorageInternalException {
        Objects.requireNonNull(block, "block");
        boundary.readLock().lock();
        Frame frame = null;
        boolean latched = false;
        boolean success = false;
        try {
            frame = retain(block, true);
            frame.latch.lock();
            latched = true;
            ByteBuffer view = frame.bytes.duplicate().clear().order(ByteOrder.BIG_ENDIAN);
            success = true;
            return view;
        } finally {
            if (!success) release(frame, latched);
        }
    }

    /** Releases one pin and its page latch on the owning thread; unbalanced unpin is an error. */
    public synchronized void unpin(BlockId block) {
        release(ownedFrame(block), true);
    }

    /** Marks a pinned page dirty and retains the highest LSN among all its changes. */
    public synchronized void markDirty(BlockId block, long lsn) {
        Frame frame = ownedFrame(block);
        if (!frame.logged || lsn > frame.maxLsn) frame.maxLsn = lsn;
        frame.logged = true;
        frame.dirty = true;
    }

    /**
     * Marks a pinned page changed by reservation/cancellation or an index build without a new WAL
     * record. Retains any existing logged LSN, so flushing never bypasses WAL for earlier changes.
     * Not for normal row insert/update/delete or recovery; those must use markDirty with a real LSN.
     */
    public synchronized void markUnloggedDirty(BlockId block) {
        ownedFrame(block).dirty = true;
    }

    /**
     * Flushes one dirty page: WAL forceThrough -> block write -> file force -> clean.
     * Skips only forceThrough when the frame has no logged mutations, never for a fabricated LSN.
     * A failure preserves the dirty page; concurrent changes must not be marked clean accidentally.
     */
    public void flushPage(BlockId block) throws StorageInternalException {
        Objects.requireNonNull(block, "block");
        boundary.readLock().lock();
        Frame frame = null;
        boolean latched = false;
        try {
            frame = retain(block, false);
            if (frame == null) return;
            frame.latch.lock();
            latched = true;
            flushFrame(frame);
        } finally {
            release(frame, latched);
        }
    }

    /**
     * Flushes table and index pages at one mutation boundary, waiting for other threads' pins.
     * The calling thread must unpin first; attempting a lock upgrade is a storage error.
     */
    public void flushAll() throws StorageInternalException {
        lockBoundary();
        try {
            ensureOpen();
            for (Frame frame : frames) {
                if (frame != null) flushFrame(frame);
            }
        } finally {
            boundary.writeLock().unlock();
        }
    }

    /** Discards one file's pages without flushing; rejects pinned pages. Used before DROP. */
    public void invalidateFile(String fileName) throws StorageInternalException {
        discard(Objects.requireNonNull(fileName, "fileName"));
    }

    /** Discards all pages without flushing; caller guarantees no cursors, pins, or transactions. */
    public void invalidateAll() throws StorageInternalException {
        discard(null);
    }

    /**
     * Waits for pins, rejects further use, then flushes/releases pages without closing BlockManager.
     * The caller must unpin first. A flush failure retains pages for a subsequent close retry;
     * other operations remain rejected. Idempotent after success.
     */
    @Override
    public void close() throws StorageInternalException {
        lockBoundary();
        try {
            closed = true;
            for (Frame frame : frames) {
                if (frame != null) flushFrame(frame);
            }
            cached.clear();
            Arrays.fill(frames, null);
            clock = 0;
        } finally {
            boundary.writeLock().unlock();
        }
    }

    /** Reserves a pin before waiting for a latch, preventing eviction of owners and waiters. */
    private synchronized Frame retain(BlockId block, boolean load) throws StorageInternalException {
        ensureOpen();
        Frame frame = cached.get(block);
        if (frame == null && load) {
            int slot = victim();
            Frame previous = frames[slot];
            // shortcut: I/O saat cache miss diserialkan; pisahkan bila contention terukur.
            if (previous != null) flushFrame(previous);
            Frame loaded = new Frame(block, blocks.read(block));
            if (previous != null) cached.remove(previous.block);
            frames[slot] = loaded;
            cached.put(block, loaded);
            frame = loaded;
        }
        if (frame != null) {
            frame.pins++;
            if (load) frame.referenced = true;
        }
        return frame;
    }

    /** Clock gives unpinned pages a second chance and fails when no frame can be replaced. */
    private int victim() throws StorageInternalException {
        for (int pass = 0; pass < 2; pass++) {
            for (int seen = 0; seen < capacity; seen++) {
                int slot = clock;
                clock = (clock + 1) % capacity;
                Frame frame = frames[slot];
                if (frame == null) return slot;
                if (frame.pins == 0) {
                    if (!frame.referenced) return slot;
                    frame.referenced = false;
                }
            }
        }
        throw new StorageInternalException("all buffer frames are pinned");
    }

    /** Requires a pin and its matching latch on this thread; callers hold the pool monitor. */
    private Frame ownedFrame(BlockId block) {
        Objects.requireNonNull(block, "block");
        ensureOpen();
        Frame frame = cached.get(block);
        if (frame == null || !frame.latch.isHeldByCurrentThread()) {
            throw new IllegalStateException("page is not pinned by this thread: " + block);
        }
        return frame;
    }

    /** Unlocks before making a frame evictable, then releases the pin's maintenance protection. */
    private void release(Frame frame, boolean latched) {
        synchronized (this) {
            if (frame != null) {
                if (latched) frame.latch.unlock();
                frame.pins--;
            }
        }
        boundary.readLock().unlock();
    }

    /** Uses the same WAL/write/force path under a page latch or exclusive access to an unpinned page. */
    private void flushFrame(Frame frame) throws StorageInternalException {
        if (!frame.dirty) return;
        if (frame.logged) {
            try {
                wal.forceThrough(frame.maxLsn);
            } catch (RafenDBInternalException | RuntimeException e) {
                throw new StorageInternalException("cannot force WAL for " + frame.block, e);
            }
        }
        blocks.write(frame.block, frame.bytes);
        blocks.force(frame.block.fileName());
        frame.dirty = false;
    }

    /** Atomically checks all matching pages before discarding any; null selects the whole pool. */
    private void discard(String fileName) throws StorageInternalException {
        boundary.readLock().lock();
        try {
            synchronized (this) {
                ensureOpen();
                for (Frame frame : frames) {
                    if (frame != null
                            && (fileName == null || fileName.equals(frame.block.fileName()))
                            && frame.pins != 0) {
                        throw new StorageInternalException(
                                "cannot discard pinned page " + frame.block);
                    }
                }
                for (int slot = 0; slot < capacity; slot++) {
                    Frame frame = frames[slot];
                    if (frame != null
                            && (fileName == null || fileName.equals(frame.block.fileName()))) {
                        cached.remove(frame.block);
                        frames[slot] = null;
                    }
                }
                if (fileName == null) clock = 0;
            }
        } finally {
            boundary.readLock().unlock();
        }
    }

    /** Prevents read-to-write lock upgrades while holding a pin, which would deadlock. */
    private void lockBoundary() throws StorageInternalException {
        if (boundary.getReadHoldCount() != 0) {
            throw new StorageInternalException("unpin pages before flushAll or close");
        }
        boundary.writeLock().lock();
    }

    /** Rejects normal operations once shutdown starts, including after a failed flush. */
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("buffer manager is closed");
    }
}
