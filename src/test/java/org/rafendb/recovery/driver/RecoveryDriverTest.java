package org.rafendb.recovery.driver;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Pengujian unit untuk {@link RecoveryDriver}.
 */
class RecoveryDriverTest {

    @Test
    void testRecoveryDriverRunAllTests() throws Exception {
        RecoveryDriver driver = new RecoveryDriver();
        boolean success = driver.runAllTests();
        assertTrue(success, "All integration tests in RecoveryDriver should pass");
    }

    @Test
    void testRecoveryDriverCliArguments() throws Exception {
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {"--help"}));
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {"--test"}));
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {"--crash"}));
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {"--view"}));
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {"--tx", "1"}));
    }

    @Test
    void testRecoveryDriverNonInteractiveDefault() throws Exception {
        assertDoesNotThrow(() -> RecoveryDriver.main(new String[] {}));
    }
}
