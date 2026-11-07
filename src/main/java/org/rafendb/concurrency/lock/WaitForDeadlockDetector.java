package org.rafendb.concurrency.lock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.rafendb.common.txn.TxId;

public class WaitForDeadlockDetector implements DeadlockDetector {

    /** {@inheritDoc} */
    @Override
    public Optional<TxId> selectVictim(Map<TxId, Set<TxId>> waitFor) {
        Objects.requireNonNull(waitFor, "waitFor");

        for (Map.Entry<TxId, Set<TxId>> entry : waitFor.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "transaction");
            Set<TxId> blockers = Objects.requireNonNull(entry.getValue(), "blockers");

            for (TxId blocker : blockers) {
                Objects.requireNonNull(blocker, "blocker");
            }
        }

        TxId victim = null;

        for (TxId tx : waitFor.keySet()) {
            if (isInCycle(tx, waitFor) && (victim == null || tx.compareTo(victim) > 0)) {
                victim = tx;
            }
        }

        return Optional.ofNullable(victim);
    }

    private static boolean isInCycle(TxId start, Map<TxId, Set<TxId>> waitFor) {
        Set<TxId> visited = new HashSet<>();
        Deque<TxId> pending = new ArrayDeque<>();

        pending.addLast(start);
        visited.add(start);

        while (!pending.isEmpty()) {
            TxId current = pending.removeLast();

            for (TxId blocker : waitFor.getOrDefault(current, Set.of())) {
                if (blocker.equals(start)) {
                    return true;
                }

                if (visited.add(blocker)) {
                    pending.addLast(blocker);
                }
            }
        }

        return false;
    }
}
