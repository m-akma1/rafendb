package org.rafendb.concurrency.driver;

import static org.rafendb.common.txn.AccessResult.Status.ALLOWED;
import static org.rafendb.common.txn.AccessResult.Status.WAITING;
import static org.rafendb.common.txn.TxStatus.ABORTED;
import static org.rafendb.common.txn.TxStatus.COMMITTED;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.Component;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.txn.AccessResult.Status;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.ConcurrencyControlManager;
import org.rafendb.concurrency.driver.Scenario.ExecutedAccess;
import org.rafendb.concurrency.protocol.ProtocolConfig;
import org.rafendb.concurrency.txn.TransactionManager;
import org.rafendb.concurrency.verify.PrecedenceGraphVerifier;
import org.rafendb.concurrency.verify.SerializabilityVerifier.Access;
import org.rafendb.concurrency.verify.SerializabilityVerifier.Result;
import org.rafendb.driver.Driver;
import org.rafendb.driver.DriverException;

/** Interactive and scripted schedules against CCM, FRM and SM in a temporary database. */
public final class ConcurrencyDriver extends Driver {
    private static final String READ_USAGE = "read <tx> <table> [status]";
    private static final String WRITE_USAGE = "write <tx> <table> <value> [status]";

    /** Tracks a transaction, its worker, and the access to retry after WAITING. */
    private static final class Session {
        private final TxId id;
        private final ExecutorService worker;
        private AccessCommand pending;

        /** Associates an admitted transaction with its worker. */
        private Session(TxId id, ExecutorService worker) {
            this.id = id;
            this.worker = worker;
        }
    }

    /** Holds a parsed access and its expected status, including the arguments for a retry. */
    private record AccessCommand(
            Session session,
            String alias,
            AccessType type,
            ResourceId resource,
            int value,
            Status expected) {}

    /** Outcome of one access attempt, with a value when the access executed. */
    private record Attempt(Status status, int value) {}

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private ProtocolType protocol;
    private ConcurrencyControlManager ccm;
    private Scenario scenario;
    private boolean mockScenario;
    private boolean batch;
    private boolean needsVerification;

    /** Creates the standalone CCM driver. */
    ConcurrencyDriver() {
        super(Component.CCM);
    }

    /**
     * Launches the driver on standard input and output.
     *
     * @param args command-line options
     */
    public static void main(String[] args) {
        new ConcurrencyDriver().launch(args);
    }

    @Override
    protected void setup() {
        mockScenario = mocked(Component.FRM);
        if (mockScenario != mocked(Component.SM)) {
            throw new DriverException("mock both with --mock=sm,frm, or use both real components");
        }
        batch = option("run") != null;
        try {
            protocol = ProtocolConfig.parse(option("protocol"));
        } catch (IllegalArgumentException e) {
            throw new DriverException(e.getMessage());
        }
        command(
                "reset",
                "reset",
                "start a fresh database and WAL",
                (args, rest) -> {
                    requireArity(args, 0, 0, "reset");
                    resetScenario();
                });
        command(
                "begin",
                "begin <tx>",
                "start a transaction with the given alias",
                (args, rest) -> begin(args));
        command(
                "read",
                READ_USAGE,
                "read a table's test value",
                (args, rest) -> execute(parseAccess(AccessType.READ, args)));
        command(
                "write",
                WRITE_USAGE,
                "set a table's test value",
                (args, rest) -> execute(parseAccess(AccessType.WRITE, args)));
        command(
                "parallel",
                "parallel \"<access>\" \"<access>\"",
                "submit two read or write commands on different transactions",
                (args, rest) -> parallel(args));
        command(
                "retry",
                "retry <tx> [status]",
                "retry a waiting access with the same arguments",
                (args, rest) -> retry(args));
        command(
                "commit",
                "commit <tx> [status]",
                "commit a transaction",
                (args, rest) -> finish(args, COMMITTED));
        command(
                "abort",
                "abort <tx> [status]",
                "roll back a transaction",
                (args, rest) -> finish(args, ABORTED));
        command(
                "assert",
                "assert value <table> <number>",
                "check the current test value",
                (args, rest) -> assertValue(args));
        command(
                "verify",
                "verify <serializable|cyclic> [file]",
                "verify executed accesses or a handwritten schedule",
                (args, rest) -> verify(args));

        out().println(
                        mockScenario
                                ? "FRM and storage: in-memory mocks"
                                : "FRM and storage: real (temporary database and WAL)");
        out().println("Protocol: " + ProtocolConfig.describe(protocol));
    }

