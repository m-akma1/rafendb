/**
 * FRM bridge (CC-03 to CC-05): forwarding to FRM under WAL, the single abort flow, and
 * post-recovery initialization. {@link org.rafendb.concurrency.bridge.FrmGateway} and {@link
 * org.rafendb.concurrency.bridge.TransactionStates} stand in for the FRM and the coordinator's
 * transaction table until their real interfaces exist.
 *
 * <p>Owner: G2 Concurrency Control Manager (CC3).
 */
package org.rafendb.concurrency.bridge;
