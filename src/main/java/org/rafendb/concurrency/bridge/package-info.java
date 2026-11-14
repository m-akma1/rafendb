/**
 * FRM bridge (CC-03 to CC-05): forwarding to FRM under WAL through {@link
 * org.rafendb.api.recovery.RecoveryManager}, the single abort flow, and post-recovery
 * initialization. Status changes go through {@link
 * org.rafendb.concurrency.txn.TransactionManager}, and protection is released through the active
 * {@link org.rafendb.concurrency.protocol.Protocol}.
 *
 * <p>Owner: G2 Concurrency Control Manager (CC3).
 */
package org.rafendb.concurrency.bridge;
