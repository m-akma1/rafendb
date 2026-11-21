package org.rafendb.api.storage;

import java.util.List;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;

/**
 * Read-only storage metadata for query planning. Thread-safe.
 *
 * <p>Arguments and results are non-null; null arguments throw {@link NullPointerException}.
 * Names are passed already normalized. Returned lists are unmodifiable. After the owning storage
 * manager is closed, all methods throw {@link IllegalStateException}.
 */
public interface StorageMetadata {
    /**
     * Lists table names.
     *
     * @return the names, sorted
     */
    List<String> listTables();

    /**
     * Returns a table's schema, including its outgoing foreign keys.
     *
     * @param table the table name
     * @return the schema
     * @throws StorageRuntimeException if the table does not exist
     */
    TableSchema getSchema(String table);

    /**
     * Returns a table's statistics. Record and block counts are always current; the other fields
     * may be refreshed lazily.
     *
     * @param table the table name
     * @return the statistics
     * @throws StorageRuntimeException if the table does not exist
     * @throws StorageInternalException on a storage failure while refreshing the statistics
     */
    TableStats getStats(String table) throws StorageInternalException;

    /**
     * Lists a table's indexes.
     *
     * @param table the table name
     * @return the indexes
     * @throws StorageRuntimeException if the table does not exist
     */
    List<IndexInfo> listIndexes(String table);
}
