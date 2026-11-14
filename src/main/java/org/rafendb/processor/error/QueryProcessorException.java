package org.rafendb.processor.error;

import org.rafendb.common.error.Component;
import org.rafendb.common.error.RafenDBRuntimeException;

/** Base exception untuk error runtime pada Query Processor. */
public class QueryProcessorException extends RafenDBRuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Membuat exception QP tanpa penyebab tambahan.
     *
     * @param message pesan error, boleh null
     */
    public QueryProcessorException(String message) {
        this(message, null);
    }

    /**
     * Membuat exception QP dengan penyebab aslinya.
     *
     * @param message pesan error, boleh null
     * @param cause penyebab error, boleh null
     */
    public QueryProcessorException(String message, Throwable cause) {
        super(Component.QP, message, cause);
    }
}
