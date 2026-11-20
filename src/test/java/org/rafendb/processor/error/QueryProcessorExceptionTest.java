package org.rafendb.processor.error;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.RafenDBRuntimeException;

class QueryProcessorExceptionTest {
    @Test
    void messageOnlyConstructorKeepsQueryProcessorPrefixWithoutCause() {
        var error = new QueryProcessorException("Query failed");

        assertEquals("Runtime error at Query Processor: Query failed", error.getMessage());
        assertNull(error.getCause());
        assertInstanceOf(RafenDBRuntimeException.class, error);
    }

    @Test
    void qpExceptionsAreCaughtThroughSharedBaseWithOriginalMessageAndCause() {
        var cause = new IllegalStateException("Underlying failure");
        var errors =
                List.of(
                        new QueryProcessorException("Query failed", cause),
                        new QueryExecutionException("Query failed", cause),
                        new TransactionAbortedException("Query failed", cause));

        for (var error : errors) {
            var caught =
                    assertThrows(
                            QueryProcessorException.class,
                            () -> {
                                throw error;
                            });

            assertSame(error, caught);
            assertInstanceOf(RafenDBRuntimeException.class, caught);
            assertEquals("Runtime error at Query Processor: Query failed", caught.getMessage());
            assertSame(cause, caught.getCause());
        }
    }
}