    @Override
    protected void help() {
        super.help();
        out().println("  --protocol=lock|timestamp    default: lock");
        out().println("  --mock=sm,frm    use the in-memory fallback for both dependencies");
        out().println(
                        "Use retry after WAITING. See test-scenarios/concurrency/README.md for inputs.");
    }

    @Override
    protected void close() throws Exception {
        checkBatchCompletion();
        closeSessions();
        Scenario previous = scenario;
        scenario = null;
        ccm = null;
        needsVerification = false;
        if (previous != null) {
            previous.close();
        }
    }

    /** Stops the transaction workers and clears their aliases. */
    private void closeSessions() {
        for (Session session : sessions.values()) {
            session.worker.close();
        }
        sessions.clear();
    }

    /** Replaces the current scenario with an empty backend and CCM. */
    private void resetScenario() throws Exception {
        Scenario next = mockScenario ? new MockScenarioStorage() : ScenarioStorage.open();
        checkBatchCompletion();
        closeSessions();
        Scenario previous = scenario;
        scenario = next;
        needsVerification = false;
        ccm =
                new ConcurrencyControlManager(
                        new TransactionManager(),
                        ProtocolConfig.create(protocol),
                        scenario.recovery());
        ccm.initializeAfterRecovery(new RecoveryInfo(0, 0));
        if (previous != null) {
            previous.close();
        }
        out().println("Protocol: " + ccm.activeProtocol());
    }

    /** Counts an unfinished batch scenario as a failed check. */
    private void checkBatchCompletion() {
        if (batch && needsVerification) {
            check("finish all transactions and run verify before ending the scenario", false);
        }
    }

