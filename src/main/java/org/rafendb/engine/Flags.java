package org.rafendb.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line flags of the server and the client, in the same style as the driver kit: only
 * {@code --name=value} and bare {@code --name}. Read every flag first, then call {@link
 * #rejectUnknown()} so a typo is reported instead of ignored.
 *
 * <p>Every problem is an {@link IllegalArgumentException} whose message is meant for the user.
 * Not thread-safe; used once at startup.
 */
public final class Flags {
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Set<String> read = new HashSet<>();

    private Flags() {}

    /**
     * Parses the arguments of {@code main}.
     *
     * @param args the arguments
     * @return the flags
     * @throws IllegalArgumentException if an argument is not a flag or a flag is repeated
     */
    public static Flags parse(String[] args) {
        Flags flags = new Flags();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            String name =
                    !arg.startsWith("--") ? "" : eq < 0 ? arg.substring(2) : arg.substring(2, eq);
            if (name.isEmpty()) {
                throw new IllegalArgumentException(
                        "unexpected argument: " + arg + " (flags are --name=value or --name)");
            }
            if (flags.values.containsKey(name)) {
                throw new IllegalArgumentException("--" + name + " given more than once");
            }
            flags.values.put(name, eq < 0 ? null : arg.substring(eq + 1));
        }
        return flags;
    }

    /**
     * Returns whether bare {@code --name} was given.
     *
     * @param name the flag name without dashes
     * @return true if given
     * @throws IllegalArgumentException if it was given a value
     */
    public boolean has(String name) {
        if (!values.containsKey(name)) {
            return false;
        }
        read.add(name);
        if (values.get(name) != null) {
            throw new IllegalArgumentException("--" + name + " takes no value");
        }
        return true;
    }

    /**
     * Returns the value of {@code --name=value}, or a default.
     *
     * @param name the flag name without dashes
     * @param fallback the value when the flag is absent
     * @return the value or the default
     * @throws IllegalArgumentException if the flag was given without a value
     */
    public String value(String name, String fallback) {
        if (!values.containsKey(name)) {
            return fallback;
        }
        read.add(name);
        String value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "--" + name + " needs a value: --" + name + "=<value>");
        }
        return value;
    }

    /**
     * Returns the value of {@code --name=<integer>}, or a default.
     *
     * @param name the flag name without dashes
     * @param fallback the value when the flag is absent
     * @return the value or the default
     * @throws IllegalArgumentException if the value is missing or not an integer
     */
    public int intValue(String name, int fallback) {
        String value = value(name, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a whole number: " + value);
        }
    }

    /**
     * Fails if a flag was given that nobody read.
     *
     * @throws IllegalArgumentException naming the first unknown flags
     */
    public void rejectUnknown() {
        List<String> unknown = new ArrayList<>();
        for (String name : values.keySet()) {
            if (!read.contains(name)) {
                unknown.add("--" + name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown option: " + String.join(", ", unknown));
        }
    }
}
