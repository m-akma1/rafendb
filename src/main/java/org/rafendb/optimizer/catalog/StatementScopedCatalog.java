package org.rafendb.optimizer.catalog;

/**
 * Catalog that supports refreshing its metadata view
 * at the beginning of each statement.
 */
public interface StatementScopedCatalog extends Catalog {

    void beginStatement();
}