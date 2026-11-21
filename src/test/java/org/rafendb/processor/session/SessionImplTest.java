package org.rafendb.processor.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.rafendb.api.processor.Session;
import org.rafendb.api.processor.Session.TxState;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.ProcessorRuntimeException;
import org.rafendb.common.query.QueryResult;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.common.type.Value;
import org.rafendb.engine.Engine;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.operator.FakeCcm;

/**
 * The session against a scripted CCM (which always hands out T1), the mock SM's {@code prodi} (3
 * rows) and {@code mahasiswa} (5 rows), and the real optimizer.
 *
 * <p>State-machine steps: {@code BEGIN}, {@code COMMIT}, {@code ABORT}; {@code RUN} is a statement
 * that needs the transaction; {@code ERR} is a statement that fails; {@code CCMERR} is a statement
 * whose transaction CCM aborted first (as TableLocks and WriteSupport do), so the session aborts
 * again.
 */
class SessionImplTest {
    private final FakeCcm ccm = new FakeCcm();
    private final MockStorageManager storage = new MockStorageManager();
    private final SessionImpl session =
            (SessionImpl)
                    new QueryProcessorImpl(ccm, storage, () -> Engine.optimizer(storage))
                            .openSession();

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

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(
            delimiter = '~', // one column; SQL may contain ';'
            textBlock =
                    """
            SELECT * FROM prodi => rows3 IDLE
            SELECT nim, nama FROM mahasiswa WHERE ipk > 3.5; => rows2 IDLE
            SELECT nama FROM mahasiswa WHERE nim = 13524275 => rows1 IDLE
            SELECT * FROM mahasiswa JOIN prodi ON mahasiswa.kode_prodi = prodi.kode => rows5 IDLE
            SELECT * FROM nope => ValidationException IDLE
            COMMIT => ProcessorRuntimeException IDLE | ABORT => ProcessorRuntimeException IDLE
            BEGIN => BEGIN ACTIVE | SELECT * FROM prodi => rows3 ACTIVE | COMMIT => COMMIT IDLE
            BEGIN => BEGIN ACTIVE | BEGIN => ProcessorRuntimeException FAILED | ABORT => ABORT IDLE
            BEGIN => BEGIN ACTIVE | SELEC * FROM prodi => SyntaxException FAILED | SELECT * FROM prodi => ProcessorRuntimeException FAILED | COMMIT => ProcessorRuntimeException FAILED | ABORT => ABORT IDLE
            BEGIN => BEGIN ACTIVE | SELECT * FROM nope => ValidationException FAILED | ABORT => ABORT IDLE | SELECT * FROM prodi => rows3 IDLE
            INSERT INTO prodi VALUES ('XX', 'Prodi Baru') => affected1 IDLE
            UPDATE mahasiswa SET ipk = 4 WHERE ipk < 3.5 => affected2 IDLE
            UPDATE mahasiswa SET ipk = 4 WHERE nim = 1 => affected0 IDLE
            DELETE FROM mahasiswa WHERE kode_prodi = 'STI' => affected2 IDLE
            CREATE INDEX mahasiswa_nama_idx ON mahasiswa(nama) USING HASH => CREATE_INDEX IDLE
            INSERT INTO prodi VALUES ('XX') => ValidationException IDLE
            BEGIN => BEGIN ACTIVE | INSERT INTO prodi VALUES ('XX', 'Prodi Baru') => affected1 ACTIVE | DELETE FROM prodi => affected3 ACTIVE | COMMIT => COMMIT IDLE
            BEGIN => BEGIN ACTIVE | CREATE INDEX x ON mahasiswa(nope) => ValidationException FAILED | ABORT => ABORT IDLE
            """)
    void statementsGiveTheirResultAndLeaveTheSessionInTheExpectedState(String script) {
        for (String step : script.split("\\|")) {
            String[] sides = step.split("=>");
            String[] expected = sides[1].strip().split(" ");
            assertEquals(expected[0], outcomeOf(sides[0].strip()), step);
            assertEquals(TxState.valueOf(expected[1]), session.transactionState(), step);
        }
    }

    @Test
    void aSelectReadsUnderTheReadLockOfItsOwnTransaction() {
        session.execute("SELECT * FROM prodi");

        assertEquals(List.of("ccm.begin", "ccm.access READ prodi", "ccm.commit T1"), ccm.events());
    }

    @Test
    void aWriteLocksLogsAndCommitsInItsOwnTransaction() {
        session.execute("INSERT INTO prodi VALUES ('XX', 'Prodi Baru')");

        assertEquals(
                List.of(
                        "ccm.begin",
                        "ccm.access WRITE prodi",
                        "ccm.log INSERT lsn=100",
                        "ccm.commit T1"),
                ccm.events());
    }

    @Test
    void headersAreColumnNamesQualifiedOnlyWhenShared() {
        var rows =
                (QueryResult.Rows)
                        session.execute(
                                "SELECT mahasiswa.nama, prodi.nama, nim FROM mahasiswa"
                                        + " JOIN prodi ON mahasiswa.kode_prodi = prodi.kode");

        assertEquals(List.of("mahasiswa.nama", "prodi.nama", "nim"), rows.columns());
    }

    @Test
    void orderByDecidesTheRowOrder() {
        var rows =
                (QueryResult.Rows) session.execute("SELECT nim FROM mahasiswa ORDER BY nim DESC");

        assertEquals(List.of(Value.ofInt(18224280)), rows.rows().getFirst().values());
        assertEquals(List.of(Value.ofInt(13224279)), rows.rows().getLast().values());
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
        assertThrows(IllegalStateException.class, () -> session.execute("SELECT * FROM prodi"));
        assertEquals(2, ccm.events().size(), "a closed session never reaches CCM");
    }

    @Test
    void nullSqlIsRejected() {
        Session open = session;
        assertThrows(NullPointerException.class, () -> open.execute(null));
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

    /** {@code rowsN}, the status text, {@code affectedN}, or the simple name of what was thrown. */
    private String outcomeOf(String sql) {
        try {
            return switch (session.execute(sql)) {
                case QueryResult.Rows rows -> "rows" + rows.rows().size();
                case QueryResult.Status status -> status.message().replace(' ', '_');
                case QueryResult.Affected affected -> "affected" + affected.count();
            };
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
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
