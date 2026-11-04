package org.rafendb.storage.file;

import java.util.Objects;

/**
 * A physical block, identified by a catalog-assigned file name and a zero-based block number.
 * Unlike a row Rid, this identity distinguishes table and index files in the shared buffer.
 *
 * @param fileName a non-blank name relative to the database directory, never an SQL alias
 * @param number the nonnegative block number
 */
public record BlockId(String fileName, long number) {
    /** Validates the identity; BlockManager also checks that resolved paths stay in its directory. */
    public BlockId {
        Objects.requireNonNull(fileName, "fileName");
        if (fileName.isBlank()) throw new IllegalArgumentException("blank file name");
        if (number < 0) throw new IllegalArgumentException("negative block number");
    }
}
