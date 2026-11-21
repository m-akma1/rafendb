package org.rafendb.processor.session;

import java.util.Objects;
import java.util.function.Supplier;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.api.optimizer.QueryOptimizer;
import org.rafendb.api.processor.QueryProcessor;
import org.rafendb.api.processor.Session;
import org.rafendb.api.storage.StorageQuery;

/**
 * The Query Processor: opens one {@link Session} per client over the components that all sessions
 * share. Each session gets its own optimizer, since an optimizer need not be thread-safe.
 */
public final class QueryProcessorImpl implements QueryProcessor {
    private final ConcurrencyControl ccm;
    private final StorageQuery storage;
    private final Supplier<QueryOptimizer> optimizers;

    /**
     * Builds the processor.
     *
     * @param ccm the CCM; writes are logged through it, so it must also be {@link
     *     ConcurrencyLogging}
     * @param storage the storage, through its query interface
     * @param optimizers makes a new optimizer for each session
     * @throws IllegalArgumentException if {@code ccm} does not log changes
     */
    public QueryProcessorImpl(
            ConcurrencyControl ccm, StorageQuery storage, Supplier<QueryOptimizer> optimizers) {
        this.ccm = Objects.requireNonNull(ccm, "ccm");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.optimizers = Objects.requireNonNull(optimizers, "optimizers");
        if (!(ccm instanceof ConcurrencyLogging)) {
            throw new IllegalArgumentException("The CCM must also implement ConcurrencyLogging");
        }
    }

    @Override
    public Session openSession() {
        return new SessionImpl(ccm, storage, Objects.requireNonNull(optimizers.get(), "optimizer"));
    }
}
