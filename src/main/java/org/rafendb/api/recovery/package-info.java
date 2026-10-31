/**
 * Failure Recovery Manager (FRM) contract: {@link org.rafendb.api.recovery.RecoveryManager}.
 *
 * <p>Exposes transaction logging, rollback, checkpointing, and crash recovery services to other
 * database components. The log record types it takes are in {@code org.rafendb.common.log}.
 *
 * <p>Owner: G5 Failure Recovery Manager.
 */
package org.rafendb.api.recovery;
