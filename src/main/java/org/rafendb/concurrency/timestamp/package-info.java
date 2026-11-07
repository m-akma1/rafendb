/**
 * Timestamp-based protocol (CC-08): {@link org.rafendb.concurrency.timestamp.TimestampProtocol} is
 * the contract and {@link org.rafendb.concurrency.timestamp.StrictTimestampProtocol} implements it
 * as table-level strict timestamp ordering (R-TS, W-TS and an uncommitted-writer marker per table,
 * no Thomas' Write Rule, deadlock-free).
 *
 * <p>Owner: G2 Concurrency Control Manager (CC4).
 */
package org.rafendb.concurrency.timestamp;
