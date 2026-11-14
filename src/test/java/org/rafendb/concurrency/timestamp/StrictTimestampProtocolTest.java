package org.rafendb.concurrency.timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rafendb.concurrency.timestamp.TimestampProtocol.Decision.ABORT_REQUIRED;
import static org.rafendb.concurrency.timestamp.TimestampProtocol.Decision.ALLOWED;
import static org.rafendb.concurrency.timestamp.TimestampProtocol.Decision.WAITING;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.timestamp.TimestampProtocol.Decision;

/** Tests for {@link StrictTimestampProtocol}. A transaction's timestamp equals its id here. */
final class StrictTimestampProtocolTest {

    private static final ResourceId ACCOUNTS = ResourceId.ofTable("accounts");
    private static final ResourceId ORDERS = ResourceId.ofTable("orders");

    private final StrictTimestampProtocol protocol = new StrictTimestampProtocol();

    private static TxId tx(long id) {
        return new TxId(id);
    }

    private Decision read(long id, ResourceId resource) {
        return protocol.requestAccess(tx(id), id, AccessType.READ, resource);
    }

    private Decision write(long id, ResourceId resource) {
        return protocol.requestAccess(tx(id), id, AccessType.WRITE, resource);
    }

    private void commit(long id) {
        protocol.complete(tx(id), TxStatus.COMMITTED);
    }

    private void abort(long id) {
        protocol.complete(tx(id), TxStatus.ABORTED);
    }

    // ---- basic timestamp rules -------------------------------------------------------------

    @Test
    void firstReadAndFirstWriteAreAllowed() {
        assertEquals(ALLOWED, read(1, ACCOUNTS));
        assertEquals(ALLOWED, write(2, ORDERS));
    }

    @Test
    void readersDoNotBlockEachOther() {
        assertEquals(ALLOWED, read(1, ACCOUNTS));
        assertEquals(ALLOWED, read(2, ACCOUNTS));
        assertEquals(ALLOWED, read(3, ACCOUNTS));
    }

    @Test
    void writeIsRejectedWhenAYoungerTransactionAlreadyRead() {
        assertEquals(ALLOWED, read(2, ACCOUNTS));
        assertEquals(ABORT_REQUIRED, write(1, ACCOUNTS));
    }

    @Test
    void writeIsAllowedWhenOnlyOlderTransactionsRead() {
        assertEquals(ALLOWED, read(1, ACCOUNTS));
        assertEquals(ALLOWED, write(2, ACCOUNTS));
    }

    @Test
    void readIsRejectedWhenAYoungerTransactionAlreadyWrote() {
        assertEquals(ALLOWED, write(2, ACCOUNTS));
        commit(2);
        assertEquals(ABORT_REQUIRED, read(1, ACCOUNTS));
    }

    @Test
    void writeIsRejectedWhenAYoungerTransactionAlreadyWrote() {
        assertEquals(ALLOWED, write(2, ACCOUNTS));
        commit(2);
        assertEquals(ABORT_REQUIRED, write(1, ACCOUNTS));
    }

    @Test
    void timestampViolationIsRejectedInsteadOfWaiting() {
        assertEquals(ALLOWED, write(2, ACCOUNTS));
        // T1 is older than the uncommitted writer, so waiting could never help it.
        assertEquals(ABORT_REQUIRED, read(1, ACCOUNTS));
        assertEquals(ABORT_REQUIRED, write(1, ACCOUNTS));
    }

    @Test
    void tablesAreIndependent() {
        // Different tables have different writers, so each one blocks only its own readers.
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        assertEquals(ALLOWED, write(2, ORDERS));
        assertEquals(WAITING, read(3, ACCOUNTS));
        assertEquals(WAITING, read(3, ORDERS));
        commit(1);
        assertEquals(ALLOWED, read(3, ACCOUNTS));
        assertEquals(WAITING, read(3, ORDERS));
    }

