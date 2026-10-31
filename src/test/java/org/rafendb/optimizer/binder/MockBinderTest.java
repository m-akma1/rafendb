package org.rafendb.optimizer.binder;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.rafendb.common.error.ValidationException;
import org.rafendb.common.query.ParsedQuery;

class MockBinderTest {
    @Test void passesThroughParsedQuery() {
        ParsedQuery parsed = () -> "select 1";
        assertSame(parsed, new MockBinder().bind(parsed));
    }

    @Test void rejectsNullInput() {
        assertThrows(NullPointerException.class, () -> new MockBinder().bind(null));
    }
}
