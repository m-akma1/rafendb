package org.rafendb.concurrency.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.concurrency.verify.SerializabilityVerifier.Access;
import org.rafendb.concurrency.verify.SerializabilityVerifier.Result;

/** Tests for {@link PrecedenceGraphVerifier}. */
final class PrecedenceGraphVerifierTest {

    private static final ResourceId A = ResourceId.ofTable("a");
    private static final ResourceId B = ResourceId.ofTable("b");

    private final PrecedenceGraphVerifier verifier = new PrecedenceGraphVerifier();

    private static TxId tx(long id) {
        return new TxId(id);
    }

    private static Access r(long tx, ResourceId resource) {
        return new Access(tx(tx), AccessType.READ, resource);
    }

    private static Access w(long tx, ResourceId resource) {
        return new Access(tx(tx), AccessType.WRITE, resource);
    }

    private static Set<TxId> committed(long... ids) {
        Set<TxId> set = new HashSet<>();
        for (long id : ids) {
            set.add(tx(id));
        }
        return set;
    }

    // ---- serializable schedules ------------------------------------------------------------

    @Test
    void serialScheduleIsSerializableInExecutionOrder() {
        Result result =
                verifier.verify(List.of(r(1, A), w(1, A), r(2, A), w(2, A)), committed(1, 2));

        assertTrue(result.serializable());
        assertEquals(List.of(tx(1), tx(2)), result.serialOrder());
        assertEquals(Set.of(tx(2)), result.graph().get(tx(1)));
        assertEquals(Set.of(), result.graph().get(tx(2)));
    }

    @Test
    void readReadDoesNotConflict() {
        Result result = verifier.verify(List.of(r(1, A), r(2, A)), committed(1, 2));

        assertTrue(result.serializable());
        assertEquals(Set.of(), result.graph().get(tx(1)));
        assertEquals(Set.of(), result.graph().get(tx(2)));
    }

    @Test
    void everyConflictKindAddsAnEdge() {
        // Read-write, write-read and write-write each order the earlier transaction first.
        Result rw = verifier.verify(List.of(r(1, A), w(2, A)), committed(1, 2));
        Result wr = verifier.verify(List.of(w(1, A), r(2, A)), committed(1, 2));
        Result ww = verifier.verify(List.of(w(1, A), w(2, A)), committed(1, 2));

        for (Result result : List.of(rw, wr, ww)) {
            assertEquals(Set.of(tx(2)), result.graph().get(tx(1)));
            assertTrue(result.serializable());
        }
    }

    @Test
    void chainGivesTheOnlyPossibleOrder() {
        Result result = verifier.verify(List.of(w(1, A), w(2, A), w(3, A)), committed(1, 2, 3));

        assertTrue(result.serializable());
        assertEquals(List.of(tx(1), tx(2), tx(3)), result.serialOrder());
        assertEquals(Set.of(tx(2), tx(3)), result.graph().get(tx(1)));
    }

    @Test
    void laterTransactionCanComeFirstInTheSerialOrder() {
        // T3 wrote before T1 read, so T3 must precede T1; T2 is independent.
        Result result = verifier.verify(List.of(w(3, A), r(1, A), r(2, B)), committed(1, 2, 3));

        assertTrue(result.serializable());
        // Among ready transactions the smallest id goes first: T2, then T3, which frees T1.
        assertEquals(List.of(tx(2), tx(3), tx(1)), result.serialOrder());
    }

    @Test
    void isolatedCommittedTransactionIsInTheGraphAndTheOrder() {
        Result result = verifier.verify(List.of(r(1, A)), committed(1, 7));

        assertTrue(result.serializable());
        assertEquals(Set.of(tx(1), tx(7)), result.graph().keySet());
        assertEquals(Set.of(), result.graph().get(tx(7)));
        assertEquals(List.of(tx(1), tx(7)), result.serialOrder());
    }

    @Test
    void emptyInputIsSerializable() {
        Result result = verifier.verify(List.of(), Set.of());

        assertTrue(result.serializable());
        assertTrue(result.graph().isEmpty());
        assertTrue(result.serialOrder().isEmpty());
    }

    // ---- non-serializable schedules --------------------------------------------------------

    @Test
    void cycleIsNotSerializableAndHasNoOrder() {
        // R1(A) W2(A) gives T1 -> T2; R2(B) W1(B) gives T2 -> T1.
        Result result =
                verifier.verify(List.of(r(1, A), w(2, A), r(2, B), w(1, B)), committed(1, 2));

        assertFalse(result.serializable());
        assertTrue(result.serialOrder().isEmpty());
        assertEquals(Set.of(tx(2)), result.graph().get(tx(1)));
        assertEquals(Set.of(tx(1)), result.graph().get(tx(2)));
    }

    @Test
    void lostUpdateIsNotSerializable() {
        // Both read A, then both write A.
        Result result =
                verifier.verify(List.of(r(1, A), r(2, A), w(1, A), w(2, A)), committed(1, 2));

        assertFalse(result.serializable());
    }

