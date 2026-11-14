package org.rafendb.recovery.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;

class CheckpointManagerTest {

    @TempDir Path tempDir;

    @Test
    void waitsForActiveTransactionsBeforeFlushingAndEndingCheckpoint() throws Exception {
        List<String> calls = new ArrayList<>();
        ConcurrencyControl ccm =
                proxy(
                        ConcurrencyControl.class,
                        (method, args) -> {
                            if (method.getName().equals("pauseNewTransactions")) {
                                calls.add("pause");
                            } else if (method.getName().equals("awaitNoActive")) {
                                calls.add("await");
                                return true;
                            } else if (method.getName().equals("resumeNewTransactions")) {
                                calls.add("resume");
                            }
                            return null;
                        });
        StorageManager storage =
                proxy(
                        StorageManager.class,
                        (method, args) -> {
                            if (method.getName().equals("flush")) {
                                calls.add("flush");
                            }
                            return null;
                        });

        try (Logger logger = new Logger(tempDir.resolve("checkpoint.log"))) {
            CheckpointManager manager =
                    new CheckpointManager(logger, ccm, storage, Duration.ofSeconds(1));

            assertTrue(manager.createCheckpoint());
            assertEquals(List.of("pause", "await", "flush", "resume"), calls);
            assertEquals(
                    List.of(LogType.CHECKPOINT_BEGIN, LogType.CHECKPOINT_END),
                    logger.getLogsSorted().stream().map(record -> record.getLogType()).toList());
            assertTrue(manager.getLastCheckpointLsn() > 0);
        }
    }

    @Test
    void timeoutDoesNotFlushOrWriteCheckpointEndAndResumesAdmission() throws Exception {
        List<String> calls = new ArrayList<>();
        ConcurrencyControl ccm =
                proxy(
                        ConcurrencyControl.class,
                        (method, args) -> {
                            if (method.getName().equals("pauseNewTransactions")) {
                                calls.add("pause");
                            } else if (method.getName().equals("awaitNoActive")) {
                                calls.add("await");
                                return false;
                            } else if (method.getName().equals("resumeNewTransactions")) {
                                calls.add("resume");
                            }
                            return null;
                        });
        StorageManager storage =
                proxy(
                        StorageManager.class,
                        (method, args) -> {
                            if (method.getName().equals("flush")) {
                                calls.add("flush");
                            }
                            return null;
                        });

        try (Logger logger = new Logger(tempDir.resolve("timeout.log"))) {
            CheckpointManager manager = new CheckpointManager(logger, ccm, storage, Duration.ZERO);

            assertFalse(manager.createCheckpoint());
            assertEquals(List.of("pause", "await", "resume"), calls);
            assertTrue(logger.getLogsSorted().isEmpty());
            assertEquals(0, manager.getLastCheckpointLsn());
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (object, method, args) -> {
                            Object result = invocation.invoke(method, args);
                            if (result != null || method.getReturnType() == void.class) {
                                return result;
                            }
                            return defaultValue(method.getReturnType());
                        });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        return null;
    }
}
