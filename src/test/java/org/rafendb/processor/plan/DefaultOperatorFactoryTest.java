package org.rafendb.processor.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.Value;
import org.rafendb.mock.concurrency.MockConcurrencyControl;
import org.rafendb.mock.recovery.MockRecoveryManager;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.operator.write.DMLOperator;
import org.rafendb.processor.plan.command.DeletePlanNode;
import org.rafendb.processor.plan.command.InsertPlanNode;
import org.rafendb.processor.plan.command.UpdatePlanNode;

class DefaultOperatorFactoryTest {
    private final InsertPlanNode node = new InsertPlanNode("employees", List.of(Value.ofInt(7)));

    @Test
    void customScanBindingForwardsContextTableAndNullableAliasWithoutLifecycleCalls() {
        for (int sessionId : new int[] {1, 2}) {
            var context = context(sessionId);
            for (String alias : new String[] {null, "p"}) {
                var workers = new ArrayList<FakeChildOp>();
                var builder =
                        DefaultOperatorFactory.bindScan(
                                context,
                                (receivedContext, table, receivedAlias) -> {
                                    assertSame(context, receivedContext);
                                    assertEquals("prodi", table);
                                    assertEquals(alias, receivedAlias);
                                    var worker = new FakeChildOp(List.of(), List.of());
                                    workers.add(worker);
                                    return worker;
                                });

                var factory =
                        factory(
                                builder,
                                insert -> {
                                    throw new AssertionError("Unexpected INSERT");
                                });
                var first = factory.createScan(new TableScanNode(alias, "prodi"));
                var second = factory.createScan(new TableScanNode(alias, "prodi"));
                assertNotSame(first, second);
                assertEquals(2, workers.size());
                for (var worker : workers) {
                    assertThrows(IllegalStateException.class, worker::next);
                    assertEquals(0, worker.getCloseCount());
                }
            }
        }
    }

