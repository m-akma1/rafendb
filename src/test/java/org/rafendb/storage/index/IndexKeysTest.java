package org.rafendb.storage.index;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rafendb.storage.index.IndexFixtures.ch;
import static org.rafendb.storage.index.IndexFixtures.charCol;
import static org.rafendb.storage.index.IndexFixtures.f;
import static org.rafendb.storage.index.IndexFixtures.floatCol;
import static org.rafendb.storage.index.IndexFixtures.i;
import static org.rafendb.storage.index.IndexFixtures.intCol;
import static org.rafendb.storage.index.IndexFixtures.nul;
import static org.rafendb.storage.index.IndexFixtures.s;
import static org.rafendb.storage.index.IndexFixtures.varcharCol;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.StorageException;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.type.DataType;

/** Tests {@link IndexKeys}: type-family checks, NULL handling, ordering and width limits. */
class IndexKeysTest {

    @Test
    void sameFamilyKeyPasses() {
        assertEquals(Optional.of(i(5)), IndexKeys.normalize(i(5), intCol("a")));
        assertEquals(Optional.of(f(2.5f)), IndexKeys.normalize(f(2.5f), floatCol("x")));
        // CHAR and VARCHAR are one family.
        assertEquals(Optional.of(ch("x")), IndexKeys.normalize(ch("x"), varcharCol("v", 5)));
        assertEquals(Optional.of(s("x")), IndexKeys.normalize(s("x"), charCol("c", 5)));
    }

    @Test
    void otherFamilyRejected() {
        assertThrows(StorageException.class, () -> IndexKeys.normalize(f(2.5f), intCol("a")));
        // No numeric promotion, even when the value is exact.
        assertThrows(StorageException.class, () -> IndexKeys.normalize(i(3), floatCol("x")));
        assertThrows(StorageException.class, () -> IndexKeys.normalize(s("1"), intCol("a")));
    }

    @Test
    void otherFamilyMessageMatchesMock() {
        StorageException e =
                assertThrows(
                        StorageException.class, () -> IndexKeys.normalize(f(2.5f), intCol("a")));
        assertEquals("key of type FLOAT does not match column a of type INTEGER", e.getMessage());
    }

    @Test
    void nullKeyIsEmptyButFamilyIsCheckedFirst() {
        assertEquals(Optional.empty(), IndexKeys.normalize(nul(DataType.INTEGER), intCol("a")));
        assertEquals(Optional.empty(), IndexKeys.normalize(nul(DataType.CHAR), varcharCol("v", 3)));
        assertThrows(
                StorageException.class,
                () -> IndexKeys.normalize(nul(DataType.VARCHAR), intCol("a")));
    }

    @Test
    void negativeZeroEqualsZero() {
        assertEquals(0, IndexKeys.compare(f(-0.0f), f(0.0f)));
    }

    @Test
    void textHasNoPaddingAndIsCaseSensitive() {
        assertNotEquals(0, IndexKeys.compare(ch("abc"), ch("abc ")));
        assertTrue(IndexKeys.compare(s("B"), s("a")) < 0);
        assertEquals(0, IndexKeys.compare(ch("abc"), s("abc")));
    }

    @Test
    void bPlusTreeWidthBoundary() {
        assertDoesNotThrow(
                () ->
                        IndexKeys.requireIndexable(
                                varcharCol("v", 1337), IndexType.B_PLUS_TREE, 4096));
        StorageException e =
                assertThrows(
                        StorageException.class,
                        () ->
                                IndexKeys.requireIndexable(
                                        varcharCol("v", 1338), IndexType.B_PLUS_TREE, 4096));
        assertEquals("column v is too wide to index", e.getMessage());
    }

    @Test
    void hashAcceptsMaxTextLength() {
        assertDoesNotThrow(
                () -> IndexKeys.requireIndexable(varcharCol("v", 4000), IndexType.HASH, 4096));
    }

    @Test
    void numericColumnsFitSmallTestPages() {
        assertDoesNotThrow(
                () -> IndexKeys.requireIndexable(intCol("a"), IndexType.B_PLUS_TREE, 128));
        assertDoesNotThrow(() -> IndexKeys.requireIndexable(floatCol("x"), IndexType.HASH, 128));
    }
}
