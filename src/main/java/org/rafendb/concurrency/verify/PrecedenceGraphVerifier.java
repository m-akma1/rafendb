package org.rafendb.concurrency.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;

/**
 * Conflict-serializability check with a precedence graph (CC-09).
 *
 * <p>The graph has one node per committed transaction. For two accesses of different committed
 * transactions, the transaction whose access happened first gets an edge to the other one when the
 * accesses overlap and at least one of them is a WRITE. Two accesses overlap when they are on the
 * same table and either one is table-level or both address the same row. The schedule is
 * serializable exactly when the graph has no cycle, and then a topological sort gives an
 * equivalent serial order. Accesses of transactions that are not committed are ignored.
 *
 * <p>The order is deterministic: among transactions that are ready at the same time, the one with
 * the smallest id comes first. The verifier keeps no state, so one instance can be shared by any
 * number of threads. It compares each access only with earlier accesses to the same table, which
 * is quadratic in the number of accesses per table in the worst case; that is fine for the short
 * schedules it is used on.
 */
public final class PrecedenceGraphVerifier implements SerializabilityVerifier {

    /** Creates a stateless verifier. */
    public PrecedenceGraphVerifier() {}

    /**
     * {@inheritDoc}
     *
     * <p>Thread-safe. The returned graph and order are deeply unmodifiable.
     */
    @Override
    public Result verify(List<Access> schedule, Set<TxId> committed) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(committed, "committed");
        for (Access access : schedule) {
            requireComplete(access);
        }

        Map<TxId, Set<TxId>> edges = new TreeMap<>();
        for (TxId tx : committed) {
            edges.put(Objects.requireNonNull(tx, "committed element"), new TreeSet<>());
        }
        addPrecedenceEdges(schedule, edges);

        List<TxId> order = topologicalOrder(edges);
        boolean serializable = order.size() == edges.size();

        Map<TxId, Set<TxId>> graph = new LinkedHashMap<>();
        edges.forEach((tx, successors) -> graph.put(tx, Collections.unmodifiableSet(successors)));
        return new Result(
                serializable,
                Collections.unmodifiableMap(graph),
                serializable ? List.copyOf(order) : List.of());
    }

    /**
     * Checks that an access and all its fields are present.
     *
     * @param access the access to check
     * @throws NullPointerException if the access or one of its fields is null
     */
    private static void requireComplete(Access access) {
        Objects.requireNonNull(access, "schedule element");
        Objects.requireNonNull(access.tx(), "access tx");
        Objects.requireNonNull(access.type(), "access type");
        Objects.requireNonNull(access.resource(), "access resource");
    }

    /**
     * Adds an earlier-to-later edge for every conflicting pair of committed accesses.
     *
     * @param schedule the accesses in execution order
     * @param edges the graph being built; its keys are exactly the committed transactions
     */
    private static void addPrecedenceEdges(List<Access> schedule, Map<TxId, Set<TxId>> edges) {
        Map<String, List<Access>> earlierByTable = new HashMap<>();
        for (Access current : schedule) {
            if (!edges.containsKey(current.tx())) {
                continue;
            }
            List<Access> earlier =
                    earlierByTable.computeIfAbsent(
                            current.resource().table(), table -> new ArrayList<>());
            for (Access prior : earlier) {
                if (conflicts(prior, current)) {
                    edges.get(prior.tx()).add(current.tx());
                }
            }
            earlier.add(current);
        }
    }

    /**
     * Tells whether an earlier access must precede a later one.
     *
     * @param earlier an access that happened first, on the same table as {@code later}
     * @param later an access that happened afterwards
     * @return true if they belong to different transactions, overlap, and one is a WRITE
     */
    private static boolean conflicts(Access earlier, Access later) {
        if (earlier.tx().equals(later.tx())) {
            return false;
        }
        if (earlier.type() == AccessType.READ && later.type() == AccessType.READ) {
            return false;
        }
        return overlap(earlier.resource(), later.resource());
    }

    /**
     * Tells whether two resources of the same table cover a common row.
     *
     * @param a the first resource
     * @param b the second resource
     * @return true if either is the whole table or both address the same row
     */
    private static boolean overlap(ResourceId a, ResourceId b) {
        return a.isTable() || b.isTable() || a.rid().equals(b.rid());
    }

    /**
     * Sorts the graph topologically, taking the smallest ready id first.
     *
     * @param edges the graph; every transaction is a key
     * @return every transaction once in an order consistent with the edges, or fewer transactions
     *     than the graph has (a cycle blocks the rest)
     */
    private static List<TxId> topologicalOrder(Map<TxId, Set<TxId>> edges) {
        Map<TxId, Integer> waitingOn = new HashMap<>();
        for (TxId tx : edges.keySet()) {
            waitingOn.put(tx, 0);
        }
        for (Set<TxId> successors : edges.values()) {
            for (TxId successor : successors) {
                waitingOn.merge(successor, 1, Integer::sum);
            }
        }

        PriorityQueue<TxId> ready = new PriorityQueue<>();
        waitingOn.forEach(
                (tx, count) -> {
                    if (count == 0) {
                        ready.add(tx);
                    }
                });

        List<TxId> order = new ArrayList<>(edges.size());
        while (!ready.isEmpty()) {
            TxId next = ready.poll();
            order.add(next);
            for (TxId successor : edges.get(next)) {
                if (waitingOn.merge(successor, -1, Integer::sum) == 0) {
                    ready.add(successor);
                }
            }
        }
        return order;
    }
}
