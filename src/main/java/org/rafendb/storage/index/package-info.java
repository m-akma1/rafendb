/**
 * Index management (SM-10, SM-11): building indexes on one column, keeping them in step with
 * insert, update and delete, and finding Rids by key or key range. {@link
 * org.rafendb.storage.index.IndexManager} is the only entry point, used by the facade.
 *
 * <p>Owner: G4 Storage Manager (SM5).
 */
package org.rafendb.storage.index;
