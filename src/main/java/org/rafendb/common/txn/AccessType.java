package org.rafendb.common.txn;

/** Kind of access a transaction requests from the CCM. */
public enum AccessType {
    /** Read; needs a shared lock under the lock-based protocol. */
    READ,
    /** Insert, update or delete; needs an exclusive lock under the lock-based protocol. */
    WRITE
}
