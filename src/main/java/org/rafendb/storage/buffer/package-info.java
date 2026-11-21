/**
 * Buffer pool (SM-12, SM-13). {@link BufferManager} is one shared pool with Clock replacement and a latch per page. Before it writes a dirty page, it forces the WAL through the page's last LSN.
 *
 * <p>Owner: G4 Storage Manager (SM2).
 */
package org.rafendb.storage.buffer;
