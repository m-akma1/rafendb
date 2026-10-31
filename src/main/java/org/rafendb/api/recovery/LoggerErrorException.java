package org.rafendb.api.recovery;

/**
 * Thrown when a WAL logging or flush operation fails.
 */
public class LoggerErrorException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LoggerErrorException(String message) {
        super(message);
    }

    public LoggerErrorException(String message, Throwable cause) {
        super(message, cause);
    }

    public String getErrMessage() {
        return getMessage();
    }
}
