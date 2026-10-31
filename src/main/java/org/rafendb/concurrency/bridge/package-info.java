/**
 * FRM bridge (CC-03 to CC-05): forwarding to FRM under WAL, the single abort flow, and
 * post-recovery initialization. The FRM contract it calls is not defined yet. {@link org.rafendb.concurrency.bridge.TransactionStates} and {@link
 * org.rafendb.concurrency.bridge.ProtectionRelease} stand in for the coordinator's transaction
 * table and protocol strategy until CC1's code exists.
 *
 * <p>Owner: G2 Concurrency Control Manager (CC3).
 */
package org.rafendb.concurrency.bridge;
