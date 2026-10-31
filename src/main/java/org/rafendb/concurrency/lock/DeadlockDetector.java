package org.rafendb.concurrency.lock;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.rafendb.common.txn.TxId;

/** Stateless, thread-safe wait-for cycle detection, including lock upgrades (CC-07). */
public interface DeadlockDetector {

    /**
     * Selects one victim: the largest TxId in any cycle, never an acyclic waiter. The coordinator
     * revalidates against live lock state, aborts through CC-04, and rescans after safe cleanup.
     * The driver/coordinator documents and enforces the detection/resolution time bound.
     *
     * @param waitFor a consistent snapshot; an edge from A to B means A waits for B, not null
     * @return a victim, or empty if there is no cycle; never null
     * @throws NullPointerException if the graph, keys, adjacency sets, or elements are null
     */
    Optional<TxId> selectVictim(Map<TxId, Set<TxId>> waitFor);
}
