/**
 * The Storage Manager driver (SM-15). {@link StorageDriver} runs storage commands on persistent data and a WAL, in {@code data/} by default. Each write command is its own transaction, and startup recovers before it accepts commands.
 *
 * <p>Owner: G4 Storage Manager (SM1).
 */
package org.rafendb.storage.driver;
