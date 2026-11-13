package org.rafendb.recovery.driver;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Pengujian unit untuk {@link RecoveryDriver} dan integrasi dengan {@link FRManagerDriver}.
 */
class RecoveryDriverTest {

    @Test
    void testFRManagerDriverRunAllTests() {
        FRManagerDriver driver = new FRManagerDriver();
        boolean success = driver.runAllTests();
        assertTrue(success, "All integration tests in FRManagerDriver should pass");
    }

    @Test
    void testRecoveryDriverCliArguments() {
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {"--help"}));
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {"--test"}));
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {"--crash"}));
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {"--view"}));
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {"--tx", "1"}));
    }

    @Test
    void testRecoveryDriverNonInteractiveDefault() {
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {}));
    }
}
