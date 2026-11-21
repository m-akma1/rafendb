package org.rafendb.concurrency.txn;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;

class TransactionManagerTest {

    private static final Map<TxStatus, Set<TxStatus>> ALLOWED =
            Map.of(
                    TxStatus.ACTIVE, Set.of(TxStatus.PARTIALLY_COMMITTED, TxStatus.FAILED),
                    TxStatus.PARTIALLY_COMMITTED, Set.of(TxStatus.COMMITTED, TxStatus.FAILED),
                    TxStatus.FAILED, Set.of(TxStatus.ABORTED),
                    TxStatus.ABORTED, Set.of(),
                    TxStatus.COMMITTED, Set.of());

    private final TransactionManager manager = new TransactionManager();

    private TransactionManager opened() {
        manager.open(0);
        return manager;
    }

    private void driveTo(TxId tx, TxStatus target) {
        switch (target) {
            case ACTIVE -> {}
            case PARTIALLY_COMMITTED -> manager.transition(tx, TxStatus.PARTIALLY_COMMITTED);
            case FAILED -> manager.transition(tx, TxStatus.FAILED);
            case COMMITTED -> {
                manager.transition(tx, TxStatus.PARTIALLY_COMMITTED);
                manager.transition(tx, TxStatus.COMMITTED);
            }
            case ABORTED -> {
                manager.transition(tx, TxStatus.FAILED);
                manager.transition(tx, TxStatus.ABORTED);
            }
        }
    }

    @Test
    void beginIsRefusedUntilOpened() {
        assertThrows(IllegalStateException.class, manager::begin);
    }

    @Test
    void openRejectsNegativeHighWaterAndSecondOpen() {
        assertThrows(IllegalArgumentException.class, () -> manager.open(-1));
        manager.open(0);
        assertThrows(IllegalStateException.class, () -> manager.open(0));
    }

    @Test
    void idsStartAboveTheRecoveredHighWaterAndGrowByOne() {
        manager.open(41);
        assertEquals(new TxId(42), manager.begin());
        assertEquals(new TxId(43), manager.begin());
    }

    @Test
    void newTransactionStartsActive() {
        TxId tx = opened().begin();
        assertEquals(TxStatus.ACTIVE, manager.status(tx));
    }

    @Test
    void idsAreExhaustedAtTheMaximum() {
        manager.open(Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, manager::begin);
    }

    @Test
    void timestampFollowsBeginOrder() {
        opened();
        TxId first = manager.begin();
        TxId second = manager.begin();
        assertTrue(manager.timestamp(first) > 0);
        assertTrue(manager.timestamp(first) < manager.timestamp(second));
    }

    @Test
    void unknownAndNullTransactionsAreRejected() {
        opened();
        TxId unknown = new TxId(999);
        assertThrows(IllegalArgumentException.class, () -> manager.status(unknown));
        assertThrows(IllegalArgumentException.class, () -> manager.timestamp(unknown));
        assertThrows(
                IllegalArgumentException.class, () -> manager.transition(unknown, TxStatus.FAILED));
        assertThrows(NullPointerException.class, () -> manager.status(null));
        assertThrows(NullPointerException.class, () -> manager.timestamp(null));
    }

