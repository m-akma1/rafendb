package org.rafendb.api.concurrency;

/** Startup-configurable CCM protocols; LOCK_BASED is the default. Both use table-level resources. */
public enum ProtocolType {
    /** Rigorous 2PL: retain S and X until durable commit or successful rollback. */
    LOCK_BASED,
    /** Strict timestamp ordering, selected instead of locking by startup configuration. */
    TIMESTAMP_BASED
}
