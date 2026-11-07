package org.rafendb.concurrency.driver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.concurrency.ConcurrencyControlManager;
import org.rafendb.concurrency.protocol.LockBasedProtocol;
import org.rafendb.concurrency.protocol.Protocol;
import org.rafendb.concurrency.protocol.TimestampBasedProtocol;
import org.rafendb.concurrency.txn.TransactionManager;
import org.rafendb.concurrency.verify.PrecedenceGraphVerifier;
import org.rafendb.concurrency.verify.SerializabilityVerifier.Access;
import org.rafendb.engine.Version;

// scripts/run.sh driver ccm --protocol=TIMESTAMP_BASED

/** Standalone concurrent CCM scenarios with an in-memory recovery and storage fake. */
public final class ConcurrencyDriver {
    private static final ResourceId A = ResourceId.ofRow("a", new Rid(0, 0));
    private static final ResourceId B = ResourceId.ofRow("b", new Rid(0, 0));

    private enum Scenario {
        WAIT_AND_ROLLBACK,
        TWO_COMMITS,
        DEADLOCK
    }

    private ConcurrencyDriver() {}

    public static void main(String[] args) throws Exception {
        ProtocolType type = ProtocolType.LOCK_BASED;
        if (args.length > 1 || (args.length == 1 && !args[0].startsWith("--protocol="))) {
            throw new IllegalArgumentException("Usage: --protocol=LOCK_BASED|TIMESTAMP_BASED");
        }
        if (args.length == 1) {
            try {
                type = ProtocolType.valueOf(args[0].substring("--protocol=".length()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown protocol: " + args[0], e);
            }
        }
        System.out.println(Version.banner("Concurrency Control Manager Driver"));
        System.out.println("FRM and storage: in-memory fake");
        boolean passed;
        try {
            passed = runScenario(type, Scenario.WAIT_AND_ROLLBACK);
            passed &= runScenario(type, Scenario.TWO_COMMITS);
            if (type == ProtocolType.LOCK_BASED) {
                passed &= runScenario(type, Scenario.DEADLOCK);
            }
        } catch (Exception e) {
            System.out.println("FAIL: " + e);
            throw e;
        }
        if (!passed) {
            throw new IllegalStateException("One or more CCM scenarios failed");
        }
    }

    private static boolean runScenario(ProtocolType type, Scenario scenario) throws Exception {
        MemoryRecovery frm = new MemoryRecovery();
        Protocol protocol =
                switch (type) {
                    case LOCK_BASED -> new LockBasedProtocol();
                    case TIMESTAMP_BASED -> new TimestampBasedProtocol();
                };
        var ccm = new ConcurrencyControlManager(new TransactionManager(), protocol, frm);
        ccm.initializeAfterRecovery(new RecoveryInfo(0, 0));
        System.out.println("Protocol: " + ccm.activeProtocol());
        TxId first = ccm.begin();
        TxId second = ccm.begin();
        CountDownLatch firstWrite = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        List<Access> schedule = new ArrayList<>();
        System.out.println("Scenario: " + scenario);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var one =
                    executor.submit(
                            () -> {
                                require(access(ccm, first, A) == AccessResult.Status.ALLOWED);
                                write(ccm, frm, schedule, first, A, 1);
                                firstWrite.countDown();
                                require(secondReady.await(3, TimeUnit.SECONDS));
                                if (scenario == Scenario.DEADLOCK) {
                                    require(retry(ccm, first, B) == AccessResult.Status.ALLOWED);
                                    if (frm.value(B) != 0) {
                                        throw new IllegalStateException(
                                                "Victim rollback did not restore B");
                                    }
                                    System.out.println("Victim rollback restored B to 0");
                                    write(ccm, frm, schedule, first, B, 3);
                                }
                                require(ccm.commit(first).status() == TxStatus.COMMITTED);
                                System.out.println(first + " COMMITTED");
                                return true;
                            });
            var two =
                    executor.submit(
                            () -> {
                                require(firstWrite.await(3, TimeUnit.SECONDS));
                                if (scenario == Scenario.DEADLOCK) {
                                    require(access(ccm, second, B) == AccessResult.Status.ALLOWED);
                                    write(ccm, frm, schedule, second, B, 2);
                                    secondReady.countDown();
                                    require(retry(ccm, second, A) == AccessResult.Status.ABORTED);
                                } else {
                                    require(access(ccm, second, A) == AccessResult.Status.WAITING);
                                    secondReady.countDown();
                                    require(retry(ccm, second, A) == AccessResult.Status.ALLOWED);
                                    write(ccm, frm, schedule, second, A, 2);
                                    if (scenario == Scenario.TWO_COMMITS) {
                                        require(ccm.commit(second).status() == TxStatus.COMMITTED);
                                    } else {
                                        require(ccm.abort(second).status() == TxStatus.ABORTED);
                                    }
                                }
                                System.out.println(
                                        second
                                                + (scenario == Scenario.TWO_COMMITS
                                                        ? " COMMITTED"
                                                        : " ABORTED"));
                                return true;
                            });
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
        }
        Set<TxId> committed =
                scenario == Scenario.TWO_COMMITS ? Set.of(first, second) : Set.of(first);
        var verification = new PrecedenceGraphVerifier().verify(List.copyOf(schedule), committed);
        boolean passed =
                verification.serializable()
                        && frm.value(A) == (scenario == Scenario.TWO_COMMITS ? 2 : 1)
                        && (scenario != Scenario.DEADLOCK || frm.value(B) == 3)
                        && (scenario != Scenario.TWO_COMMITS
                                || (verification
                                                .graph()
                                                .equals(
                                                        Map.of(
                                                                first,
                                                                Set.of(second),
                                                                second,
                                                                Set.of()))
                                        && verification
                                                .serialOrder()
                                                .equals(List.of(first, second))));
        System.out.println("Executed schedule: " + schedule);
        System.out.println("Precedence graph: " + verification.graph());
        System.out.println("Serial order: " + verification.serialOrder());
        System.out.println("Final values: a=" + frm.value(A) + ", b=" + frm.value(B));
        System.out.println(passed ? "PASS" : "FAIL");
        return passed;
    }

    private static AccessResult.Status access(
            ConcurrencyControlManager ccm, TxId tx, ResourceId row) {
        var status = ccm.requestAccess(tx, AccessType.WRITE, row).status();
        System.out.println(tx + " WRITE " + row.table() + " " + status);
        return status;
    }

    private static AccessResult.Status retry(ConcurrencyControlManager ccm, TxId tx, ResourceId row)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        AccessResult.Status status;
        do {
            status = access(ccm, tx, row);
            if (status == AccessResult.Status.WAITING) {
                Thread.sleep(10);
            }
        } while (status == AccessResult.Status.WAITING && System.nanoTime() < deadline);
        return status;
    }

