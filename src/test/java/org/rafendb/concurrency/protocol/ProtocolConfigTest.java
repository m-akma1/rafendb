package org.rafendb.concurrency.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.rafendb.api.concurrency.ProtocolType;

class ProtocolConfigTest {

    // The default protocol must be lock-based.
    @Test
    void defaultIsLockBased() {
        assertEquals(ProtocolType.LOCK_BASED, ProtocolConfig.DEFAULT);
    }

    // No setting given (null) means the default is used.
    @Test
    void noConfigurationUsesTheDefault() {
        assertEquals(ProtocolConfig.DEFAULT, ProtocolConfig.parse(null));
    }

    // Short and full names work, in any case, with "-" or "_", and with spaces around.
    @Test
    void acceptsShortAndFullNamesIgnoringCaseAndHyphens() {
        for (String value : new String[] {"lock", "LOCK", "lock_based", "Lock-Based", " lock "}) {
            assertEquals(ProtocolType.LOCK_BASED, ProtocolConfig.parse(value), value);
        }
        for (String value :
                new String[] {"timestamp", "TIMESTAMP", "timestamp_based", "Timestamp-Based"}) {
            assertEquals(ProtocolType.TIMESTAMP_BASED, ProtocolConfig.parse(value), value);
        }
    }

    // Blank or unknown names are rejected with a message the user can understand.
    @Test
    void blankOrUnknownValueIsRejectedWithAUserMessage() {
        for (String value : new String[] {"", "  ", "mvcc", "optimistic"}) {
            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> ProtocolConfig.parse(value),
                            value);
            assertTrue(error.getMessage().contains("lock or timestamp"), error.getMessage());
        }
    }

    // create() gives the protocol class that matches the type.
    @Test
    void createsTheMatchingProtocol() {
        assertInstanceOf(LockBasedProtocol.class, ProtocolConfig.create(ProtocolType.LOCK_BASED));
        assertInstanceOf(
                TimestampBasedProtocol.class, ProtocolConfig.create(ProtocolType.TIMESTAMP_BASED));
        for (ProtocolType type : ProtocolType.values()) {
            assertEquals(type, ProtocolConfig.create(type).type());
        }
    }

    // Each create() call returns a new object, so no state is shared between runs.
    @Test
    void everyCreateGivesAFreshProtocol() {
        assertNotSame(
                ProtocolConfig.create(ProtocolType.LOCK_BASED),
                ProtocolConfig.create(ProtocolType.LOCK_BASED));
    }

    // describe() starts with the protocol name, and only the default has "[default]".
    @Test
    void describeNamesTheProtocolAndMarksOnlyTheDefault() {
        String lock = ProtocolConfig.describe(ProtocolType.LOCK_BASED);
        String timestamp = ProtocolConfig.describe(ProtocolType.TIMESTAMP_BASED);
        assertTrue(lock.startsWith("LOCK_BASED"), lock);
        assertTrue(lock.endsWith("[default]"), lock);
        assertTrue(timestamp.startsWith("TIMESTAMP_BASED"), timestamp);
        assertFalse(timestamp.contains("[default]"), timestamp);
    }
}
