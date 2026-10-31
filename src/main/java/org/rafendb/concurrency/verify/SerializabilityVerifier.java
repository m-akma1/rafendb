package org.rafendb.concurrency.verify;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;

/** Stateless, thread-safe precedence-graph verification independent of the protocol (CC-09). */
public interface SerializabilityVerifier {

    /** One executed access, not a request or WAITING response; fields must be non-null. */
    record Access(TxId tx, AccessType type, ResourceId resource) {}

    /**
     * Non-null, deeply unmodifiable snapshots. Graph includes isolated committed transactions;
     * serialOrder contains every committed transaction once if acyclic, otherwise is empty.
     */
    record Result(boolean serializable, Map<TxId, Set<TxId>> graph, List<TxId> serialOrder) {}

    /**
     * Adds earlier-to-later edges between committed transactions for overlapping table/row
     * accesses with at least one WRITE. Serializable iff acyclic; cycles are valid inputs.
     * The driver separately checks final-data equivalence and absence of aborted effects.
     *
     * @param schedule manual or recorded accesses in actual execution order, not null
     * @param committed successfully committed transactions, not null
     * @return the graph and an equivalent topological serial order, never null
     * @throws NullPointerException if inputs or their elements/fields are null
     */
    Result verify(List<Access> schedule, Set<TxId> committed);
}
