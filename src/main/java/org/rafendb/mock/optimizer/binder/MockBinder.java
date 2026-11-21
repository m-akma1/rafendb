package org.rafendb.mock.optimizer.binder;

import java.util.Objects;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.optimizer.binder.Binder;

/**
 * Pass-through stand-in for {@link org.rafendb.optimizer.binder.QueryBinder}: returns the query
 * unchanged, without resolving names or validating it. Stateless and thread-safe.
 */
public final class MockBinder implements Binder {
    /** {@inheritDoc} */
    @Override
    public ParsedQuery bind(ParsedQuery parsed) {
        Objects.requireNonNull(parsed, "parsed query");
        return parsed;
    }
}
