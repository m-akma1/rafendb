/**
 * Mock of the Storage Manager API ({@code api.storage}). It serves hard-coded,
 * immutable dummy
 * tables and indexes and never changes them, so other components can run their
 * drivers with {@code
 * --mock=storage} before the real Storage Manager exists.
 *
 * <p>
 * Owner: Storage Manager.
 */
package org.rafendb.mock.storage;
