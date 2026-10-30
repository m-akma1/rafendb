package org.rafendb.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rafendb.storage.index.IndexFixtures.i;
import static org.rafendb.storage.index.IndexFixtures.nul;
import static org.rafendb.storage.index.IndexFixtures.rid;

import org.junit.jupiter.api.Test;
import org.rafendb.common.type.DataType;

/** Tests {@link IndexEntry}: ordering by key, then Rid, and the probe entry. */
class IndexEntryTest {

    @Test
    void ordersByKeyThenRid() {
        assertTrue(new IndexEntry(i(1), rid(0, 5)).compareTo(new IndexEntry(i(1), rid(1, 0))) < 0);
        assertTrue(new IndexEntry(i(1), rid(9, 9)).compareTo(new IndexEntry(i(2), rid(0, 0))) < 0);
        assertEquals(0, new IndexEntry(i(1), rid(2, 3)).compareTo(new IndexEntry(i(1), rid(2, 3))));
    }

    @Test
    void probeIsSmallestEntryOfItsKey() {
        assertTrue(IndexEntry.probe(i(1)).compareTo(new IndexEntry(i(1), rid(0, 0))) <= 0);
        assertTrue(IndexEntry.probe(i(1)).compareTo(new IndexEntry(i(0), rid(99, 99))) > 0);
    }

    @Test
    void nullKeyRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexEntry(nul(DataType.INTEGER), rid(0, 0)));
    }
}
