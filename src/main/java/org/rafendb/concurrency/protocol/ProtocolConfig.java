package org.rafendb.concurrency.protocol;

import java.util.Locale;
import org.rafendb.api.concurrency.ProtocolType;

/**
 * Picks the concurrency protocol when the system starts (CC-02).
 *
 * The choice comes from a setting such as {@code --protocol=lock|timestamp}. If nothing is set,
 * {@link #DEFAULT} is used: {@link ProtocolType#LOCK_BASED} (rigorous 2PL). The protocol is fixed
 * for the whole run and cannot be switched while the system is running.
 */
public final class ProtocolConfig {

    /** Protocol used when no setting is given. */
    public static final ProtocolType DEFAULT = ProtocolType.LOCK_BASED;

    /** Prevents instantiation of the configuration utility. */
    private ProtocolConfig() {}

    /**
     * Turns a setting into a protocol. Upper or lower case both work, and {@code -} counts as
     * {@code _}. Accepted values: {@code lock}, {@code lock_based}, {@code timestamp}, {@code
     * timestamp_based}.
     *
     * @param value the setting, or {@code null} if none was given
     * @return the protocol, or {@link #DEFAULT} if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank or not a known protocol; the
     *     message is written for the user
     */
    public static ProtocolType parse(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String key = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "LOCK", "LOCK_BASED" -> ProtocolType.LOCK_BASED;
            case "TIMESTAMP", "TIMESTAMP_BASED" -> ProtocolType.TIMESTAMP_BASED;
            default ->
                    throw new IllegalArgumentException(
                            "unknown concurrency protocol: '"
                                    + value
                                    + "' (use lock or timestamp; default is "
                                    + name(DEFAULT)
                                    + ")");
        };
    }

    /**
     * Creates a new, empty protocol.
     *
     * @param type the protocol to create, not null
     * @return a new protocol with no locks or timestamps
     * @throws NullPointerException if {@code type} is null
     */
    public static Protocol create(ProtocolType type) {
        return switch (type) {
            case LOCK_BASED -> new LockBasedProtocol();
            case TIMESTAMP_BASED -> new TimestampBasedProtocol();
        };
    }

    /**
     * Describes a protocol in one line, for the startup message.
     *
     * @param type the active protocol, not null
     * @return text such as {@code LOCK_BASED (rigorous 2PL, table-level, ...) [default]}
     * @throws NullPointerException if {@code type} is null
     */
    public static String describe(ProtocolType type) {
        String detail =
                switch (type) {
                    case LOCK_BASED -> "rigorous 2PL, table-level, wait-for-graph deadlock";
                    case TIMESTAMP_BASED -> "strict timestamp ordering, table-level";
                };
        return name(type) + " (" + detail + ")" + (type == DEFAULT ? " [default]" : "");
    }

    /** Returns the protocol name used in startup descriptions and diagnostics. */
    private static String name(ProtocolType type) {
        return type.name();
    }
}
