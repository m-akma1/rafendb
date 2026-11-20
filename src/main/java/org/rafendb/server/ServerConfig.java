package org.rafendb.server;

import java.time.Duration;
import java.util.Objects;

/**
 * Network and lifecycle settings of the server. Database settings belong to the engine, not here.
 *
 * @param port the TCP port to listen on, 0 to 65535; 0 picks a free port (for tests)
 * @param shutdownTimeout how long a graceful shutdown waits for running statements, not negative
 */
public record ServerConfig(int port, Duration shutdownTimeout) {
    /** The port clients connect to by default. */
    public static final int DEFAULT_PORT = 3140;

    /**
     * Checks the values.
     *
     * @throws IllegalArgumentException if the port is out of range or the timeout is negative
     * @throws NullPointerException if the timeout is null
     */
    public ServerConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be 0 to 65535: " + port);
        }
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdown timeout must not be negative");
        }
    }

    /**
     * Returns the defaults: port {@value #DEFAULT_PORT} and a 5 second shutdown timeout.
     *
     * @return the default configuration
     */
    public static ServerConfig defaults() {
        return new ServerConfig(DEFAULT_PORT, Duration.ofSeconds(5));
    }
}
