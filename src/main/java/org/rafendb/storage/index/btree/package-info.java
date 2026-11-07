/**
 * B+ tree index stored in pages: equality and range search over (key, Rid) entries, with leaves
 * chained in key order. Deletes do not merge nodes.
 *
 * <p>Owner: G4 Storage Manager (SM5).
 */
package org.rafendb.storage.index.btree;
