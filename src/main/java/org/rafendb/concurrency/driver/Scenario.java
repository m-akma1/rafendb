package org.rafendb.concurrency.driver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.txn.AccessType;
import org.rafendb.concurrency.verify.SerializabilityVerifier.Access;

/** Records accesses in the order they actually execute, for either scenario backend. */
abstract class Scenario {
    /** A recorded access with the value read or written. */
    record ExecutedAccess(Access access, int value) {}

    private final List<ExecutedAccess> executed = new ArrayList<>();

    /** Returns the recovery manager for this scenario. */
    abstract RecoveryManager recovery();

    /** Reads the test row, returning empty when it is absent. */
    abstract Optional<Integer> storedValue(String table) throws StorageInternalException;

    /** Logs and applies the new value of a test row. */
    abstract void write(ConcurrencyLogging logging, Access access, int value)
            throws ConcurrencyInternalException, StorageInternalException;

    /** Closes this scenario's backend resources. */
    abstract void close() throws Exception;

    /** Applies and records an allowed access under the same monitor. */
    final synchronized int apply(ConcurrencyLogging logging, Access access, int value)
            throws ConcurrencyInternalException, StorageInternalException {
        int observed = value;
        if (access.type() == AccessType.WRITE) {
            write(logging, access, value);
        } else {
            observed = storedValue(access.resource().table()).orElse(0);
        }
        executed.add(new ExecutedAccess(access, observed));
        return observed;
    }

    /** Returns an immutable snapshot of the recorded schedule. */
    final synchronized List<ExecutedAccess> schedule() {
        return List.copyOf(executed);
    }
}
