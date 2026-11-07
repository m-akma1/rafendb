package org.rafendb.optimizer.binder;

import org.rafendb.common.query.ParsedQuery;

/** Binds parsed SQL to schema metadata and performs semantic validation. */
public interface Binder {
    /** Returns a query enriched with binding information. */
    ParsedQuery bind(ParsedQuery parsed);
}
