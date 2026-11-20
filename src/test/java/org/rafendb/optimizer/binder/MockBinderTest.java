package org.rafendb.optimizer.binder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.query.FromItem;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.mock.optimizer.binder.MockBinder;

class MockBinderTest {
    @Test
    void passesThroughParsedQuery() {
        ParsedQuery parsed =
                new SelectQuery("", List.of(), new FromItem.TableRef("table"), null, List.of());
        assertSame(parsed, new MockBinder().bind(parsed));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(NullPointerException.class, () -> new MockBinder().bind(null));
    }
}
