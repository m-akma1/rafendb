/**
 * Table schemas, constraints, indexes and statistics (SM-08, SM-09). {@link CatalogManager} keeps them in memory and persists them as a binary snapshot and an allocation file, each written to a temporary file first and then moved into place.
 *
 * <p>Owner: G4 Storage Manager (SM4).
 */
package org.rafendb.storage.catalog;
