package org.rafendb.processor.error;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.rafendb.common.error.RafenDBRuntimeException;

class TransactionAbortedExceptionTest {
    @Test
    void messageIsKeptWithQueryProcessorPrefix() {
        TransactionAbortedException error = new TransactionAbortedException("Transaction aborted");
        assertEquals("Runtime error at Query Processor: Transaction aborted", error.getMessage());
        assertNull(error.getCause());
        assertInstanceOf(RafenDBRuntimeException.class, error);
    }

    @Test
    void causeIsKept() {
        IllegalStateException cause = new IllegalStateException("Deadlock");
        TransactionAbortedException error =
                new TransactionAbortedException("Transaction aborted", cause);
        assertSame(cause, error.getCause());
        assertEquals("Runtime error at Query Processor: Transaction aborted", error.getMessage());
    }
}