    private static void write(
            ConcurrencyControlManager ccm,
            MemoryRecovery frm,
            List<Access> schedule,
            TxId tx,
            ResourceId row,
            int value)
            throws ConcurrencyInternalException {
        synchronized (frm) {
            var before = new byte[] {(byte) frm.value(row)};
            var after = new byte[] {(byte) value};
            ccm.logChange(tx, new ChangeRecord(row, Optional.of(before), Optional.of(after)));
            frm.values.put(row, value);
            schedule.add(new Access(tx, AccessType.WRITE, row));
        }
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalStateException("Scenario expectation failed");
        }
    }

    /** In-memory fake; it is not durable and exists only for these scenarios. */
    private static final class MemoryRecovery implements RecoveryManager {
        private final Map<ResourceId, Integer> values = new HashMap<>();
        private final Map<TxId, List<ChangeRecord>> changes = new HashMap<>();
        private final Map<TxId, TxStatus> statuses = new HashMap<>();
        private long lsn;

        synchronized int value(ResourceId row) {
            return values.getOrDefault(row, 0);
        }

        @Override
        public synchronized long writeLog(LogPayload payload) {
            changes.computeIfAbsent(payload.tx(), ignored -> new ArrayList<>())
                    .add(payload.change().orElseThrow());
            return ++lsn;
        }

        @Override
        public void forceThrough(long lsn) {}

        @Override
        public synchronized boolean beginTransaction(TxId tx) {
            statuses.put(tx, TxStatus.ACTIVE);
            return true;
        }

        @Override
        public synchronized boolean commitTransaction(TxId tx) {
            statuses.put(tx, TxStatus.COMMITTED);
            return true;
        }

        @Override
        public synchronized boolean rollbackTransaction(TxId tx) {
            var list = changes.getOrDefault(tx, List.of());
            for (int i = list.size() - 1; i >= 0; i--) {
                ChangeRecord change = list.get(i);
                values.put(change.resource(), (int) change.beforeImage().orElseThrow()[0]);
            }
            statuses.put(tx, TxStatus.ABORTED);
            return true;
        }

        @Override
        public boolean createCheckpoint() {
            return true;
        }

        @Override
        public boolean recoverToPointInTime(long timestamp) {
            return false;
        }

        @Override
        public boolean recoverToPointInTime() {
            return false;
        }

        @Override
        public boolean recoverFromCrash() {
            return true;
        }

        @Override
        public synchronized TxStatus getTransactionStatus(TxId tx) {
            return statuses.get(tx);
        }
    }
}
