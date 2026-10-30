package org.rafendb.api.processor;

import java.util.List;
import org.rafendb.common.expr.ColumnRef;
import org.rafendb.common.tuple.Row;

/**
 * Proposed row cursor. Each instance belongs to one execution and is not thread-safe.
 * Inputs/services are supplied when constructed; no SQL mutation is implied by this API.
 */
public interface Operator extends AutoCloseable {
    /**
     * Starts or resets reading to the beginning, even while open, exhausted, or closed.
     * Reinitializes children as needed and releases previous reading resources before replacement.
     * After a failure, close must remain safe for cleanup. No arguments or result.
     */
    void open();

    /**
     * Returns non-null immutable ordered column identities, available before open and after close,
     * including empty results. Row positions correspond to this order. Join concatenates outer
     * then inner; types and a complete shared Schema class remain pending team agreement.
     */
    List<ColumnRef> schema();

    /**
     * Returns a non-null row or null at exhaustion; exhaustion remains stable.
     * @throws IllegalStateException if not open or already closed
     */
    Row next();

    /** Releases resources, including before open; repeated calls are harmless. No result. */
    @Override
    void close();
}
