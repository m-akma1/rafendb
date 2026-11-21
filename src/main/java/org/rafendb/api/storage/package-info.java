/**
 * Storage Manager contracts: {@link org.rafendb.api.storage.StorageMetadata} for query planning,
 * {@link org.rafendb.api.storage.StorageQuery} for query execution,
 * {@link org.rafendb.api.storage.StorageDefinition} for creating and dropping tables,
 * {@link org.rafendb.api.storage.StorageRecovery} for recovery, and the complete
 * {@link org.rafendb.api.storage.StorageManager} facade. The same storage object implements all
 * five interfaces; consumers depend on the view they need. Also provides the
 * {@link
 * org.rafendb.api.storage.RecordCursor} and the
 * {@link org.rafendb.api.storage.WalSync} hook.
 *
 * <p>
 * Owner: Storage Manager.
 */
package org.rafendb.api.storage;
