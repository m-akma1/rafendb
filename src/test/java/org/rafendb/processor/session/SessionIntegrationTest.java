package org.rafendb.processor.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.api.processor.Session;
import org.rafendb.api.processor.Session.TxState;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.RecoveryInternalException;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.log.LogType;
import org.rafendb.common.query.QueryResult;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.Value;
import org.rafendb.concurrency.ConcurrencyControlManager;
import org.rafendb.concurrency.protocol.LockBasedProtocol;
import org.rafendb.concurrency.txn.TransactionManager;
import org.rafendb.engine.Engine;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.recovery.FRManager;
import org.rafendb.recovery.log.Logger;

/**
 * Sessions over the real SM, FRM, and CCM (two-phase locking) on a temporary data directory,
 * seeded with the sample database: customers, orders, and order_items, 60 rows each, where order
 * {@code i} belongs to customer {@code i}.
 */
@Timeout(30)
class SessionIntegrationTest {
    @TempDir Path dir;

    private Stack stack;

    @AfterEach
    void closeStack() throws Exception {
        if (stack != null) {
            stack.shutDown();
        }
    }

    @Test
    void seededDataAnswersScanFilterJoinAndSortQueries() throws Exception {
        stack = new Stack(dir, true);
        Session session = stack.processor.openSession();

        assertEquals(60, rows(session, "SELECT * FROM customers").rows().size());
        var joined =
                rows(
                        session,
                        "SELECT order_id, orders.customer_id, customers.customer_id FROM orders"
                                + " JOIN customers ON orders.customer_id = customers.customer_id");
        assertEquals(60, joined.rows().size());
        assertEquals(
                List.of("order_id", "orders.customer_id", "customers.customer_id"),
                joined.columns());
        var sorted =
                rows(
                        session,
                        "SELECT order_id FROM orders WHERE amount > 60 ORDER BY order_id DESC");
        assertEquals(10, sorted.rows().size());
        assertEquals(List.of(Value.ofInt(60)), sorted.rows().getFirst().values());

        // An indexed column; QO still prefers a table scan at this size (see the batch notes).
        assertEquals(
                List.of(Value.ofInt(7)),
                rows(session, "SELECT order_id FROM orders WHERE customer_id = 7")
                        .rows()
                        .getFirst()
                        .values());
        assertEquals(TxState.IDLE, session.transactionState());
    }

    @Test
    void limitOrNotAndSelfJoinAliasesWorkThroughTheSession() throws Exception {
        stack = new Stack(dir, true);
        Session session = stack.processor.openSession();

        var top = rows(session, "SELECT order_id FROM orders ORDER BY order_id DESC LIMIT 3");
        assertEquals(
                List.of(
                        List.of(Value.ofInt(60)),
                        List.of(Value.ofInt(59)),
                        List.of(Value.ofInt(58))),
                top.rows().stream().map(row -> row.values()).toList());
        assertEquals(0, rows(session, "SELECT * FROM orders LIMIT 0").rows().size());

        String someIds = "SELECT customer_id FROM customers WHERE ";
        assertEquals(
                2, rows(session, someIds + "customer_id = 1 OR customer_id = 60").rows().size());
        assertEquals(2, rows(session, someIds + "NOT (customer_id > 2)").rows().size());
        assertEquals(
                3,
                rows(session, someIds + "customer_id < 3 OR customer_id = 7 AND credit > -1")
                        .rows()
                        .size());

        var pairs =
                rows(
                        session,
                        "SELECT a.customer_id, b.customer_id FROM orders AS a JOIN orders AS b"
                                + " ON a.customer_id = b.customer_id WHERE a.order_id = 1");
        assertEquals(List.of(Value.ofInt(1), Value.ofInt(1)), pairs.rows().getFirst().values());
        assertEquals(2, pairs.columns().stream().distinct().count(), pairs.columns().toString());
    }

    @Test
    void explainTakesNoLocks() throws Exception {
        stack = new Stack(dir, true);
        Session writer = stack.processor.openSession();
        Session reader = stack.processor.openSession();
        writer.execute("BEGIN");
        writer.execute("INSERT INTO customers VALUES (61, 'New', 1.5, 'A')");

        // A SELECT would wait for the writer (see aReaderWaitsUntilTheWriterCommits).
        CompletableFuture<QueryResult> explain =
                CompletableFuture.supplyAsync(
                        () -> reader.execute("EXPLAIN SELECT * FROM customers WHERE credit > 1"));
        assertInstanceOf(QueryResult.Status.class, explain.get(5, TimeUnit.SECONDS));
        assertEquals(TxState.IDLE, reader.transactionState());
        writer.execute("ABORT");
    }

