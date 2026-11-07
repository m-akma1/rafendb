package org.rafendb.optimizer.catalog;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;

/** Per-query metadata cache; construct a new instance for each query because statistics change. */
public final class CachingCatalog implements Catalog {
    private final Catalog delegate;
    private final Map<String, TableSchema> schemas = new ConcurrentHashMap<>();
    private final Map<String, TableStats> stats = new ConcurrentHashMap<>();
    private final Map<String, List<IndexInfo>> indexes = new ConcurrentHashMap<>();

    /** Wraps a non-null catalog. Instances are safe for concurrent calls within one query. */
    /**
     * Wraps a non-null catalog. Construct a new instance per query so statistics do not outlive
     * the query's planning snapshot.
     *
     * @param delegate metadata source
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public CachingCatalog(Catalog delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> listTables() {
        return List.copyOf(delegate.listTables());
    }

    /** {@inheritDoc} */
    @Override
    public TableSchema getSchema(String table) {
        return schemas.computeIfAbsent(table, delegate::getSchema);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized TableStats getStats(String table) throws StorageInternalException {
        TableStats value = stats.get(table);
        if (value == null) {
            value = delegate.getStats(table);
            stats.put(table, value);
        }
        return value;
    }

    /** {@inheritDoc} */
    @Override
    public List<IndexInfo> listIndexes(String table) {
        return indexes.computeIfAbsent(table, t -> List.copyOf(delegate.listIndexes(t)));
    }
}