    // ---- strictness: no dirty read, no dirty write ------------------------------------------

    @Test
    void dirtyReadIsPreventedUntilTheWriterCommits() {
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        assertEquals(WAITING, read(2, ACCOUNTS));
        commit(1);
        assertEquals(ALLOWED, read(2, ACCOUNTS));
    }

    @Test
    void dirtyWriteIsPreventedUntilTheWriterCommits() {
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        assertEquals(WAITING, write(2, ACCOUNTS));
        commit(1);
        assertEquals(ALLOWED, write(2, ACCOUNTS));
    }

    @Test
    void waiterProceedsAfterTheWriterAborts() {
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        assertEquals(WAITING, read(2, ACCOUNTS));
        abort(1);
        assertEquals(ALLOWED, read(2, ACCOUNTS));
    }

    @Test
    void waitingDoesNotChangeAnyState() {
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        assertEquals(WAITING, write(3, ACCOUNTS));
        assertEquals(WAITING, read(3, ACCOUNTS));
        commit(1);
        // If waiting had raised W-TS or R-TS to 3, T2 would now be rejected.
        assertEquals(ALLOWED, write(2, ACCOUNTS));
    }

    @Test
    void abortKeepsTimestampHistory() {
        assertEquals(ALLOWED, write(6, ACCOUNTS));
        abort(6);
        assertEquals(ABORT_REQUIRED, read(5, ACCOUNTS));
        assertEquals(ALLOWED, read(7, ACCOUNTS));
    }

    // ---- idempotency -----------------------------------------------------------------------

    @Test
    void repeatedWriteByTheOwnerStaysAllowed() {
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        commit(1);
        assertEquals(ALLOWED, write(2, ACCOUNTS));
    }

    @Test
    void ownerCanReadItsOwnUncommittedWrite() {
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        assertEquals(ALLOWED, read(1, ACCOUNTS));
    }

    @Test
    void readThenWriteBySameTransactionIsAllowed() {
        assertEquals(ALLOWED, read(1, ACCOUNTS));
        assertEquals(ALLOWED, write(1, ACCOUNTS));
    }

    @Test
    void repeatedReadStaysAllowed() {
        assertEquals(ALLOWED, read(1, ACCOUNTS));
        assertEquals(ALLOWED, read(1, ACCOUNTS));
    }

    @Test
    void waitingRequestCanBeRepeatedUntilGranted() {
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        assertEquals(WAITING, read(2, ACCOUNTS));
        assertEquals(WAITING, read(2, ACCOUNTS));
        commit(1);
        assertEquals(ALLOWED, read(2, ACCOUNTS));
        assertEquals(ALLOWED, read(2, ACCOUNTS));
    }

    // ---- complete --------------------------------------------------------------------------

    @Test
    void completeIsIdempotentAndIgnoresUnknownTransactions() {
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        commit(1);
        commit(1);
        abort(1);
        abort(99);
    }

    @Test
    void completeReleasesEveryTableOfTheTransaction() {
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        assertEquals(ALLOWED, write(1, ORDERS));
        assertEquals(WAITING, read(2, ACCOUNTS));
        assertEquals(WAITING, read(2, ORDERS));
        commit(1);
        assertEquals(ALLOWED, read(2, ACCOUNTS));
        assertEquals(ALLOWED, read(2, ORDERS));
    }

    @Test
    void completeRejectsNonFinalStatus() {
        for (TxStatus status :
                new TxStatus[] {TxStatus.ACTIVE, TxStatus.PARTIALLY_COMMITTED, TxStatus.FAILED}) {
            assertThrows(IllegalArgumentException.class, () -> protocol.complete(tx(1), status));
        }
    }

    @Test
    void failedTransactionKeepsItsProtectionUntilCompleted() {
        assertEquals(ALLOWED, write(1, ACCOUNTS));
        // FAILED is not terminal: nothing is released, so waiters keep waiting.
        assertThrows(
                IllegalArgumentException.class, () -> protocol.complete(tx(1), TxStatus.FAILED));
        assertEquals(WAITING, read(2, ACCOUNTS));
    }

