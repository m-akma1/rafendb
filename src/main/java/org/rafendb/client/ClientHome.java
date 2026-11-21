package org.rafendb.client;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * The one directory for the client's user files, so they are not scattered around the home
 * directory. Chosen by {@code --home}, else the {@code RAFENDB_HOME} environment variable, else
 * {@code $XDG_CONFIG_HOME/rafendb} or {@code ~/.config/rafendb} ({@code %APPDATA%\RafenDB} on
 * Windows).
 *
 * <p>It holds {@code history}, the line editor's history, and the optional {@code
 * client.properties} with defaults for {@code host}, {@code port}, {@code timing} ({@code on} or
 * {@code off}) and {@code history.size} (0 keeps no history). Flags override the file, and the
 * file overrides the built-in defaults. The directory is created when an interactive client keeps
 * history.
 */
final class ClientHome {
    private static final String PROPERTIES = "client.properties";
    private static final String HISTORY = "history";

    private final Path dir;
    private final Properties properties;

    private ClientHome(Path dir, Properties properties) {
        this.dir = dir;
        this.properties = properties;
    }

    /**
     * Finds the directory and reads {@code client.properties} if it exists.
     *
     * @param flag the value of {@code --home}, or {@code null}
     * @return the home
     * @throws IllegalArgumentException if {@code client.properties} cannot be read
     */
    static ClientHome resolve(String flag) {
        Path dir = flag != null ? Path.of(flag) : defaultDir();
        Properties properties = new Properties();
        Path file = dir.resolve(PROPERTIES);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + file + ": " + e.getMessage());
            }
        }
        return new ClientHome(dir, properties);
    }

    /**
     * Returns the directory.
     *
     * @return the directory, which may not exist yet
     */
    Path dir() {
        return dir;
    }

    /**
     * Returns the history file, creating the directory if needed.
     *
     * @return the file, or {@code null} if the directory cannot be created
     */
    Path historyFile() {
        try {
            Files.createDirectories(dir);
            return dir.resolve(HISTORY);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns a setting, or a default.
     *
     * @param key the key in {@code client.properties}
     * @param fallback the value when the key is absent
     * @return the value or the default
     */
    String value(String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : value.strip();
    }

    /**
     * Returns a whole-number setting, or a default.
     *
     * @param key the key in {@code client.properties}
     * @param fallback the value when the key is absent
     * @return the value or the default
     * @throws IllegalArgumentException if the value is not a whole number
     */
    int intValue(String key, int fallback) {
        String value = value(key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    PROPERTIES + ": " + key + " must be a whole number: " + value);
        }
    }

    /**
     * Returns an {@code on}/{@code off} setting, or a default.
     *
     * @param key the key in {@code client.properties}
     * @param fallback the value when the key is absent
     * @return the value or the default
     * @throws IllegalArgumentException if the value is neither {@code on} nor {@code off}
     */
    boolean onOff(String key, boolean fallback) {
        String value = value(key, null);
        if (value == null) {
            return fallback;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default ->
                    throw new IllegalArgumentException(
                            PROPERTIES + ": " + key + " must be on or off: " + value);
        };
    }

    private static Path defaultDir() {
        String env = System.getenv("RAFENDB_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        Path userHome = Path.of(System.getProperty("user.home"));
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            String appData = System.getenv("APPDATA");
            Path base =
                    appData != null && !appData.isBlank()
                            ? Path.of(appData)
                            : userHome.resolve("AppData").resolve("Roaming");
            return base.resolve("RafenDB");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : userHome.resolve(".config");
        return base.resolve("rafendb");
    }
}
