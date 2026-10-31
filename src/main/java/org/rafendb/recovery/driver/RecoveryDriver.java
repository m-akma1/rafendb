package org.rafendb.recovery.driver;

import org.rafendb.engine.Version;

/**
 * Interactive driver for the Recovery component.
 *
 * <p> TODO: Implementasi
 */
public final class RecoveryDriver {
    private RecoveryDriver() {}

    public static void main(String[] args) {
        System.out.println(Version.banner("Failure Recovery Manager Driver"));
        FRManagerDriver driver = new FRManagerDriver();
        driver.runAllTests();
    }
}
