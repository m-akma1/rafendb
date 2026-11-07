package org.rafendb.concurrency.lock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;

public final class TableLockManager implements LockManager {
    private static final class LockState {
        private final Map<TxId, AccessType> holders = new HashMap<>();
        private final Deque<Request> queue = new ArrayDeque<>();
    }

    private record Request(TxId tx, AccessType type) {}

    private final Map<ResourceId, LockState> tables = new HashMap<>();

    public synchronized boolean holdsWrite(TxId tx, ResourceId resource) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(resource, "resource");
        LockState state = tables.get(resource);
        return state != null && state.holders.get(tx) == AccessType.WRITE;
    }

    private static boolean compatible(LockState state, Request request) {
        for (Map.Entry<TxId, AccessType> holder : state.holders.entrySet()) {
            if (holder.getKey().equals(request.tx())) {
                continue;
            }
            if (request.type() == AccessType.WRITE || holder.getValue() == AccessType.WRITE) {
                return false;
            }
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean tryAcquire(TxId tx, AccessType type, ResourceId resource) {
        // just check for null if null throw NullPointerException
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(resource, "resouce");

        // The agreement is using table-level, Resource can still allow row-level
        if (!resource.isTable()) {
            throw new IllegalArgumentException("Resource must be table-level");
        }

        LockState state = tables.computeIfAbsent(resource, ignored -> new LockState());
        AccessType held = state.holders.get(tx);

        if (held == AccessType.WRITE || held == type) {
            return true;
        }

        Request request = new Request(tx, type);

        if (!state.queue.contains(request)) {
            state.queue.addLast(request);
        }

        if (!request.equals(state.queue.peekFirst()) || !compatible(state, request)) {
            return false;
        }

        state.queue.removeFirst();
        state.holders.put(tx, type);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Map<TxId, Set<TxId>> waitForGraph() {
        Map<TxId, Set<TxId>> graph = new HashMap<>();

        for (LockState state : tables.values()) {
            Set<TxId> earlierWaiters = new HashSet<>();

            for (Request request : state.queue) {
                Set<TxId> blockers =
                        graph.computeIfAbsent(request.tx(), ignored -> new HashSet<>());

                for (TxId earlier : earlierWaiters) {
                    if (!earlier.equals(request.tx())) {
                        blockers.add(earlier);
                    }
                }

                for (Map.Entry<TxId, AccessType> holder : state.holders.entrySet()) {
                    if (!holder.getKey().equals(request.tx())
                            && (request.type() == AccessType.WRITE
                                    || holder.getValue() == AccessType.WRITE)) {
                        blockers.add(holder.getKey());
                    }
                }

                earlierWaiters.add(request.tx());
            }
        }
        Map<TxId, Set<TxId>> snapshot = new HashMap<>();
        for (Map.Entry<TxId, Set<TxId>> entry : graph.entrySet()) {
            snapshot.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(snapshot);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void cancelPending(TxId tx) {
        Objects.requireNonNull(tx, "tx");

        Iterator<LockState> states = tables.values().iterator();

        while (states.hasNext()) {
            LockState state = states.next();
            state.queue.removeIf(request -> request.tx().equals(tx));

            if (state.holders.isEmpty() && state.queue.isEmpty()) {
                states.remove();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void releaseAll(TxId tx) {
        Objects.requireNonNull(tx, "tx");

        Iterator<LockState> states = tables.values().iterator();
        while (states.hasNext()) {
            LockState state = states.next();
            state.queue.removeIf(request -> request.tx().equals(tx));
            state.holders.remove(tx);

            if (state.holders.isEmpty() && state.queue.isEmpty()) {
                states.remove();
            }
        }
    }
}