    @Test
    void committedWritesSurviveARestartAndAnOpenTransactionDoesNot() throws Exception {
        stack = new Stack(dir, true);
        Session session = stack.processor.openSession();
        assertEquals(1, affected(session, "INSERT INTO customers VALUES (61, 'New', 1.5, 'A')"));
        assertEquals(1, affected(session, "UPDATE customers SET credit = 0 WHERE customer_id = 1"));
        assertEquals(20, affected(session, "DELETE FROM order_items WHERE gift = 'Y'"));
        session.execute("BEGIN");
        assertEquals(1, affected(session, "INSERT INTO customers VALUES (62, 'Lost', 1.5, 'A')"));
        session.close(); // rolls the open transaction back

        stack.shutDown();
        stack = new Stack(dir, false);
        Session after = stack.processor.openSession();

        assertEquals(61, rows(after, "SELECT * FROM customers").rows().size());
        assertEquals(
                0, rows(after, "SELECT * FROM customers WHERE customer_id = 62").rows().size());
        assertEquals(
                List.of(Value.ofFloat(0f)),
                rows(after, "SELECT credit FROM customers WHERE customer_id = 1")
                        .rows()
                        .getFirst()
                        .values());
        assertEquals(40, rows(after, "SELECT * FROM order_items").rows().size());
    }

    @Test
    void aReaderWaitsUntilTheWriterCommits() throws Exception {
        stack = new Stack(dir, true);
        Session writer = stack.processor.openSession();
        Session reader = stack.processor.openSession();
        writer.execute("BEGIN");
        writer.execute("INSERT INTO customers VALUES (61, 'New', 1.5, 'A')");

        CompletableFuture<QueryResult> read =
                CompletableFuture.supplyAsync(() -> reader.execute("SELECT * FROM customers"));
        assertThrows(TimeoutException.class, () -> read.get(300, TimeUnit.MILLISECONDS));

        writer.execute("COMMIT");

        assertEquals(61, ((QueryResult.Rows) read.get(10, TimeUnit.SECONDS)).rows().size());
        assertEquals(TxState.IDLE, reader.transactionState());
    }

    @Test
    void aDeadlockVictimFailsAndOnlyItsSessionMustAbort() throws Exception {
        stack = new Stack(dir, true);
        Session first = stack.processor.openSession();
        Session second = stack.processor.openSession();
        first.execute("BEGIN");
        first.execute("UPDATE customers SET credit = 1 WHERE customer_id = 1");
        second.execute("BEGIN");
        second.execute("UPDATE orders SET amount = 1 WHERE order_id = 1");

        CompletableFuture<QueryResult> firstWaits =
                CompletableFuture.supplyAsync(() -> first.execute("SELECT * FROM orders"));
        assertThrows(TimeoutException.class, () -> firstWaits.get(300, TimeUnit.MILLISECONDS));
        Throwable secondFailure = failureOf(() -> second.execute("SELECT * FROM customers"));
        Throwable firstFailure = failureOf(() -> firstWaits.get(10, TimeUnit.SECONDS));

        assertTrue(
                (firstFailure == null) != (secondFailure == null),
                "exactly one victim: " + firstFailure + " / " + secondFailure);
        Session victim = firstFailure != null ? first : second;
        Session survivor = victim == first ? second : first;
        assertInstanceOf(
                TransactionAbortedException.class,
                firstFailure != null ? firstFailure : secondFailure);
        assertEquals(TxState.FAILED, victim.transactionState());
        assertEquals(TxState.ACTIVE, survivor.transactionState());

        victim.execute("ABORT");
        survivor.execute("COMMIT");
        assertEquals(TxState.IDLE, victim.transactionState());
        assertEquals(TxState.IDLE, survivor.transactionState());
    }

    private static QueryResult.Rows rows(Session session, String sql) {
        return (QueryResult.Rows) session.execute(sql);
    }

    private static long affected(Session session, String sql) {
        return ((QueryResult.Affected) session.execute(sql)).count();
    }

    private interface Call {
        void run() throws Exception;
    }

    /** What the call threw, unwrapped from the future; {@code null} if it succeeded. */
    private static Throwable failureOf(Call call) {
        try {
            call.run();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    /** The components wired by hand, in the engine's start order; no Engine yet. */
    private static final class Stack {
        private final Logger logger;
        private final StorageManager storage;
        private final FRManager frm;
        private final QueryProcessorImpl processor;

        Stack(Path dir, boolean seed) throws Exception {
            logger = new Logger(dir.resolve("wal.log"));
            storage = Engine.storage(dir, 64, logger);
            frm = new FRManager(logger, storage);
            assertTrue(frm.recoverFromCrash(), "crash recovery");
            storage.flush();
            if (seed) {
                seed();
            }
            var ccm =
                    new ConcurrencyControlManager(
                            new TransactionManager(), new LockBasedProtocol(), frm);
            ccm.initializeAfterRecovery(frm.getRecoveryInfo());
            processor = new QueryProcessorImpl(ccm, storage, () -> Engine.optimizer(storage));
        }

        /** One logged bootstrap transaction, before CCM hands out ids. */
        private void seed() throws Exception {
            TxId tx = new TxId(logger.getHighestTransactionId() + 1);
            assertTrue(frm.beginTransaction(tx));
            storage.loadSampleDatabase(
                    change -> {
                        try {
                            return frm.writeLog(LogPayload.ofChange(tx, LogType.INSERT, change));
                        } catch (RecoveryInternalException e) {
                            throw new IllegalStateException(e);
                        }
                    });
            assertTrue(frm.commitTransaction(tx));
        }

        /** Storage closes (and flushes) before its WAL. */
        void shutDown() throws Exception {
            try (logger;
                    storage) {
                // closed in reverse order
            }
        }
    }
}
