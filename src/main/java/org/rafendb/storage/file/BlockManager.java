package org.rafendb.storage.file;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import org.rafendb.common.error.StorageInternalException;

/**
 * SM2 physical I/O over fixed-size blocks in one database directory. Thread-safe: operations
 * are serialized per manager, including allocation and cached-handle lifecycle. The engine must
 * use one manager per database and release cached handles with closeFiles when restoring files.
 * I/O and corrupt-file failures become StorageInternalException. BufferManager owns cached page
 * contents and WAL ordering; the facade owns this manager's lifecycle. No method implicitly forces
 * writes or flushes buffer pages; callers use force after the required WAL synchronization.
 */
public class BlockManager implements AutoCloseable {
    protected final Path directory;
    private final int blockSize;
    private final Map<Path, FileChannel> files = new HashMap<>();
    private boolean closed;

    /** Constructs the manager without creating files or reading data. */
    public BlockManager(Path directory, int blockSize) {
        this.directory =
                Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (blockSize <= 0) throw new IllegalArgumentException("block size must be positive");
        this.blockSize = blockSize;
    }

    /** Returns bytes per physical block, including its header. */
    public int blockSize() {
        return blockSize;
    }

    /**
     * Creates the database directory if needed, then an empty catalog-assigned file without
     * retaining a handle. Names must be single relative file names. An existing entry is an error,
     * never truncated or reused; invalid names and I/O failures throw StorageInternalException.
     */
    public synchronized void create(String fileName) throws StorageInternalException {
        ensureOpen();
        Path file = resolveFile(fileName);
        try {
            Files.createDirectories(directory);
            closeFile(file);
            Files.createFile(file);
        } catch (IOException e) {
            throw new StorageInternalException("cannot create file " + fileName, e);
        }
    }

