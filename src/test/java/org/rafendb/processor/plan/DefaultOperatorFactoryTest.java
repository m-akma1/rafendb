package org.rafendb.processor.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.IndexScanDescriptor;
import org.rafendb.common.plan.IndexScanNode;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.common.query.FromItem;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.api.SessionView;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.operator.FakeCcm;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.operator.FakeSession;
import org.rafendb.processor.operator.FakeStorage;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.operator.write.DMLOperator;
import org.rafendb.processor.plan.command.CreateIndexPlanNode;
import org.rafendb.processor.plan.command.CreateTablePlanNode;
import org.rafendb.processor.plan.command.DeletePlanNode;
import org.rafendb.processor.plan.command.DropTablePlanNode;
import org.rafendb.processor.plan.command.InsertPlanNode;
import org.rafendb.processor.plan.command.UpdatePlanNode;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/**
 * The bind* helpers wire a real operator constructor to one session's context; building an
 * operator or action must never run it. Running a command at most once is the translator's job
 * (DefaultPlanTranslatorTest).
 */
class DefaultOperatorFactoryTest {
    private static final InsertPlanNode INSERT =
            new InsertPlanNode("employees", List.of(Value.ofInt(7)));
    private static final CreateIndexPlanNode INDEX =
            new CreateIndexPlanNode(
                    "idx_salary",
                    "employees",
                    new Expr.ColumnRef(null, "salary"),
                    CreateIndexPlanNode.IndexKind.BTREE);

    private final FakeSession context =
            new FakeSession(
                    new FakeCcm(),
                    new FakeStorage(
                            new TableSchema(
                                    "employees",
                                    List.of(new Column("id", DataType.INTEGER, 0, false, true))),
                            List.of()));

    @Test
    void scanBindingForwardsContextTableAndAliasWithoutOpening() {
        for (String alias : new String[] {null, "p"}) {
            var built = new ArrayList<FakeChildOp>();
            var scans =
                    DefaultOperatorFactory.bindScan(
                            context,
                            (ctx, table, receivedAlias) -> {
                                assertSame(context, ctx);
                                assertEquals("prodi", table);
                                assertEquals(alias, receivedAlias);
                                built.add(new FakeChildOp(List.of(), List.of()));
                                return built.getLast();
                            });

            assertNotSame(
                    scans.apply(new TableScanNode(alias, "prodi")),
                    scans.apply(new TableScanNode(alias, "prodi")));
            assertEquals(List.of(0, 0), built.stream().map(FakeChildOp::getOpenCount).toList());
        }
    }

    @Test
    void indexScanBindingForwardsAliasAndDescriptorWithoutOpening() {
        var condition =
                new Expr.Binary(
                        Expr.Op.GE,
                        new Expr.ColumnRef("m", "nim", 0),
                        new Expr.Literal(Value.ofInt(7)));
        var descriptor = new IndexScanDescriptor("idx_nim", "nim", condition);
        var operator = new FakeChildOp(List.of(), List.of());
        var scans =
                DefaultOperatorFactory.bindIndexScan(
                        context,
                        (ctx, table, alias, scan) -> {
                            assertSame(context, ctx);
                            assertEquals("mahasiswa", table);
                            assertEquals("m", alias);
                            assertSame(descriptor, scan);
                            return operator;
                        });

        assertSame(operator, scans.apply(new IndexScanNode("m", "mahasiswa", descriptor)));
        assertEquals(0, operator.getOpenCount());
    }

    @Test
    void insertIsDeferredAndSeesTheTransactionActiveWhenItRuns() {
        var built = new ArrayList<RecordingInsert>();
        var factory =
                insertOnly(
                        DefaultOperatorFactory.bindInsert(
                                context,
                                (table, values, ctx) -> {
                                    assertEquals(INSERT.table(), table);
                                    assertSame(INSERT.values(), values);
                                    built.add(new RecordingInsert(ctx));
                                    return built.getLast();
                                }));
        var translator = new DefaultPlanTranslator(factory);
        var first = (ExecutionPlan.Command) translator.translate(new QueryPlan(INSERT, 0));
        var second = (ExecutionPlan.Command) translator.translate(new QueryPlan(INSERT, 0));
        assertEquals(2, built.size());
        assertNull(built.get(0).executedTx, "preparing must not run the insert");

        context.setCurrentTx(new TxId(11));
        assertEquals(1, first.action().execute().affectedRows());
        context.setCurrentTx(new TxId(12));
        second.action().execute();

        assertEquals(new TxId(11), built.get(0).executedTx);
        assertEquals(new TxId(12), built.get(1).executedTx);
    }

    @Test
    void missingOrNegativeDmlCountsFail() {
        assertThrows(
                IllegalStateException.class,
                insertOnly(node -> () -> null).createInsert(INSERT)::execute);
        assertThrows(
                IllegalArgumentException.class,
                insertOnly(node -> () -> -1L).createInsert(INSERT)::execute);
    }

