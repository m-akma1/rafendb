package org.rafendb.optimizer.catalog;

import java.util.List;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;

/**
 * Read-only metadata view used by binding and cost estimation. Implementations must be thread-safe.
 */
public interface Catalog {
    /**
     * Lists known tables.
     *
     * @return canonical table names in catalog order, never {@code null}
     */
    List<String> listTables();

    /**
     * Returns the schema, or throws the storage manager's missing-table exception.
     *
     * @param table non-null table name
     * @return non-null schema
     */
    TableSchema getSchema(String table);

    /**
     * Returns current table statistics.
     *
     * @param table non-null table name
     * @return non-null statistics
     * @throws org.rafendb.common.error.StorageInternalException if refreshing statistics fails
     */
    TableStats getStats(String table) throws StorageInternalException;

    /**
     * Lists indexes on the table.
     *
     * @param table non-null table name
     * @return an immutable or defensive index list, never {@code null}
     */
    List<IndexInfo> listIndexes(String table);
}
