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
    record ExecutedAccess(Access access, int value) {}

    private final List<ExecutedAccess> executed = new ArrayList<>();

    abstract RecoveryManager recovery();

    abstract Optional<Integer> storedValue(String table) throws StorageInternalException;

    abstract void write(ConcurrencyLogging logging, Access access, int value)
            throws ConcurrencyInternalException, StorageInternalException;

    abstract void close() throws Exception;

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

    final synchronized List<ExecutedAccess> schedule() {
        return List.copyOf(executed);
    }
}
