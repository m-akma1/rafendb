package org.rafendb.concurrency.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.mock.recovery.MockRecoveryManager;

class FrmBridgeTest {

    private final List<String> calls = new ArrayList<>();
    private FakeTransactionStates states;
    private FakeProtocol protocol;
    private FrmBridge bridge;

    @BeforeEach
    void setUp() {
        states = new FakeTransactionStates(calls);
        protocol = new FakeProtocol(calls);
        bridge = new FrmBridge(new MockRecoveryManager(), states, protocol);
    }

    @Test
    void admissionStartsClosed() {
        assertFalse(bridge.isAdmitting());
    }

    @Test
    void initializationResetsAboveLargerHighWaterMarkThenOpensAdmission() {
        bridge.initializeAfterRecovery(new RecoveryInfo(100, 120));

        assertEquals(List.of("resetAbove 120", "reset"), calls);
        assertTrue(bridge.isAdmitting());
    }

    @Test
    void secondInitializationIsRejected() {
        bridge.initializeAfterRecovery(new RecoveryInfo(0, 0));

        assertThrows(
                IllegalStateException.class,
                () -> bridge.initializeAfterRecovery(new RecoveryInfo(5, 5)));
        assertEquals(List.of("resetAbove 0", "reset"), calls);
    }

    @Test
    void negativeHighWaterMarkIsRejectedBeforeAnyReset() {
        assertThrows(
                IllegalArgumentException.class,
                () -> bridge.initializeAfterRecovery(new RecoveryInfo(-1, 0)));
        assertFalse(bridge.isAdmitting());
        assertTrue(calls.isEmpty());
    }

    @Test
    void failedResetLeavesAdmissionClosedAndCanBeRetried() {
        protocol.failReset = true;

        assertThrows(
                IllegalStateException.class,
                () -> bridge.initializeAfterRecovery(new RecoveryInfo(3, 3)));
        assertFalse(bridge.isAdmitting());

        protocol.failReset = false;
        bridge.initializeAfterRecovery(new RecoveryInfo(3, 3));
        assertTrue(bridge.isAdmitting());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> bridge.initializeAfterRecovery(null));
        assertThrows(NullPointerException.class, () -> new FrmBridge(null, states, protocol));
    }
}