    @Test
    void concurrentBeginsGiveUniqueIncreasingIds() throws Exception {
        opened();
        int threads = 16;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<List<TxId>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<List<TxId>> worker =
                        () -> {
                            start.await();
                            List<TxId> mine = new ArrayList<>();
                            for (int j = 0; j < perThread; j++) {
                                mine.add(manager.begin());
                            }
                            return mine;
                        };
                futures.add(pool.submit(worker));
            }
            start.countDown();
            Set<TxId> all = new HashSet<>();
            for (Future<List<TxId>> future : futures) {
                List<TxId> mine = future.get(10, TimeUnit.SECONDS);
                for (int j = 1; j < mine.size(); j++) {
                    assertTrue(
                            mine.get(j - 1).compareTo(mine.get(j)) < 0,
                            "ids of one client must increase");
                }
                all.addAll(mine);
            }
            assertEquals(threads * perThread, all.size(), "every id must be unique");
            assertEquals(new TxId(threads * perThread), Collections.max(all));
            assertEquals(new TxId(1), Collections.min(all));
        }
    }

    @Test
    void transitionsFollowTheStateMachine() {
        opened();
        for (TxStatus from : TxStatus.values()) {
            for (TxStatus to : TxStatus.values()) {
                TxId tx = manager.begin();
                driveTo(tx, from);
                String move = from + " -> " + to;
                if (ALLOWED.get(from).contains(to)) {
                    manager.transition(tx, to);
                    assertEquals(to, manager.status(tx), move);
                } else {
                    assertThrows(
                            IllegalStateException.class, () -> manager.transition(tx, to), move);
                    assertEquals(from, manager.status(tx), move + " must leave the status alone");
                }
            }
        }
    }

    @Test
    void transitionRejectsNullTarget() {
        TxId tx = opened().begin();
        assertThrows(NullPointerException.class, () -> manager.transition(tx, null));
    }

    @Test
    void committedAndAbortedAreFinalForEveryTarget() {
        opened();
        for (TxStatus finalStatus : new TxStatus[] {TxStatus.COMMITTED, TxStatus.ABORTED}) {
            TxId tx = manager.begin();
            driveTo(tx, finalStatus);
            for (TxStatus next : TxStatus.values()) {
                assertThrows(
                        IllegalStateException.class,
                        () -> manager.transition(tx, next),
                        finalStatus + " -> " + next);
            }
            assertEquals(finalStatus, manager.status(tx));
        }
    }

    @Test
    void pauseHoldsBeginUntilResume() throws Exception {
        opened();
        manager.pause();
        Callable<TxId> begin = manager::begin;
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<TxId> held = pool.submit(begin);
            assertThrows(TimeoutException.class, () -> held.get(200, TimeUnit.MILLISECONDS));
            manager.resume();
            assertEquals(new TxId(1), held.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void pausingDoesNotStopRunningTransactionsFromFinishing() {
        TxId tx = opened().begin();
        manager.pause();
        driveTo(tx, TxStatus.COMMITTED);
        assertEquals(TxStatus.COMMITTED, manager.status(tx));
        manager.resume();
    }

    @Test
    void pauseAndResumeAreHarmlessWhenRepeated() {
        opened();
        manager.resume();
        manager.pause();
        manager.pause();
        manager.resume();
        assertEquals(new TxId(1), manager.begin());
    }

    @Test
    void interruptWhileHeldGivesIllegalStateAndKeepsTheInterruptFlag() throws Exception {
        opened();
        manager.pause();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                manager.begin();
                            } catch (Throwable e) {
                                error.set(e);
                                flag.set(Thread.currentThread().isInterrupted());
                            }
                        });
        thread.start();
        Thread.sleep(100);
        thread.interrupt();
        thread.join(5000);
        assertFalse(thread.isAlive());
        assertInstanceOf(IllegalStateException.class, error.get());
        assertEquals(Boolean.TRUE, flag.get());
        manager.resume();
    }

    @Test
    void awaitNoActiveReturnsAtOnceWhenNothingRuns() throws Exception {
        opened();
        assertTrue(manager.awaitNoActive(Duration.ZERO));
        assertTrue(manager.awaitNoActive(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void awaitNoActiveTimesOutWhileATransactionRuns() throws Exception {
        opened().begin();
        assertFalse(manager.awaitNoActive(Duration.ZERO));
        assertFalse(manager.awaitNoActive(Duration.ofMillis(50)));
    }

    @Test
    void failedTransactionStillCountsUntilItIsAborted() throws Exception {
        TxId tx = opened().begin();
        manager.transition(tx, TxStatus.FAILED);
        assertFalse(manager.awaitNoActive(Duration.ofMillis(50)));
        manager.transition(tx, TxStatus.ABORTED);
        assertTrue(manager.awaitNoActive(Duration.ofMillis(50)));
    }

    @Test
    void awaitNoActiveWakesWhenTheLastTransactionFinishes() throws Exception {
        opened();
        TxId first = manager.begin();
        TxId second = manager.begin();
        driveTo(first, TxStatus.COMMITTED);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(
                    () -> {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        driveTo(second, TxStatus.ABORTED);
                    });
            assertTrue(manager.awaitNoActive(Duration.ofSeconds(5)));
        }
    }

    @Test
    void awaitNoActiveRejectsBadTimeouts() {
        opened();
        assertThrows(NullPointerException.class, () -> manager.awaitNoActive(null));
        assertThrows(
                IllegalArgumentException.class, () -> manager.awaitNoActive(Duration.ofMillis(-1)));
    }
}
