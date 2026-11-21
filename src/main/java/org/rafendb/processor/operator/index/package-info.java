/**
 * CREATE INDEX and index scan (QP-13). {@link
 * org.rafendb.processor.operator.index.CreateIndexOp} is a command action that takes a WRITE lock
 * on the table and builds a B+ tree or hash index through SM when executed. SM does not log it, so
 * the session runs it only outside an explicit transaction. {@link
 * org.rafendb.processor.operator.index.IndexScanOp} takes a READ lock, looks up matching Rids with
 * an equality or range search on the index, then fetches each row with its Rid, skipping Rids that
 * no longer hold a live record.
 *
 * <p>SM is used only through {@link org.rafendb.api.storage.StorageQuery}.
 *
 * <p>Owner: G1 Query Processor (QP5).
 */
package org.rafendb.processor.operator.index;