    @Test
    void scanBindingRejectsMissingDependenciesInputsAndOperatorResults() {
        var context = context(1);
        assertThrows(
                NullPointerException.class,
                () ->
                        DefaultOperatorFactory.bindScan(
                                null,
                                (ctx, table, alias) -> new FakeChildOp(List.of(), List.of())));
        assertThrows(
                NullPointerException.class, () -> DefaultOperatorFactory.bindScan(context, null));
        var noStorage =
                new ExecutionContext(
                        context.getSContext(),
                        context.getConcurrencyControl(),
                        null,
                        context.getRecoveryManager());
        assertThrows(
                NullPointerException.class,
                () ->
                        DefaultOperatorFactory.bindScan(
                                noStorage,
                                (ctx, table, alias) -> new FakeChildOp(List.of(), List.of())));
        var builder =
                DefaultOperatorFactory.bindScan(
                        context, (ctx, table, alias) -> new FakeChildOp(List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> builder.apply(null));
        var missingWorker = DefaultOperatorFactory.bindScan(context, (ctx, table, alias) -> null);
        assertThrows(
                NullPointerException.class,
                () -> missingWorker.apply(new TableScanNode(null, "prodi")));
    }

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

    @Test
    void updateBindingForwardsAssignmentChildEvaluatorAndSessionWithoutExecuting() {
        var context = context(1);
        var evaluator = new DefaultExpressionEvaluator();
        var column = new Expr.ColumnRef("employees", "salary", 1);
        var value = new Expr.Binary(Expr.Op.ADD, column, new Expr.Literal(Value.ofInt(10)));
        var update = new UpdatePlanNode("employees", column, value, Optional.empty());
        var child = new FakeChildOp(List.of(), List.of());
        int[] creations = {0};
        int[] executions = {0};
        var builder =
                DefaultOperatorFactory.bindUpdate(
                        context,
                        evaluator,
                        (table, target, expression, source, receivedEvaluator, ctx) -> {
                            assertEquals(update.table(), table);
                            assertSame(column, target);
                            assertSame(value, expression);
                            assertSame(child, source);
                            assertSame(evaluator, receivedEvaluator);
                            assertSame(context, ctx);
                            creations[0]++;
                            return () -> {
                                executions[0]++;
                                return 2L;
                            };
                        });
        var factory =
                writeFactory(
                        builder,
                        (delete, source) -> {
                            throw new AssertionError("Unexpected DELETE");
                        });

        var action = factory.createUpdate(update, child);
        assertEquals(1, creations[0]);
        assertEquals(0, executions[0]);
        assertThrows(IllegalStateException.class, child::next);
        assertEquals(0, child.getCloseCount());
        assertNull(context.getSContext().getCurrentTxId());
        assertEquals(new ExecutionPlan.CommandResult(2, "UPDATE"), action.execute());
        assertThrows(IllegalStateException.class, action::execute);
        assertEquals(1, executions[0]);
    }

    @Test
    void deleteBindingForwardsSessionOriginalChildAndTableWithoutExecuting() {
        var context = context(1);
        var delete = new DeletePlanNode("employees", Optional.empty());
        var child = new FakeChildOp(List.of(), List.of());
        int[] creations = {0};
        int[] executions = {0};
        var builder =
                DefaultOperatorFactory.bindDelete(
                        context,
                        (ctx, source, table) -> {
                            assertSame(context, ctx);
                            assertSame(child, source);
                            assertEquals(delete.table(), table);
                            creations[0]++;
                            return () -> {
                                executions[0]++;
                                return 1L;
                            };
                        });
        var factory =
                writeFactory(
                        (update, source) -> {
                            throw new AssertionError("Unexpected UPDATE");
                        },
                        builder);

        var action = factory.createDelete(delete, child);
        assertEquals(1, creations[0]);
        assertEquals(0, executions[0]);
        assertThrows(IllegalStateException.class, child::next);
        assertEquals(0, child.getCloseCount());
        assertNull(context.getSContext().getCurrentTxId());
        assertEquals(new ExecutionPlan.CommandResult(1, "DELETE"), action.execute());
        assertThrows(IllegalStateException.class, action::execute);
        assertEquals(1, executions[0]);
    }

    @Test
    void updateAndDeleteBindingsRejectMissingInputsAndWorkers() {
        var context = context(1);
        var evaluator = new DefaultExpressionEvaluator();
        var update =
                new UpdatePlanNode(
                        "employees",
                        new Expr.ColumnRef(null, "salary", 0),
                        new Expr.Literal(Value.ofInt(10)),
                        Optional.empty());
        var delete = new DeletePlanNode("employees", Optional.empty());
        var child = new FakeChildOp(List.of(), List.of());
        DefaultOperatorFactory.UpdateConstructor updateConstructor =
                (table, column, value, source, eval, ctx) -> () -> 0L;
        DefaultOperatorFactory.DeleteConstructor deleteConstructor =
                (ctx, source, table) -> () -> 0L;

        assertThrows(
                NullPointerException.class,
                () -> DefaultOperatorFactory.bindUpdate(null, evaluator, updateConstructor));
        assertThrows(
                NullPointerException.class,
                () -> DefaultOperatorFactory.bindUpdate(context, null, updateConstructor));
        assertThrows(
                NullPointerException.class,
                () -> DefaultOperatorFactory.bindUpdate(context, evaluator, null));
        assertThrows(
                NullPointerException.class,
                () -> DefaultOperatorFactory.bindDelete(null, deleteConstructor));
        assertThrows(
                NullPointerException.class, () -> DefaultOperatorFactory.bindDelete(context, null));

        var updates = DefaultOperatorFactory.bindUpdate(context, evaluator, updateConstructor);
        var deletes = DefaultOperatorFactory.bindDelete(context, deleteConstructor);
        assertThrows(NullPointerException.class, () -> updates.apply(null, child));
        assertThrows(NullPointerException.class, () -> updates.apply(update, null));
        assertThrows(NullPointerException.class, () -> deletes.apply(null, child));
        assertThrows(NullPointerException.class, () -> deletes.apply(delete, null));
        var noUpdate =
                DefaultOperatorFactory.bindUpdate(
                        context, evaluator, (table, column, value, source, eval, ctx) -> null);
        var noDelete = DefaultOperatorFactory.bindDelete(context, (ctx, source, table) -> null);
        assertThrows(NullPointerException.class, () -> noUpdate.apply(update, child));
        assertThrows(NullPointerException.class, () -> noDelete.apply(delete, child));
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
        return factory(
                scan -> {
                    throw new AssertionError("Unexpected Table Scan");
                },
                DefaultOperatorFactory.bindInsert(context, constructor));
    }

    private static DefaultOperatorFactory factory(
            Function<TableScanNode, Operator> scanBuilder,
            Function<InsertPlanNode, DMLOperator> insertBuilder) {
        return factory(
                scanBuilder,
                insertBuilder,
                (update, child) -> {
                    throw new AssertionError("Unexpected UPDATE");
                },
                (delete, child) -> {
                    throw new AssertionError("Unexpected DELETE");
                });
    }

    private static DefaultOperatorFactory writeFactory(
            BiFunction<UpdatePlanNode, Operator, DMLOperator> updateBuilder,
            BiFunction<DeletePlanNode, Operator, DMLOperator> deleteBuilder) {
        return factory(
                scan -> {
                    throw new AssertionError("Unexpected Table Scan");
                },
                insert -> {
                    throw new AssertionError("Unexpected INSERT");
                },
                updateBuilder,
                deleteBuilder);
    }

    private static DefaultOperatorFactory factory(
            Function<TableScanNode, Operator> scanBuilder,
            Function<InsertPlanNode, DMLOperator> insertBuilder,
            BiFunction<UpdatePlanNode, Operator, DMLOperator> updateBuilder,
            BiFunction<DeletePlanNode, Operator, DMLOperator> deleteBuilder) {
        return new DefaultOperatorFactory(
                scanBuilder,
                scan -> {
                    throw new AssertionError("Unexpected Index Scan");
                },
                insertBuilder,
                updateBuilder,
                deleteBuilder,
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