    /** Begins a transaction on its own worker, opening the backend on first use. */
    private void begin(List<String> args) throws Exception {
        requireArity(args, 1, 1, "begin <tx>");
        String alias = args.getFirst();
        if (alias.isBlank() || sessions.containsKey(alias)) {
            throw new DriverException("empty or duplicate transaction alias: " + alias);
        }
        if (scenario == null) {
            resetScenario();
        }
        ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        Thread.ofVirtual().name("ccm-" + alias).factory());
        TxId id;
        try {
            id = await(worker.submit(ccm::begin));
        } catch (Exception e) {
            worker.close();
            throw e;
        }
        sessions.put(alias, new Session(id, worker));
        needsVerification = true;
        out().println("BEGIN " + alias + " (" + id + ")");
    }

    /** Looks up an existing transaction alias or reports invalid input. */
    private Session session(String alias) {
        Session session = sessions.get(alias);
        if (session == null) {
            throw new DriverException("unknown transaction: " + alias);
        }
        return session;
    }

    /** Validates an access command before submitting it to the transaction worker. */
    private AccessCommand parseAccess(AccessType type, List<String> args) {
        boolean writing = type == AccessType.WRITE;
        int required = writing ? 3 : 2;
        requireArity(args, required, required + 1, writing ? WRITE_USAGE : READ_USAGE);
        String alias = args.getFirst();
        Session session = session(alias);
        if (session.pending != null) {
            throw new DriverException(alias + " has a pending access; use retry or abort");
        }
        return new AccessCommand(
                session,
                alias,
                type,
                resource(args.get(1)),
                writing ? parseInt(args.get(2)) : 0,
                expected(args, required, ALLOWED));
    }

    /** Runs one access on its transaction worker and reports the result. */
    private void execute(AccessCommand access) throws Exception {
        show(access, await(access.session().worker.submit(() -> attempt(access))));
    }

    /** Applies an access only after CCM grants it and attempts abort on write failure. */
    private Attempt attempt(AccessCommand access)
            throws ConcurrencyInternalException, StorageInternalException {
        TxId id = access.session().id;
        Status status = ccm.requestAccess(id, access.type(), access.resource()).status();
        if (status != ALLOWED) {
            return new Attempt(status, 0);
        }

        try {
            int value =
                    scenario.apply(
                            ccm, new Access(id, access.type(), access.resource()), access.value());
            return new Attempt(status, value);
        } catch (ConcurrencyInternalException
                | StorageInternalException
                | RuntimeException failure) {
            if (access.type() == AccessType.WRITE) {
                try {
                    ccm.abort(id);
                } catch (ConcurrencyInternalException | RuntimeException rollback) {
                    failure.addSuppressed(rollback);
                }
            }
            throw failure;
        }
    }

    /** Queues an access on its worker behind the shared start signal. */
    private Future<Attempt> submitAtStart(AccessCommand access, CountDownLatch start) {
        return access.session()
                .worker
                .submit(
                        () -> {
                            start.await();
                            return attempt(access);
                        });
    }

    /** Reports the outcome and saves the command if it needs a retry. */
    private void show(AccessCommand access, Attempt attempt) {
        access.session().pending = attempt.status() == WAITING ? access : null;
        String label = access.alias() + " " + access.type() + " " + access.resource().table();
        check(label, access.expected(), attempt.status());
        if (attempt.status() == ALLOWED && access.type() == AccessType.READ) {
            out().println(label + " = " + attempt.value());
        }
    }

    /** Parses a quoted read or write command within a parallel step. */
    private AccessCommand parseParallelAccess(String input) {
        List<String> words = List.of(input.strip().split("\\s+"));
        AccessType type = parseEnum(AccessType.class, words.getFirst());
        return parseAccess(type, words.subList(1, words.size()));
    }

    /** Releases two access attempts together and checks both results. */
    private void parallel(List<String> args) throws Exception {
        requireArity(args, 2, 2, "parallel \"<access>\" \"<access>\"");
        AccessCommand first = parseParallelAccess(args.getFirst());
        AccessCommand second = parseParallelAccess(args.get(1));
        if (first.session() == second.session()) {
            throw new DriverException("parallel requires two different transactions");
        }
        CountDownLatch start = new CountDownLatch(1);
        Future<Attempt> firstResult = submitAtStart(first, start);
        Future<Attempt> secondResult;
        try {
            secondResult = submitAtStart(second, start);
        } finally {
            start.countDown();
        }
        try {
            show(first, await(firstResult));
        } finally {
            show(second, await(secondResult));
        }
    }

    /** Retries the saved access with the same operation arguments. */
    private void retry(List<String> args) throws Exception {
        requireArity(args, 1, 2, "retry <tx> [status]");
        Session session = session(args.getFirst());
        AccessCommand pending = session.pending;
        if (pending == null) {
            throw new DriverException(args.getFirst() + " has no pending access");
        }
        execute(
                new AccessCommand(
                        session,
                        pending.alias(),
                        pending.type(),
                        pending.resource(),
                        pending.value(),
                        expected(args, 1, ALLOWED)));
    }

    /** Commits or aborts on the transaction worker and checks the resulting status. */
    private void finish(List<String> args, TxStatus terminal) throws Exception {
        boolean committing = terminal == COMMITTED;
        String name = committing ? "commit" : "abort";
        requireArity(args, 1, 2, name + " <tx> [status]");
        String alias = args.getFirst();
        Session session = session(alias);
        TxStatus expected = expected(args, 1, terminal);
        if (committing
                && session.pending != null
                && scenario.recovery().getTransactionStatus(session.id) != ABORTED) {
            throw new DriverException(alias + " has a pending access; use retry or abort");
        }
        TxStatus actual =
                await(
                        session.worker.submit(
                                () ->
                                        (committing
                                                        ? ccm.commit(session.id)
                                                        : ccm.abort(session.id))
                                                .status()));
        session.pending = null;
        check(name + " " + alias, expected, actual);
    }

    /** Compares a stored test value with the expected value, treating an absent row as zero. */
    private void assertValue(List<String> args) throws StorageInternalException {
        requireArity(args, 3, 3, "assert value <table> <number>");
        if (!args.getFirst().equalsIgnoreCase("value")) {
            throw new DriverException("usage: assert value <table> <number>");
        }
        ResourceId row = resource(args.get(1));
        check(
                "value " + row.table(),
                parseInt(args.get(2)),
                scenario == null ? 0 : scenario.storedValue(row.table()).orElse(0));
    }

    /** Verifies a handwritten schedule or a completed live scenario. */
    private void verify(List<String> args) throws IOException, StorageInternalException {
        requireArity(args, 1, 2, "verify <serializable|cyclic> [file]");
        boolean expected =
                switch (args.getFirst().toLowerCase(Locale.ROOT)) {
                    case "serializable" -> true;
                    case "cyclic" -> false;
                    default -> throw new DriverException("expected serializable or cyclic");
                };
        if (args.size() == 2) {
            verifyManual(Path.of(args.get(1)), expected);
            return;
        }
        if (scenario == null) {
            showVerification(List.of(), Set.of(), expected);
            return;
        }

        Set<TxId> committed = new HashSet<>();
        for (Session session : sessions.values()) {
            TxStatus status = scenario.recovery().getTransactionStatus(session.id);
            if (status == COMMITTED) {
                committed.add(session.id);
            } else if (status != ABORTED) {
                throw new DriverException("finish all transactions before verifying the scenario");
            }
        }
        List<ExecutedAccess> executed = scenario.schedule();
        List<Access> schedule = executed.stream().map(ExecutedAccess::access).toList();
        Result result = showVerification(schedule, committed, expected);
        if (result.serializable()) {
            verifyReplay(executed, result.serialOrder());
        }
        needsVerification = false;
    }

    /** Builds a precedence graph from accesses and commits listed in a file. */
    private void verifyManual(Path file, boolean expected) throws IOException {
        Map<String, TxId> ids = new HashMap<>();
        List<Access> schedule = new ArrayList<>();
        Set<TxId> committed = new HashSet<>();
        List<String> lines = Files.readAllLines(file);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("--")) {
                continue;
            }
            List<String> words = List.of(line.split("\\s+"));
            try {
                boolean commit = words.getFirst().equalsIgnoreCase("commit");
                int arity = commit ? 2 : 3;
                requireArity(words, arity, arity, "read|write <tx> <table> or commit <tx>");
                TxId id = ids.computeIfAbsent(words.get(1), alias -> new TxId(ids.size() + 1L));
                if (committed.contains(id)) {
                    throw new DriverException("transaction already committed: " + words.get(1));
                }
                if (commit) {
                    committed.add(id);
                } else {
                    AccessType type = parseEnum(AccessType.class, words.getFirst());
                    schedule.add(new Access(id, type, resource(words.get(2))));
                }
            } catch (DriverException e) {
                throw new DriverException(file + ":" + (index + 1) + " " + e.getMessage());
            }
        }
        out().println("Transaction aliases: " + ids);
        showVerification(schedule, committed, expected);
    }

    /** Prints the schedule, graph, and serial order before checking serializability. */
    private Result showVerification(List<Access> schedule, Set<TxId> committed, boolean expected) {
        Result result = new PrecedenceGraphVerifier().verify(schedule, committed);
        out().println("Executed schedule: " + schedule);
        out().println("Precedence graph: " + result.graph());
        out().println("Serial order: " + result.serialOrder());
        check("serializable", expected, result.serializable());
        return result;
    }

    /** Compares actual reads and final rows with replay in the committed serial order. */
    private void verifyReplay(List<ExecutedAccess> executed, List<TxId> serialOrder)
            throws StorageInternalException {
        Map<ResourceId, Integer> replay = new HashMap<>();
        for (TxId id : serialOrder) {
            for (ExecutedAccess step : executed) {
                Access access = step.access();
                if (!access.tx().equals(id)) {
                    continue;
                }
                if (access.type() == AccessType.WRITE) {
                    replay.put(access.resource(), step.value());
                } else {
                    check(
                            "serial replay read " + id + " " + access.resource().table(),
                            replay.getOrDefault(access.resource(), 0),
                            step.value());
                }
            }
        }

        List<ResourceId> writtenTables =
                executed.stream()
                        .map(ExecutedAccess::access)
                        .filter(access -> access.type() == AccessType.WRITE)
                        .map(Access::resource)
                        .distinct()
                        .toList();
        for (ResourceId row : writtenTables) {
            check(
                    "serial replay final row " + row.table(),
                    Optional.ofNullable(replay.get(row)),
                    scenario.storedValue(row.table()));
        }
    }

    /** Normalizes a nonblank table name to the scenario resource key. */
    private static ResourceId resource(String table) {
        if (table.isBlank()) {
            throw new DriverException("table name is required");
        }
        return ResourceId.ofTable(table.strip().toLowerCase(Locale.ROOT));
    }

    /** Parses a signed 32-bit test value or reports invalid input. */
    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new DriverException("expected a 32-bit integer: " + value);
        }
    }

    /** Reads an optional expected status, using the command default when omitted. */
    private static <E extends Enum<E>> E expected(List<String> args, int index, E fallback) {
        return args.size() == index
                ? fallback
                : parseEnum(fallback.getDeclaringClass(), args.get(index));
    }

    /** Parses an enum name without regard to case. */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DriverException("invalid " + type.getSimpleName() + ": " + value);
        }
    }

    /** Reports command usage when the argument count is outside the accepted range. */
    private static void requireArity(List<String> args, int min, int max, String usage) {
        if (args.size() < min || args.size() > max) {
            throw new DriverException("usage: " + usage);
        }
    }

    /** Waits for a worker result and propagates its original exception. */
    private static <T> T await(Future<T> task) throws Exception {
        try {
            return task.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception failure) {
                throw failure;
            }
            throw e;
        }
    }
}
