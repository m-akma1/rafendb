package org.rafendb.driver;

import org.rafendb.common.error.GeneralRuntimeException;

/**
 * A mistake by the person using a driver: an unknown command, a malformed or unknown flag, or wrong
 * arguments to a command. Printed as {@code Runtime error at General: <message>}, counted as one
 * failure, and the driver carries on. Driver subclasses may throw it for their own bad input.
 */
public class DriverException extends GeneralRuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was wrong, such as {@code "unknown command: tabels"}
     */
    public DriverException(String message) {
        super(message);
    }
}
