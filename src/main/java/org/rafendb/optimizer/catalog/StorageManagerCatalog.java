package org.rafendb.optimizer.catalog;

import java.util.List;
import java.util.Objects;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;

/**
 * Read-only adapter from the public storage manager interface to optimizer metadata.
 *
 * <p>Thread-safety follows the supplied storage manager's contract.
 */
public final class StorageManagerCatalog implements Catalog {
    private final StorageManager storageManager;

    /**
     * Creates an adapter; the storage manager must be non-null and thread-safe.
     *
     * @param storageManager source storage manager
     * @throws NullPointerException if {@code storageManager} is {@code null}
     */
    public StorageManagerCatalog(StorageManager storageManager) {
        this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
    }

    /** {@inheritDoc} */
    @Override
    public List<String> listTables() {
        return storageManager.listTables();
    }

    /** {@inheritDoc} */
    @Override
    public TableSchema getSchema(String table) {
        return storageManager.getSchema(table);
    }

    /** {@inheritDoc} */
    @Override
    public TableStats getStats(String table) throws StorageInternalException {
        return storageManager.getStats(table);
    }

    /** {@inheritDoc} */
    @Override
    public List<IndexInfo> listIndexes(String table) {
        return storageManager.listIndexes(table);
    }
}
