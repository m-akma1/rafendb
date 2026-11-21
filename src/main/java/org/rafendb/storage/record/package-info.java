/**
 * Row storage format (SM-02, SM-03). {@link RecordSerializer} encodes a row as a null bitmap, VARCHAR offsets and the column values; {@link SlottedPage} stores records in slots within one page; {@link TableScan} reads a table's live records lazily in Rid order.
 *
 * <p>Owner: G4 Storage Manager (SM3).
 */
package org.rafendb.storage.record;
