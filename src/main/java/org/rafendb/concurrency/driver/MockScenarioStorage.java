package org.rafendb.concurrency.driver;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.verify.SerializabilityVerifier.Access;

/** Reversible in-memory values for the explicit SM and FRM mock mode. */
final class MockScenarioStorage extends Scenario implements RecoveryManager {
    private final Map<String, Integer> values = new HashMap<>();
    private final Map<TxId, List<ChangeRecord>> changes = new HashMap<>();
    private final Map<TxId, TxStatus> statuses = new HashMap<>();
    private long lsn;

    @Override
    RecoveryManager recovery() {
        return this;
    }

    @Override
    synchronized Optional<Integer> storedValue(String table) {
        return Optional.ofNullable(values.get(table));
    }

    @Override
    synchronized void write(ConcurrencyLogging logging, Access access, int value)
            throws ConcurrencyInternalException {
        String table = access.resource().table();
        ResourceId row = ResourceId.ofRow(table, new Rid(0, 0));
        logging.logChange(
                access.tx(),
                new ChangeRecord(
                        row,
                        storedValue(table).map(MockScenarioStorage::encode),
                        Optional.of(encode(value))));
        values.put(table, value);
    }

    private static byte[] encode(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    @Override
    public synchronized long writeLog(LogPayload payload) {
        payload.change()
                .ifPresent(
                        change ->
                                changes.computeIfAbsent(payload.tx(), ignored -> new ArrayList<>())
                                        .add(change));
        return ++lsn;
    }

    @Override
    public synchronized boolean beginTransaction(TxId tx) {
        statuses.put(tx, TxStatus.ACTIVE);
        return true;
    }

    @Override
    public synchronized boolean commitTransaction(TxId tx) {
        statuses.put(tx, TxStatus.COMMITTED);
        changes.remove(tx);
        return true;
    }

    @Override
    public synchronized boolean rollbackTransaction(TxId tx) {
        for (ChangeRecord change : changes.getOrDefault(tx, List.of()).reversed()) {
            String table = change.resource().table();
            if (change.beforeImage().isPresent()) {
                values.put(table, ByteBuffer.wrap(change.beforeImage().get()).getInt());
            } else {
                values.remove(table);
            }
        }
        changes.remove(tx);
        statuses.put(tx, TxStatus.ABORTED);
        return true;
    }

    @Override
    public synchronized TxStatus getTransactionStatus(TxId tx) {
        return statuses.get(tx);
    }

    @Override
    public void forceThrough(long lsn) {}

    @Override
    public boolean createCheckpoint(ConcurrencyControl concurrencyControl) {
        return false;
    }

    @Override
    public boolean recoverToPointInTime(long timestamp) {
        return false;
    }

    @Override
    public boolean recoverToPointInTime() {
        return false;
    }

    @Override
    public boolean recoverFromCrash() {
        return false;
    }

    @Override
    public void close() {}
}
