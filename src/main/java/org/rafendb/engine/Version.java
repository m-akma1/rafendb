package org.rafendb.engine;

/**
 * Build version, read from the JAR manifest to shows which release is running for every banner.
 *
 */
public final class Version {
    private Version() {}

    /** Returns the release version such as "2.3", or "dev" when not running from the JAR. */
    public static String current() {
        String version = Version.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }

    /** One-line banner printed at startup by the server, the client and every driver. */
    public static String banner(String program) {
        return "RafenDB " + current() + " - " + program;
    }
}