    @Test
    void cycleDoesNotHideTransactionsOutsideIt() {
        Result result =
                verifier.verify(
                        List.of(r(1, A), w(2, A), r(2, B), w(1, B), r(3, ResourceId.ofTable("c"))),
                        committed(1, 2, 3));

        assertFalse(result.serializable());
        assertTrue(result.serialOrder().isEmpty());
        assertEquals(Set.of(tx(1), tx(2), tx(3)), result.graph().keySet());
    }

    // ---- committed filter ------------------------------------------------------------------

    @Test
    void transactionsThatDidNotCommitAreIgnored() {
        // T3 would close a cycle with T1, but it aborted.
        Result result = verifier.verify(List.of(r(1, A), w(3, A), w(3, B), w(1, B)), committed(1));

        assertTrue(result.serializable());
        assertEquals(Set.of(tx(1)), result.graph().keySet());
        assertEquals(List.of(tx(1)), result.serialOrder());
    }

    // ---- table versus row ------------------------------------------------------------------

    @Test
    void tableAccessOverlapsRowsOfTheSameTable() {
        ResourceId row = ResourceId.ofRow("a", new Rid(0, 1));

        Result rowThenTable = verifier.verify(List.of(w(1, row), r(2, A)), committed(1, 2));
        Result tableThenRow = verifier.verify(List.of(r(1, A), w(2, row)), committed(1, 2));

        assertEquals(Set.of(tx(2)), rowThenTable.graph().get(tx(1)));
        assertEquals(Set.of(tx(2)), tableThenRow.graph().get(tx(1)));
    }

    @Test
    void differentRowsOfTheSameTableDoNotOverlap() {
        ResourceId row1 = ResourceId.ofRow("a", new Rid(0, 1));
        ResourceId row2 = ResourceId.ofRow("a", new Rid(0, 2));

        Result result = verifier.verify(List.of(w(1, row1), w(2, row2)), committed(1, 2));

        assertEquals(Set.of(), result.graph().get(tx(1)));
    }

    @Test
    void sameRowOverlaps() {
        ResourceId row = ResourceId.ofRow("a", new Rid(4, 2));

        Result result =
                verifier.verify(
                        List.of(w(1, row), r(2, ResourceId.ofRow("a", new Rid(4, 2)))),
                        committed(1, 2));

        assertEquals(Set.of(tx(2)), result.graph().get(tx(1)));
    }

    @Test
    void sameRidInDifferentTablesDoesNotOverlap() {
        ResourceId inA = ResourceId.ofRow("a", new Rid(0, 1));
        ResourceId inB = ResourceId.ofRow("b", new Rid(0, 1));

        Result result = verifier.verify(List.of(w(1, inA), w(2, inB)), committed(1, 2));

        assertEquals(Set.of(), result.graph().get(tx(1)));
    }

    @Test
    void differentTablesDoNotOverlap() {
        Result result = verifier.verify(List.of(w(1, A), w(2, B)), committed(1, 2));

        assertEquals(Set.of(), result.graph().get(tx(1)));
    }

    // ---- result is a snapshot --------------------------------------------------------------

    @Test
    void resultIsDeeplyUnmodifiable() {
        Result result = verifier.verify(List.of(w(1, A), w(2, A)), committed(1, 2));

        assertThrows(UnsupportedOperationException.class, () -> result.graph().clear());
        assertThrows(
                UnsupportedOperationException.class, () -> result.graph().get(tx(1)).add(tx(9)));
        assertThrows(UnsupportedOperationException.class, () -> result.serialOrder().add(tx(9)));
    }

    @Test
    void resultIsNotAffectedByLaterChangesToTheInput() {
        List<Access> schedule = new ArrayList<>(List.of(w(1, A), w(2, A)));
        Set<TxId> committedSet = committed(1, 2);

        Result result = verifier.verify(schedule, committedSet);
        schedule.clear();
        committedSet.clear();

        assertEquals(List.of(tx(1), tx(2)), result.serialOrder());
        assertEquals(Map.of(tx(1), Set.of(tx(2)), tx(2), Set.of()), result.graph());
    }

    // ---- argument validation ---------------------------------------------------------------

    @Test
    void rejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> verifier.verify(null, committed(1)));
        assertThrows(NullPointerException.class, () -> verifier.verify(List.of(), null));
    }

    @Test
    void rejectsNullScheduleElementsAndFields() {
        List<Access> withNullElement = new ArrayList<>();
        withNullElement.add(null);

        assertThrows(
                NullPointerException.class, () -> verifier.verify(withNullElement, committed(1)));
        assertThrows(
                NullPointerException.class,
                () -> verifier.verify(List.of(new Access(null, AccessType.READ, A)), committed(1)));
        assertThrows(
                NullPointerException.class,
                () -> verifier.verify(List.of(new Access(tx(1), null, A)), committed(1)));
        assertThrows(
                NullPointerException.class,
                () ->
                        verifier.verify(
                                List.of(new Access(tx(1), AccessType.READ, null)), committed(1)));
    }

    @Test
    void rejectsNullCommittedElement() {
        Set<TxId> withNull = new HashSet<>();
        withNull.add(null);

        assertThrows(NullPointerException.class, () -> verifier.verify(List.of(), withNull));
    }
}
