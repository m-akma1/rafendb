package org.rafendb.processor.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.rafendb.api.processor.Session.TxState;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.ProcessorRuntimeException;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.operator.FakeCcm;

/**
 * The session's transaction state machine against a scripted CCM (which always hands out T1).
 *
 * <p>Steps: {@code BEGIN}, {@code COMMIT}, {@code ABORT}; {@code RUN} is a statement that needs the
 * transaction; {@code ERR} is a statement that fails; {@code CCMERR} is a statement whose
 * transaction CCM aborted first (as TableLocks and WriteSupport do), so the session aborts again.
 */
class SessionImplTest {
    private final FakeCcm ccm = new FakeCcm();
    private final SessionImpl session = new SessionImpl(ccm, new MockStorageManager());

    @ParameterizedTest(name = "[{index}] {0}: {1}")
    @CsvSource(
            delimiter = ';',
            textBlock =
                    """
            ;               RUN ok IDLE                                            ; ccm.begin, ccm.commit T1
            ;               ERR ProcessorRuntimeException IDLE                     ; ccm.begin, ccm.abort T1
            ;               CCMERR TransactionAbortedException IDLE                ; ccm.begin, ccm.abort T1, ccm.abort T1
            ;               BEGIN ok ACTIVE | RUN ok ACTIVE | RUN ok ACTIVE | COMMIT ok IDLE ; ccm.begin, ccm.commit T1
            ;               BEGIN ok ACTIVE | ABORT ok IDLE                        ; ccm.begin, ccm.abort T1
            ;               COMMIT ProcessorRuntimeException IDLE | ABORT ProcessorRuntimeException IDLE ;
            ;               BEGIN ok ACTIVE | BEGIN ProcessorRuntimeException FAILED | ABORT ok IDLE ; ccm.begin, ccm.abort T1
            ;               BEGIN ok ACTIVE | ERR ProcessorRuntimeException FAILED | RUN ProcessorRuntimeException FAILED | COMMIT ProcessorRuntimeException FAILED | BEGIN ProcessorRuntimeException FAILED | ABORT ok IDLE | RUN ok IDLE ; ccm.begin, ccm.abort T1, ccm.begin, ccm.commit T1
            ;               BEGIN ok ACTIVE | CCMERR TransactionAbortedException FAILED | ABORT ok IDLE ; ccm.begin, ccm.abort T1, ccm.abort T1
            beginNull;      BEGIN IllegalStateException IDLE | RUN IllegalStateException IDLE ; ccm.begin, ccm.begin
            commitAborted;  RUN TransactionAbortedException IDLE                   ; ccm.begin, ccm.commit T1, ccm.abort T1
            commitAborted;  BEGIN ok ACTIVE | COMMIT TransactionAbortedException IDLE ; ccm.begin, ccm.commit T1, ccm.abort T1
            commitThrows;   BEGIN ok ACTIVE | COMMIT TransactionAbortedException IDLE ; ccm.begin, ccm.commit T1, ccm.abort T1
            abortFailsOnce; ERR ProcessorRuntimeException FAILED | ABORT ok IDLE   ; ccm.begin, ccm.abort T1, ccm.abort T1
            abortFailsOnce; BEGIN ok ACTIVE | ABORT ProcessorRuntimeException FAILED | ABORT ok IDLE ; ccm.begin, ccm.abort T1, ccm.abort T1
            abortFailsOnce; BEGIN ok ACTIVE | ERR ProcessorRuntimeException FAILED | ABORT ok IDLE ; ccm.begin, ccm.abort T1, ccm.abort T1
            """)
    void stepsGiveTheirOutcomeAndStateAndCallCcmOnlyAsNeeded(
            String setup, String steps, String events) {
        configure(setup);

        for (String step : steps.split("\\|")) {
            String[] parts = step.strip().split(" ");
            String outcome = outcomeOf(action(parts[0]));
            assertEquals(parts[1], outcome, step);
            assertEquals(TxState.valueOf(parts[2]), session.transactionState(), step);
        }

        assertEquals(events == null ? List.of() : Arrays.asList(events.split(", ")), ccm.events());
    }

    @Test
    void closeRollsBackOnceAndEverythingAfterIsRejected() {
        session.begin();

        session.close();
        session.close();

        assertEquals(List.of("ccm.begin", "ccm.abort T1"), ccm.events());
        assertEquals(TxState.IDLE, session.transactionState());
        assertFalse(session.isOpen());
        assertNull(session.currentTx());
        for (String step : List.of("BEGIN", "COMMIT", "ABORT", "RUN")) {
            assertThrows(IllegalStateException.class, action(step), step);
        }
        assertEquals(2, ccm.events().size(), "a closed session never reaches CCM");
    }

    private void configure(String setup) {
        if (setup == null) {
            return;
        }
        switch (setup) {
            case "beginNull" -> ccm.beginReturns(null);
            case "commitAborted" -> ccm.commitReturns(new TxResult(TxStatus.ABORTED, "deadlock"));
            case "commitThrows" -> ccm.failNextCommit();
            case "abortFailsOnce" -> ccm.failNextAbort();
            default -> fail("unknown setup " + setup);
        }
    }

    private Executable action(String step) {
        return switch (step) {
            case "BEGIN" -> session::begin;
            case "COMMIT" -> session::commit;
            case "ABORT" -> session::abort;
            case "RUN" -> () -> session.runStatement(session::requireTx);
            case "ERR" ->
                    () ->
                            session.runStatement(
                                    () -> {
                                        throw new ProcessorRuntimeException("statement failed");
                                    });
            case "CCMERR" ->
                    () ->
                            session.runStatement(
                                    () -> {
                                        try {
                                            ccm.abort(session.requireTx());
                                        } catch (ConcurrencyInternalException e) {
                                            throw new AssertionError(e);
                                        }
                                        throw new TransactionAbortedException("deadlock victim");
                                    });
            default -> throw new IllegalArgumentException(step);
        };
    }

    /** {@code ok}, or the simple name of what the step threw. */
    private static String outcomeOf(Executable step) {
        try {
            step.execute();
            return "ok";
        } catch (Throwable e) {
            return e.getClass().getSimpleName();
        }
    }
}
