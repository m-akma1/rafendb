package org.rafendb.optimizer.binder;

import java.util.Objects;
import org.rafendb.common.query.ParsedQuery;

/** Pass-through placeholder until AST and catalog contracts are available. */
public final class MockBinder implements Binder {
    @Override
    public ParsedQuery bind(ParsedQuery parsed) {
        Objects.requireNonNull(parsed, "parsed query");
        // TODO: Bind AST expressions; resolve tables, columns and aliases; expand SELECT *;
        // handle NATURAL JOIN and integrate with the agreed catalog API.
        return parsed;
    }
}
