package org.rafendb.concurrency.bridge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;

/** Test transaction table that logs changes to a shared call list. */
final class FakeTransactionStates implements TransactionStates {
    private final List<String> calls;
    private final Map<TxId, TxStatus> statuses = new HashMap<>();

    FakeTransactionStates(List<String> calls) {
        this.calls = calls;
    }

    void put(TxId tx, TxStatus status) {
        statuses.put(tx, status);
    }

    @Override
    public TxStatus status(TxId tx) {
        TxStatus status = statuses.get(Objects.requireNonNull(tx, "tx"));
        if (status == null) {
            throw new IllegalArgumentException("unknown transaction " + tx);
        }
        return status;
    }

    @Override
    public void setStatus(TxId tx, TxStatus next) {
        TxStatus current = status(tx);
        if (current.isFinal()) {
            throw new IllegalStateException(tx + " is already " + current);
        }
        statuses.put(tx, Objects.requireNonNull(next, "next"));
        calls.add(tx + " " + next);
    }

    @Override
    public void resetAbove(long highWater) {
        statuses.clear();
        calls.add("resetAbove " + highWater);
    }
}
