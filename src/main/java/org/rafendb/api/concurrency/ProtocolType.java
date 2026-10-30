package org.rafendb.api.concurrency;

/** The concurrency control protocols the CCM supports*/
public enum ProtocolType {
    /** Lock-based protocol*/
    LOCK_BASED,
    /** Timestamp-based protocol*/
    TIMESTAMP_BASED
}
