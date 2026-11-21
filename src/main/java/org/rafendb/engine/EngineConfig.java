package org.rafendb.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.Component;

/**
 * Database settings for {@link Engine#start(EngineConfig)}. Network and shutdown settings belong to
 * the server, except {@code drainTimeout}, which the server passes on from its own timeout.
 *
 * @param dir the data directory: tables, catalog, {@code wal.log}, {@code .lock}; never touched when
 *     SM, FRM, or CCM is mocked (a temporary directory is used instead)
 * @param mocks which components are mocks: only QP, SM, CCM, and FRM may be
 * @param protocol the concurrency protocol of a real CCM
 * @param bufferFrames the size of SM's buffer pool, in pages
 * @param seed whether to load the sample database into an empty database at startup
 * @param drainTimeout how long closing waits for active transactions to finish
 */
public record EngineConfig(
        Path dir,
        Set<Component> mocks,
        ProtocolType protocol,
        int bufferFrames,
        boolean seed,
        Duration drainTimeout) {

    private static final Set<Component> MOCKABLE =
            EnumSet.of(Component.QP, Component.SM, Component.CCM, Component.FRM);

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException if a setting is out of range or a component cannot be mocked
     */
    public EngineConfig {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(drainTimeout, "drainTimeout");
        EnumSet<Component> copy = EnumSet.noneOf(Component.class);
        for (Component mock : mocks) {
            if (!MOCKABLE.contains(mock)) {
                throw new IllegalArgumentException(
                        mock.shortName().toLowerCase(Locale.ROOT)
                                + " cannot be mocked here (use qp, sm, ccm, frm)");
            }
            copy.add(mock);
        }
        mocks = Collections.unmodifiableSet(copy);
        if (bufferFrames <= 0) {
            throw new IllegalArgumentException("--frames must be positive: " + bufferFrames);
        }
        if (drainTimeout.isNegative()) {
            throw new IllegalArgumentException("drain timeout must not be negative");
        }
    }

    /**
     * Returns the defaults: directory {@code data}, nothing mocked, lock-based, 256 frames, no
     * seeding, 5 seconds to drain.
     *
     * @return the default settings
     */
    public static EngineConfig defaults() {
        return new EngineConfig(
                Path.of("data"),
                Set.of(),
                ProtocolType.LOCK_BASED,
                256,
                false,
                Duration.ofSeconds(5));
    }

    /**
     * Reads {@code --dir}, {@code --mock}, {@code --protocol}, {@code --frames}, and {@code --seed}
     * over the defaults. The caller rejects unknown flags after reading its own.
     *
     * @param flags the parsed command line
     * @return the settings
     * @throws IllegalArgumentException if a value is invalid
     */
    public static EngineConfig fromFlags(Flags flags) {
        EngineConfig defaults = defaults();
        String dir = flags.value("dir", null);
        String protocol = flags.value("protocol", null);
        return new EngineConfig(
                dir == null ? defaults.dir() : Path.of(dir),
                parseMocks(flags.value("mock", "")),
                protocol == null ? defaults.protocol() : parseProtocol(protocol),
                flags.intValue("frames", defaults.bufferFrames()),
                flags.has("seed"),
                defaults.drainTimeout());
    }

    /** Returns a copy with another data directory. */
    public EngineConfig withDir(Path dir) {
        return new EngineConfig(dir, mocks, protocol, bufferFrames, seed, drainTimeout);
    }

    /** Returns a copy with other mocks. */
    public EngineConfig withMocks(Set<Component> mocks) {
        return new EngineConfig(dir, mocks, protocol, bufferFrames, seed, drainTimeout);
    }

    /** Returns a copy with another concurrency protocol. */
    public EngineConfig withProtocol(ProtocolType protocol) {
        return new EngineConfig(dir, mocks, protocol, bufferFrames, seed, drainTimeout);
    }

    /** Returns a copy with another buffer pool size. */
    public EngineConfig withFrames(int bufferFrames) {
        return new EngineConfig(dir, mocks, protocol, bufferFrames, seed, drainTimeout);
    }

    /** Returns a copy that does or does not load the sample database. */
    public EngineConfig withSeed(boolean seed) {
        return new EngineConfig(dir, mocks, protocol, bufferFrames, seed, drainTimeout);
    }

    /** Returns a copy with another drain timeout. */
    public EngineConfig withDrainTimeout(Duration drainTimeout) {
        return new EngineConfig(dir, mocks, protocol, bufferFrames, seed, drainTimeout);
    }

    /** {@code qp,sm,ccm,frm}, comma-separated and case-insensitive; blanks are skipped. */
    private static Set<Component> parseMocks(String list) {
        EnumSet<Component> mocks = EnumSet.noneOf(Component.class);
        for (String part : list.split(",")) {
            String name = part.strip();
            if (name.isEmpty()) {
                continue;
            }
            Component mock;
            try {
                mock = Component.fromShortName(name);
            } catch (IllegalArgumentException e) {
                mock = Component.GENERAL;
            }
            if (!MOCKABLE.contains(mock)) {
                throw new IllegalArgumentException(
                        "--mock does not accept " + name + " (use qp, sm, ccm, frm)");
            }
            mocks.add(mock);
        }
        return mocks;
    }

    private static ProtocolType parseProtocol(String name) {
        return switch (name.strip().toLowerCase(Locale.ROOT)) {
            case "lock" -> ProtocolType.LOCK_BASED;
            case "timestamp" -> ProtocolType.TIMESTAMP_BASED;
            default ->
                    throw new IllegalArgumentException(
                            "--protocol must be lock or timestamp: " + name);
        };
    }
}