    // ---- argument validation ---------------------------------------------------------------

    @Test
    void requestAccessRejectsNullArguments() {
        assertThrows(
                NullPointerException.class,
                () -> protocol.requestAccess(null, 1, AccessType.READ, ACCOUNTS));
        assertThrows(
                NullPointerException.class, () -> protocol.requestAccess(tx(1), 1, null, ACCOUNTS));
        assertThrows(
                NullPointerException.class,
                () -> protocol.requestAccess(tx(1), 1, AccessType.READ, null));
    }

    @Test
    void requestAccessRejectsNonPositiveTimestamp() {
        assertThrows(
                IllegalArgumentException.class,
                () -> protocol.requestAccess(tx(1), 0, AccessType.READ, ACCOUNTS));
        assertThrows(
                IllegalArgumentException.class,
                () -> protocol.requestAccess(tx(1), -5, AccessType.WRITE, ACCOUNTS));
    }

    @Test
    void requestAccessRejectsRowLevelResource() {
        ResourceId row = ResourceId.ofRow("accounts", new Rid(0, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> protocol.requestAccess(tx(1), 1, AccessType.READ, row));
    }

    @Test
    void completeRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> protocol.complete(null, TxStatus.COMMITTED));
        assertThrows(NullPointerException.class, () -> protocol.complete(tx(1), null));
    }

    // ---- thread-safety ---------------------------------------------------------------------

    @Test
    void concurrentWritersNeverOverlapAndAlwaysFinish() throws Exception {
        final int threads = 8;
        final int perThread = 300;
        AtomicLong clock = new AtomicLong();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    for (int i = 0; i < perThread; i++) {
                                        long ts = clock.incrementAndGet();
                                        TxId me = new TxId(ts);
                                        Decision d;
                                        do {
                                            d =
                                                    protocol.requestAccess(
                                                            me, ts, AccessType.WRITE, ACCOUNTS);
                                            if (d == WAITING) {
                                                Thread.onSpinWait();
                                            }
                                        } while (d == WAITING);

                                        if (d == ALLOWED) {
                                            if (inside.incrementAndGet() != 1) {
                                                overlaps.incrementAndGet();
                                            }
                                            inside.decrementAndGet();
                                            protocol.complete(me, TxStatus.COMMITTED);
                                            commits.incrementAndGet();
                                        } else {
                                            protocol.complete(me, TxStatus.ABORTED);
                                            aborts.incrementAndGet();
                                        }
                                    }
                                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, overlaps.get());
        assertEquals(threads * perThread, commits.get() + aborts.get());
        assertTrue(commits.get() > 0);
    }

    @Test
    void concurrentReadersAndWritersOnManyTablesFinish() throws Exception {
        final int threads = 8;
        final int perThread = 300;
        final int tableCount = 4;
        AtomicLong clock = new AtomicLong();
        AtomicInteger finished = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    for (int i = 0; i < perThread; i++) {
                                        long ts = clock.incrementAndGet();
                                        TxId me = new TxId(ts);
                                        ResourceId table =
                                                ResourceId.ofTable("t" + (ts % tableCount));
                                        AccessType type =
                                                ts % 3 == 0 ? AccessType.WRITE : AccessType.READ;
                                        Decision d;
                                        do {
                                            d = protocol.requestAccess(me, ts, type, table);
                                            if (d == WAITING) {
                                                Thread.onSpinWait();
                                            }
                                        } while (d == WAITING);
                                        protocol.complete(
                                                me,
                                                d == ALLOWED
                                                        ? TxStatus.COMMITTED
                                                        : TxStatus.ABORTED);
                                        finished.incrementAndGet();
                                    }
                                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, finished.get());
    }
}
