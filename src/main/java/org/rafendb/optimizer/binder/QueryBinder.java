package org.rafendb.optimizer.binder;

import org.rafendb.common.error.ValidationException;
import org.rafendb.common.query.ParsedQuery;

public class QueryBinder {
    public void bindAndValidate(ParsedQuery parsed) throws ValidationException {
        // TODO: Validate table/column identifiers against StorageManagerCatalog, expand 'SELECT *' into explicit column references
        // and also enforce type consistency for expressions
    }
}