    @Test
    void updateBindingForwardsAssignmentChildAndEvaluatorWithoutRunning() {
        var evaluator = new DefaultExpressionEvaluator();
        var column = new Expr.ColumnRef("employees", "salary", 1);
        var value = new Expr.Binary(Expr.Op.ADD, column, new Expr.Literal(Value.ofInt(10)));
        var update = new UpdatePlanNode("employees", column, value, Optional.empty());
        var child = new FakeChildOp(List.of(), List.of());
        int[] runs = {0};
        var updates =
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
                            return () -> {
                                runs[0]++;
                                return 2L;
                            };
                        });

        var action = writeOnly(updates, unexpected2("DELETE")).createUpdate(update, child);
        assertEquals(0, runs[0]);
        assertEquals(0, child.getOpenCount());

        assertEquals(2, action.execute().affectedRows());
    }

    @Test
    void deleteBindingForwardsChildAndTableWithoutRunning() {
        var delete = new DeletePlanNode("employees", Optional.empty());
        var child = new FakeChildOp(List.of(), List.of());
        int[] runs = {0};
        var deletes =
                DefaultOperatorFactory.bindDelete(
                        context,
                        (ctx, source, table) -> {
                            assertSame(context, ctx);
                            assertSame(child, source);
                            assertEquals(delete.table(), table);
                            return () -> {
                                runs[0]++;
                                return 1L;
                            };
                        });

        var action = writeOnly(unexpected2("UPDATE"), deletes).createDelete(delete, child);
        assertEquals(0, runs[0]);
        assertEquals(0, child.getOpenCount());

        assertEquals(1, action.execute().affectedRows());
    }

    @Test
    void createIndexBindingForwardsTheNodeAndRunsWithTheLiveTransaction() {
        int[] runs = {0};
        var indexes =
                DefaultOperatorFactory.bindCreateIndex(
                        context,
                        (ctx, node) -> {
                            assertSame(context, ctx);
                            assertSame(INDEX, node);
                            return () -> {
                                assertEquals(new TxId(31), ctx.currentTx());
                                runs[0]++;
                                return new ExecutionPlan.CommandResult(0, "created");
                            };
                        });

        var action = indexOnly(indexes).createIndex(INDEX);
        assertEquals(0, runs[0]);
        context.setCurrentTx(new TxId(31));

        assertEquals(0, action.execute().affectedRows());
        assertEquals(1, runs[0]);
    }

    @Test
    void createIndexRejectsMissingOrNonZeroResultsAndPassesFailuresThrough() {
        var failure = new IllegalStateException("index build failed");
        assertThrows(
                NullPointerException.class,
                indexOnly(node -> () -> null).createIndex(INDEX)::execute);
        assertThrows(
                IllegalStateException.class,
                indexOnly(node -> () -> new ExecutionPlan.CommandResult(1, "created"))
                                .createIndex(INDEX)
                        ::execute);
        ExecutionPlan.CommandAction failing =
                () -> {
                    throw failure;
                };
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        indexOnly(node -> failing).createIndex(INDEX)::execute));
    }

    @Test
    void filterUsesSubqueryRunner() {
        List<ColumnSchema> schema =
                List.of(new ColumnSchema(new Expr.ColumnRef("employees", "id"), DataType.INTEGER));
        List<ColumnSchema> managerSchema =
                List.of(new ColumnSchema(new Expr.ColumnRef("managers", "id"), DataType.INTEGER));
        ResultRow one = new ResultRow(List.of(Value.ofInt(1)), null);
        ResultRow two = new ResultRow(List.of(Value.ofInt(2)), null);
        SelectQuery managers =
                new SelectQuery(
                        "SELECT id FROM managers",
                        List.of(new Expr.ColumnRef("managers", "id", 0)),
                        new FromItem.TableRef("managers"),
                        null,
                        List.of());
        // employees.id IN (SELECT id FROM managers)
        Expr condition = new Expr.InSubquery(new Expr.ColumnRef("employees", "id", 0), managers);
        DefaultOperatorFactory factory = writeOnly(unexpected2("UPDATE"), unexpected2("DELETE"));
        FakeChildOp result = new FakeChildOp(managerSchema, List.of(two));
        factory.setSubqueryRunner(query -> new ExecutionPlan.Rows(managerSchema, result));
        FakeChildOp child = new FakeChildOp(schema, List.of(one, two));
        Operator filter = factory.createFilter(child, condition);
        filter.open();
        assertSame(two, filter.next());
        assertNull(filter.next());
        filter.close();
    }

    @Test
    void createAndDropTableBindingsForwardContextAndNodeAndDeferExecution() {
        TableSchema schema =
                new TableSchema(
                        "new_table", List.of(new Column("id", DataType.INTEGER, 0, false, true)));
        var createNode = new CreateTablePlanNode(schema);
        var dropNode = new DropTablePlanNode("new_table", true);
        int[] executions = {0};
        var creates =
                DefaultOperatorFactory.bindCreateTable(
                        context,
                        (ctx, node) -> {
                            assertSame(context, ctx);
                            assertSame(createNode, node);
                            return () -> {
                                executions[0]++;
                                return new ExecutionPlan.CommandResult(0, "table created");
                            };
                        });
        var drops =
                DefaultOperatorFactory.bindDropTable(
                        context,
                        (ctx, node) -> {
                            assertSame(context, ctx);
                            assertSame(dropNode, node);
                            return () -> {
                                executions[0]++;
                                return new ExecutionPlan.CommandResult(0, "table dropped");
                            };
                        });
        var factory = ddlOnly(creates, drops);

        var createAction = factory.createTable(createNode);
        var dropAction = factory.dropTable(dropNode);
        assertEquals(0, executions[0]);
        assertEquals("table created", createAction.execute().status());
        assertEquals("table dropped", dropAction.execute().status());
        assertEquals(2, executions[0]);
    }

    @Test
    void createAndDropTableRejectMissingOrNonZeroResults() {
        assertThrows(
                NullPointerException.class,
                () ->
                        ddlOnly(
                                        node -> null,
                                        node -> () -> new ExecutionPlan.CommandResult(0, "ok"))
                                .createTable(
                                        new CreateTablePlanNode(
                                                context.storageDQL().getSchema("employees"))));
        assertThrows(
                IllegalStateException.class,
                ddlOnly(
                                        node -> () -> new ExecutionPlan.CommandResult(1, "created"),
                                        node -> () -> new ExecutionPlan.CommandResult(0, "dropped"))
                                .createTable(
                                        new CreateTablePlanNode(
                                                context.storageDQL().getSchema("employees")))
                        ::execute);
        assertThrows(
                IllegalStateException.class,
                ddlOnly(
                                        node -> () -> new ExecutionPlan.CommandResult(0, "created"),
                                        node -> () -> new ExecutionPlan.CommandResult(1, "dropped"))
                                .dropTable(new DropTablePlanNode("employees", false))
                        ::execute);
    }

    private static <T, R> Function<T, R> unexpected(String what) {
        return input -> {
            throw new AssertionError("Unexpected " + what);
        };
    }

    private static <T, U, R> BiFunction<T, U, R> unexpected2(String what) {
        return (first, second) -> {
            throw new AssertionError("Unexpected " + what);
        };
    }

    private static DefaultOperatorFactory insertOnly(
            Function<InsertPlanNode, DMLOperator> inserts) {
        return new DefaultOperatorFactory(
                unexpected("scan"),
                unexpected("index scan"),
                inserts,
                unexpected2("UPDATE"),
                unexpected2("DELETE"),
                unexpected("CREATE INDEX"),
                unexpected("CREATE TABLE"),
                unexpected("DROP TABLE"));
    }

    private static DefaultOperatorFactory writeOnly(
            BiFunction<UpdatePlanNode, Operator, DMLOperator> updates,
            BiFunction<DeletePlanNode, Operator, DMLOperator> deletes) {
        return new DefaultOperatorFactory(
                unexpected("scan"),
                unexpected("index scan"),
                unexpected("INSERT"),
                updates,
                deletes,
                unexpected("CREATE INDEX"),
                unexpected("CREATE TABLE"),
                unexpected("DROP TABLE"));
    }

    private static DefaultOperatorFactory indexOnly(
            Function<CreateIndexPlanNode, ExecutionPlan.CommandAction> indexes) {
        return new DefaultOperatorFactory(
                unexpected("scan"),
                unexpected("index scan"),
                unexpected("INSERT"),
                unexpected2("UPDATE"),
                unexpected2("DELETE"),
                indexes,
                unexpected("CREATE TABLE"),
                unexpected("DROP TABLE"));
    }

    private static DefaultOperatorFactory ddlOnly(
            Function<CreateTablePlanNode, ExecutionPlan.CommandAction> creates,
            Function<DropTablePlanNode, ExecutionPlan.CommandAction> drops) {
        return new DefaultOperatorFactory(
                unexpected("scan"),
                unexpected("index scan"),
                unexpected("INSERT"),
                unexpected2("UPDATE"),
                unexpected2("DELETE"),
                unexpected("CREATE INDEX"),
                creates,
                drops);
    }

    /** Records which transaction was active when it ran. */
    private static final class RecordingInsert implements DMLOperator {
        private final SessionView context;
        private TxId executedTx;

        private RecordingInsert(SessionView context) {
            this.context = context;
        }

        @Override
        public Long execute() {
            executedTx = context.currentTx();
            return 1L;
        }
    }
}
