/**
 * Write-Ahead Logging (WAL) engine and log viewer (FR-01 to FR-03).
 *
 * <p>Manages monotonic LSN generation, binary log serialization, torn-tail detection, and disk flushing.
 *
 * <p>Owner: G5 Failure Recovery Manager (FR2).
 */
package org.rafendb.recovery.log;
