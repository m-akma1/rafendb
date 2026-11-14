package org.rafendb.processor.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.Value;
import org.rafendb.mock.concurrency.MockConcurrencyControl;
import org.rafendb.mock.recovery.MockRecoveryManager;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.operator.write.DMLOperator;
import org.rafendb.processor.plan.command.InsertPlanNode;

class DefaultOperatorFactoryTest {
    private final InsertPlanNode node = new InsertPlanNode("employees", List.of(Value.ofInt(7)));

    @Test
    void bindsConstructorTableValuesAndContextWithoutExecutingInsert() {
        var context = context(1);
        var builder = DefaultOperatorFactory.bindInsert(context, RecordingInsert::new);

        var first = assertInstanceOf(RecordingInsert.class, builder.apply(node));
        var second = assertInstanceOf(RecordingInsert.class, builder.apply(node));

        assertEquals(node.table(), first.table);
        assertSame(node.values(), first.values);
        assertSame(context, first.context);
        assertEquals(0, first.executions);
        assertEquals(0, second.executions);
        assertNotSame(first, second);
        assertNull(context.getSContext().getCurrentTxId());
    }

    @Test
    void translatorDefersInsertAndUsesTransactionAssignedAfterPreparation() {
        var context = context(1);
        var workers = new ArrayList<RecordingInsert>();
        var factory =
                factory(
                        context,
                        (table, values, ctx) -> {
                            var worker = new RecordingInsert(table, values, ctx);
                            workers.add(worker);
                            return worker;
                        });
        var translator = new DefaultPlanTranslator(factory);
        var first =
                assertInstanceOf(
                        ExecutionPlan.Command.class, translator.translate(new QueryPlan(node, 0)));
        var second =
                assertInstanceOf(
                        ExecutionPlan.Command.class, translator.translate(new QueryPlan(node, 0)));

        assertEquals(2, workers.size());
        assertEquals(0, workers.get(0).executions);
        assertEquals(0, workers.get(1).executions);
        assertNull(context.getSContext().getCurrentTxId());

        var firstTx = new TxId(11);
        context.getSContext().setCurrentTxId(firstTx);
        assertEquals(new ExecutionPlan.CommandResult(1, "INSERT"), first.action().execute());
        assertEquals(firstTx, workers.get(0).executedTx);
        assertThrows(IllegalStateException.class, first.action()::execute);
        assertEquals(1, workers.get(0).executions);

        var secondTx = new TxId(12);
        context.getSContext().setCurrentTxId(secondTx);
        assertEquals(1, second.action().execute().affectedRows());
        assertEquals(secondTx, workers.get(1).executedTx);
    }

    @Test
    void failedInsertIsNotRetriedAndDoesNotStartATransaction() {
        var context = context(1);
        var workers = new ArrayList<RecordingInsert>();
        var factory =
                factory(
                        context,
                        (table, values, ctx) -> {
                            var worker = new RecordingInsert(table, values, ctx);
                            workers.add(worker);
                            return worker;
                        });
        var action = factory.createInsert(node);

        var error = assertThrows(IllegalStateException.class, action::execute);
        assertEquals("No active transaction", error.getMessage());
        assertNull(context.getSContext().getCurrentTxId());
        context.getSContext().setCurrentTxId(new TxId(13));
        assertThrows(IllegalStateException.class, action::execute);
        assertEquals(1, workers.getFirst().executions);
    }

    @Test
    void insertBindingsKeepSessionContextsSeparate() {
        var firstContext = context(1);
        var secondContext = context(2);
        firstContext.getSContext().setCurrentTxId(new TxId(21));
        secondContext.getSContext().setCurrentTxId(new TxId(22));
        var firstBuilder = DefaultOperatorFactory.bindInsert(firstContext, RecordingInsert::new);
        var secondBuilder = DefaultOperatorFactory.bindInsert(secondContext, RecordingInsert::new);

        var first = assertInstanceOf(RecordingInsert.class, firstBuilder.apply(node));
        var second = assertInstanceOf(RecordingInsert.class, secondBuilder.apply(node));
        first.execute();
        second.execute();

        assertSame(firstContext, first.context);
        assertSame(secondContext, second.context);
        assertEquals(new TxId(21), first.executedTx);
        assertEquals(new TxId(22), second.executedTx);
    }

    @Test
    void rejectsMissingInsertBindingInputsAndMissingWorker() {
        var context = context(1);
        assertThrows(
                NullPointerException.class,
                () -> DefaultOperatorFactory.bindInsert(null, RecordingInsert::new));
        assertThrows(
                NullPointerException.class, () -> DefaultOperatorFactory.bindInsert(context, null));
        var builder = DefaultOperatorFactory.bindInsert(context, RecordingInsert::new);
        assertThrows(NullPointerException.class, () -> builder.apply(null));
        var missingWorker =
                DefaultOperatorFactory.bindInsert(context, (table, values, ctx) -> null);
        assertThrows(NullPointerException.class, () -> missingWorker.apply(node));
    }

    @Test
    void missingOrNegativeDmlCountsFailWithoutAllowingAnotherExecution() {
        for (Long count : new Long[] {null, -1L}) {
            int[] executions = {0};
            var factory =
                    factory(
                            context(1),
                            (table, values, ctx) ->
                                    () -> {
                                        executions[0]++;
                                        return count;
                                    });
            var action = factory.createInsert(node);

            if (count == null) {
                var error = assertThrows(IllegalStateException.class, action::execute);
                assertTrue(error.getMessage().contains("no affected-row count"));
            } else {
                assertThrows(IllegalArgumentException.class, action::execute);
            }
            assertThrows(IllegalStateException.class, action::execute);
            assertEquals(1, executions[0]);
        }
    }

    private static ExecutionContext context(int sessionId) {
        return new ExecutionContext(
                new SessionContext(sessionId),
                new MockConcurrencyControl(),
                new MockStorageManager(),
                new MockRecoveryManager());
    }

    private static DefaultOperatorFactory factory(
            ExecutionContext context, DefaultOperatorFactory.InsertConstructor constructor) {
        return new DefaultOperatorFactory(
                scan -> {
                    throw new AssertionError("Unexpected Table Scan");
                },
                scan -> {
                    throw new AssertionError("Unexpected Index Scan");
                },
                DefaultOperatorFactory.bindInsert(context, constructor),
                (update, child) -> {
                    throw new AssertionError("Unexpected UPDATE");
                },
                (delete, child) -> {
                    throw new AssertionError("Unexpected DELETE");
                },
                index -> {
                    throw new AssertionError("Unexpected CREATE INDEX");
                });
    }

    private static final class RecordingInsert implements DMLOperator {
        private final String table;
        private final List<Value> values;
        private final ExecutionContext context;
        private int executions;
        private TxId executedTx;

        private RecordingInsert(String table, List<Value> values, ExecutionContext context) {
            this.table = table;
            this.values = values;
            this.context = context;
        }

        @Override
        public Long execute() {
            executions++;
            executedTx = context.getSContext().getCurrentTxId();
            if (executedTx == null) throw new IllegalStateException("No active transaction");
            return 1L;
        }
    }
}