    /**
     * Reads exactly one existing block into a new big-endian buffer positioned at zero.
     * Missing blocks and files with a partial trailing block are storage errors.
     */
    public synchronized ByteBuffer read(BlockId block) throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(block, "block");
        Path file = resolveFile(block.fileName());
        try {
            FileChannel channel = channel(file);
            long offset = blockOffset(channel, block);
            ByteBuffer bytes = ByteBuffer.allocate(blockSize);
            while (bytes.hasRemaining()) {
                int count = channel.read(bytes, offset + bytes.position());
                if (count < 0) throw new EOFException("incomplete block: " + block);
                if (count == 0) throw new IOException("read made no progress: " + block);
            }
            return bytes.flip();
        } catch (IOException e) {
            throw new StorageInternalException("cannot read block " + block, e);
        }
    }

    /**
     * Overwrites one existing block; allocation must use append. Contents must have capacity
     * blockSize; all its bytes are written, preserving the caller's position and limit.
     * Does not force writes. An I/O failure may leave a partially overwritten block for FRM recovery.
     */
    public synchronized void write(BlockId block, ByteBuffer contents)
            throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(contents, "contents");
        Path file = resolveFile(block.fileName());
        if (contents.capacity() != blockSize) {
            throw new StorageInternalException(
                    "buffer capacity must equal block size " + blockSize);
        }
        try {
            FileChannel channel = channel(file);
            writeFully(channel, blockOffset(channel, block), contents.duplicate().clear());
        } catch (IOException e) {
            throw new StorageInternalException("cannot write block " + block, e);
        }
    }

    /**
     * Appends a zeroed block to an existing file without forcing it. Returns its identity only
     * after a complete write; on failure attempts to truncate back, retaining any cleanup failure.
     */
    public synchronized BlockId append(String fileName) throws StorageInternalException {
        ensureOpen();
        Path file = resolveFile(fileName);
        try {
            FileChannel channel = channel(file);
            long length = checkedSize(channel);
            if (length > Long.MAX_VALUE - blockSize) {
                throw new IOException("file size would overflow");
            }
            try {
                writeFully(channel, length, ByteBuffer.allocate(blockSize));
            } catch (IOException e) {
                try {
                    channel.truncate(length);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
            return new BlockId(fileName, length / blockSize);
        } catch (IOException e) {
            throw new StorageInternalException("cannot append to file " + fileName, e);
        }
    }

    /** Returns the number of physical blocks, rejecting a file with a partial trailing block. */
    public synchronized long size(String fileName) throws StorageInternalException {
        ensureOpen();
        Path file = resolveFile(fileName);
        try {
            return checkedSize(channel(file)) / blockSize;
        } catch (IOException e) {
            throw new StorageInternalException("cannot get size of file " + fileName, e);
        }
    }

    /** Forces preceding writes and necessary file metadata to stable storage. */
    public synchronized void force(String fileName) throws StorageInternalException {
        ensureOpen();
        Path file = resolveFile(fileName);
        try {
            channel(file).force(true);
        } catch (IOException e) {
            throw new StorageInternalException("cannot force file " + fileName, e);
        }
    }

    /**
     * Removes a file after the caller has discarded its buffer pages and stopped its users.
     * Missing files are a no-op. Names follow create's rules; directories are never removed.
     * Closes/removes the cached handle before deletion, even when the file is already missing.
     * Invalid names and I/O failures throw StorageInternalException; a close failure stops deletion.
     */
    public synchronized void delete(String fileName) throws StorageInternalException {
        ensureOpen();
        Path file = resolveFile(fileName);
        try {
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageInternalException("not a file: " + fileName);
            }
            closeFile(file);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new StorageInternalException("cannot delete file " + fileName, e);
        }
    }

    /** Opens only existing regular files, refusing symlinks; callers hold this manager's monitor. */
    private FileChannel channel(Path file) throws IOException {
        FileChannel channel = files.get(file);
        if (channel == null || !channel.isOpen()) {
            if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isRegularFile()) {
                throw new IOException("not an existing regular file: " + file);
            }
            channel =
                    FileChannel.open(
                            file,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            files.put(file, channel);
        }
        return channel;
    }

    /** Closes before removing the entry, leaving failed closes available for retry. */
    private void closeFile(Path file) throws IOException {
        FileChannel channel = files.get(file);
        if (channel != null) {
            channel.close();
            files.remove(file);
        }
    }

    /** Rejects truncated files before treating their length as a block count. */
    private long checkedSize(FileChannel channel) throws IOException {
        long length = channel.size();
        if (length % blockSize != 0) throw new IOException("file has a partial trailing block");
        return length;
    }

    /** Checks allocation before multiplication, so even an oversized block number cannot overflow. */
    private long blockOffset(FileChannel channel, BlockId block) throws IOException {
        if (block.number() >= checkedSize(channel) / blockSize) {
            throw new IOException("block does not exist: " + block);
        }
        return block.number() * blockSize;
    }

    /** Completes positional writes, failing on zero progress rather than spinning indefinitely. */
    private void writeFully(FileChannel channel, long offset, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            if (channel.write(bytes, offset + bytes.position()) <= 0) {
                throw new IOException("write made no progress");
            }
        }
    }

    /** Keeps catalog-assigned names directly in the database directory, excluding traversal. */
    private Path resolveFile(String fileName) throws StorageInternalException {
        Objects.requireNonNull(fileName, "fileName");
        try {
            Path name = Path.of(fileName);
            Path file = directory.resolve(name).normalize();
            if (fileName.isBlank()
                    || name.getRoot() != null
                    || name.getNameCount() != 1
                    || !directory.equals(file.getParent())) {
                throw new StorageInternalException("invalid file name: " + fileName);
            }
            return file;
        } catch (InvalidPathException e) {
            throw new StorageInternalException("invalid file name: " + fileName, e);
        }
    }

    /** Releases cached file handles without forcing writes for restore; remains usable afterwards. */
    public synchronized void closeFiles() throws StorageInternalException {
        ensureOpen();
        closeHandles();
    }

    /**
     * Rejects further I/O and releases all handles without forcing writes. Idempotent, retrying
     * failed closes; BufferManager must have been flushed/closed first.
     */
    @Override
    public synchronized void close() throws StorageInternalException {
        closed = true;
        closeHandles();
    }

    /** Attempts every close and retains unsuccessful entries and all failures for a later retry. */
    private void closeHandles() throws StorageInternalException {
        StorageInternalException failure = null;
        Iterator<Map.Entry<Path, FileChannel>> handles = files.entrySet().iterator();
        while (handles.hasNext()) {
            Map.Entry<Path, FileChannel> entry = handles.next();
            try {
                entry.getValue().close();
                handles.remove();
            } catch (IOException e) {
                if (failure == null) {
                    failure =
                            new StorageInternalException("cannot close file " + entry.getKey(), e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) throw failure;
    }

    /** Rejects I/O as soon as shutdown starts, including after a failed close. */
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("block manager is closed");
    }
}
