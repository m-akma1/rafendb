package org.rafendb.processor.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.CartesianPlanNode;
import org.rafendb.common.plan.IndexScanDescriptor;
import org.rafendb.common.plan.IndexScanNode;
import org.rafendb.common.plan.JoinAlgorithm;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.ProjectionPlanNode;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.plan.SelectionPlanNode;
import org.rafendb.common.plan.SortPlanNode;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.plan.command.CreateIndexPlanNode;
import org.rafendb.processor.plan.command.DeletePlanNode;
import org.rafendb.processor.plan.command.InsertPlanNode;
import org.rafendb.processor.plan.command.TransactionPlanNode;
import org.rafendb.processor.plan.command.UpdatePlanNode;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

class DefaultPlanTranslatorTest {
    private static final Expr.ColumnRef ID = new Expr.ColumnRef("e", "id", 0);
    private static final Expr.ColumnRef SALARY = new Expr.ColumnRef("e", "salary", 1);
    private static final List<ColumnSchema> EMPLOYEE_SCHEMA =
            List.of(
                    new ColumnSchema(ID, DataType.INTEGER),
                    new ColumnSchema(SALARY, DataType.FLOAT));
    private static final List<ColumnSchema> DEPARTMENT_SCHEMA =
            List.of(new ColumnSchema(new Expr.ColumnRef("d", "id", 0), DataType.INTEGER));
    private static final Expr CONDITION =
            new Expr.Binary(Expr.Op.GT, SALARY, new Expr.Literal(Value.ofInt(5000)));

    private final RecordingFactory factory = new RecordingFactory();
    private final DefaultPlanTranslator translator = new DefaultPlanTranslator(factory);

    private static TableScanNode employees() {
        return new TableScanNode("e", "employees");
    }

    private static TableScanNode departments() {
        return new TableScanNode("d", "departments");
    }

    private ExecutionPlan.Rows translate(PlanNode root) {
        return assertInstanceOf(
                ExecutionPlan.Rows.class, translator.translate(new QueryPlan(root, 12.5)));
    }

    private static RecordedOperator recorded(Operator operator) {
        return assertInstanceOf(RecordedOperator.class, operator);
    }

    @Test
    void forwardsScanNodeAndReturnsItsSchemaWithoutExecutingIt() {
        for (String alias : new String[] {null, "e"}) {
            var node = new TableScanNode(alias, "employees");
            var result = translate(node);
            var scan = recorded(result.operator());
            assertEquals("scan", scan.kind);
            assertSame(node, scan.parameter);
            assertTrue(scan.children.isEmpty());
            assertEquals(EMPLOYEE_SCHEMA, result.columns());
        }
    }

    @Test
    void forwardsIndexNameAliasAndLookupConditionUnchanged() {
        var lookup = new Expr.Binary(Expr.Op.EQ, ID, new Expr.Literal(Value.ofInt(7)));
        var descriptor = new IndexScanDescriptor("employees_pk", "id", lookup);
        var node = new IndexScanNode("e", "employees", descriptor);
        var scan = recorded(translate(node).operator());
        assertEquals("index", scan.kind);
        assertSame(node, scan.parameter);
        assertSame(descriptor, ((IndexScanNode) scan.parameter).descriptor());
        assertSame(lookup, ((IndexScanNode) scan.parameter).descriptor().condition());
        assertEquals(EMPLOYEE_SCHEMA, scan.schema());
        assertEquals(List.of("index"), factory.kinds());
    }

    @Test
    void buildsScanFilterProjectBottomUpAndPreservesProjectionOrder() {
        List<Expr> expressions = List.of(SALARY, ID);
        factory.projectionSchema = List.of(EMPLOYEE_SCHEMA.get(1), EMPLOYEE_SCHEMA.get(0));
        var plan =
                new ProjectionPlanNode(expressions, new SelectionPlanNode(CONDITION, employees()));
        var result = translate(plan);
        var project = recorded(result.operator());
        var filter = recorded(project.children.get(0));
        var scan = recorded(filter.children.get(0));
        assertEquals(List.of("scan", "filter", "project"), factory.kinds());
        assertEquals(expressions, project.parameter);
        assertSame(plan.columns(), project.parameter);
        assertSame(CONDITION, filter.parameter);
        assertSame(factory.created.get(0), scan);
        assertEquals(factory.projectionSchema, result.columns());
        assertNotEquals(EMPLOYEE_SCHEMA, result.columns());
    }

    @Test
    void keepsJoinOuterInnerConditionAndNestedChildStructure() {
        var condition = new Expr.Binary(Expr.Op.EQ, ID, new Expr.ColumnRef("d", "id", 2));
        var outer = new SelectionPlanNode(CONDITION, employees());
        var plan =
                new JoinPlanNode(JoinAlgorithm.BLOCK_NESTED_LOOP, condition, outer, departments());
        var result = translate(plan);
        var join = recorded(result.operator());
        var filter = recorded(join.children.get(0));
        var inner = recorded(join.children.get(1));
        assertEquals(List.of("scan", "filter", "scan", "join"), factory.kinds());
        assertEquals("filter", filter.kind);
        assertEquals(
                "employees", ((TableScanNode) recorded(filter.children.get(0)).parameter).table());
        assertEquals("departments", ((TableScanNode) inner.parameter).table());
        assertSame(condition, join.parameter);
        assertEquals(
                List.of(EMPLOYEE_SCHEMA.get(0), EMPLOYEE_SCHEMA.get(1), DEPARTMENT_SCHEMA.get(0)),
                result.columns());
    }

    @Test
    void mapsCartesianToJoinWithNullCondition() {
        var result =
                translate(
                        new CartesianPlanNode(
                                JoinAlgorithm.BLOCK_NESTED_LOOP, employees(), departments()));
        var join = recorded(result.operator());
        assertEquals(List.of("scan", "scan", "join"), factory.kinds());
        assertNull(join.parameter);
        assertSame(factory.created.get(0), join.children.get(0));
        assertSame(factory.created.get(1), join.children.get(1));
        assertEquals(3, result.columns().size());
    }

    @Test
    void passesSortOffsetAndBothDirectionsWithoutRebindingNames() {
        for (SortPlanNode.Direction direction : SortPlanNode.Direction.values()) {
            factory.created.clear();
            var plan =
                    new SortPlanNode(
                            new SelectionPlanNode(CONDITION, employees()), SALARY, direction);
            var sort = recorded(translate(plan).operator());
            assertEquals(List.of("scan", "filter", "sort"), factory.kinds());
            assertEquals(new SortKey(1, direction == SortPlanNode.Direction.ASC), sort.parameter);
            assertSame(factory.created.get(1), sort.children.get(0));
            assertEquals(EMPLOYEE_SCHEMA, sort.schema());
        }
    }

    @Test
    void rejectsUnboundAndOutOfRangeSortOffsetsBeforeCreatingSort() {
        for (int offset : List.of(-1, 2)) {
            factory.created.clear();
            var column = new Expr.ColumnRef("e", "salary", offset);
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> translate(new SortPlanNode(employees(), column)));
            assertTrue(error.getMessage().contains("sort column offset"));
            assertEquals(List.of("scan"), factory.kinds());
        }
    }

    @Test
    void rejectsEveryNonBnlAlgorithmBeforeCreatingChildren() {
        for (JoinAlgorithm algorithm : JoinAlgorithm.values()) {
            if (algorithm == JoinAlgorithm.BLOCK_NESTED_LOOP) continue;
            for (PlanNode node :
                    List.<PlanNode>of(
                            new JoinPlanNode(algorithm, CONDITION, employees(), departments()),
                            new CartesianPlanNode(algorithm, employees(), departments()))) {
                var error = assertThrows(IllegalArgumentException.class, () -> translate(node));
                assertTrue(error.getMessage().contains(algorithm.name()));
                assertTrue(factory.created.isEmpty());
            }
        }
    }

    @Test
    void rejectsAmbiguousNaturalJoinInsteadOfTreatingItAsCartesian() {
        var node =
                new JoinPlanNode(JoinAlgorithm.BLOCK_NESTED_LOOP, null, employees(), departments());
        var error = assertThrows(IllegalArgumentException.class, () -> translate(node));
        assertTrue(error.getMessage().contains("NATURAL JOIN"));
        assertTrue(factory.created.isEmpty());
    }

    @Test
    void rejectsUnknownNodesAndUnsupportedTransactionsInsteadOfReturningDummyResults() {
        PlanNode unknown =
                new PlanNode() {
                    @Override
                    public List<PlanNode> children() {
                        return List.of();
                    }

                    @Override
                    public String describe() {
                        return "TABLE SCAN employees";
                    }
                };
        var unknownError = assertThrows(IllegalArgumentException.class, () -> translate(unknown));
        assertTrue(unknownError.getMessage().contains("Unsupported plan node"));
        var transactionError =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> translate(new TransactionPlanNode(TransactionPlanNode.Action.BEGIN)));
        assertTrue(transactionError.getMessage().contains("TransactionPlanNode"));
        assertTrue(factory.created.isEmpty());
    }

    @Test
    void rejectsNullFactoryAndNullInput() {
        assertThrows(NullPointerException.class, () -> new DefaultPlanTranslator(null));
        assertThrows(NullPointerException.class, () -> translator.translate(null));
        assertTrue(factory.created.isEmpty());
    }

    @Test
    void rejectsNullResultsFromEveryFactoryMethod() {
        var plans =
                Map.<String, PlanNode>of(
                        "scan", employees(),
                        "index",
                                new IndexScanNode(
                                        "e",
                                        "employees",
                                        new IndexScanDescriptor("pk", "id", CONDITION)),
                        "filter", new SelectionPlanNode(CONDITION, employees()),
                        "project", new ProjectionPlanNode(List.of(ID), employees()),
                        "join",
                                new JoinPlanNode(
                                        JoinAlgorithm.BLOCK_NESTED_LOOP,
                                        CONDITION,
                                        employees(),
                                        departments()),
                        "sort", new SortPlanNode(employees(), SALARY));
        for (var entry : plans.entrySet()) {
            var broken = new RecordingFactory();
            broken.nullKind = entry.getKey();
            var instance = new DefaultPlanTranslator(broken);
            var error =
                    assertThrows(
                            NullPointerException.class,
                            () -> instance.translate(new QueryPlan(entry.getValue(), 0)),
                            entry.getKey());
            assertEquals("factory result", error.getMessage());
        }
    }

    @Test
    void returnsIndependentOperatorTreesOnRepeatedTranslation() {
        var plan = new SelectionPlanNode(CONDITION, employees());
        var first = translate(plan);
        var second = translate(plan);
        assertNotSame(first, second);
        assertNotSame(first.operator(), second.operator());
        assertNotSame(
                recorded(first.operator()).children.get(0),
                recorded(second.operator()).children.get(0));
        assertEquals(first.columns(), second.columns());
        assertEquals(List.of("scan", "filter", "scan", "filter"), factory.kinds());
    }

    @Test
    void propagatesFactoryFailureWithoutContinuingToParent() {
        var failure = new IllegalStateException("Worker construction failed");
        factory.failureKind = "filter";
        factory.failure = failure;
        var plan =
                new ProjectionPlanNode(List.of(ID), new SelectionPlanNode(CONDITION, employees()));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> translate(plan)));
        assertEquals(List.of("scan"), factory.kinds());
    }

    private ExecutionPlan.Command prepareCommand(PlanNode node) {
        return assertInstanceOf(
                ExecutionPlan.Command.class, translator.translate(new QueryPlan(node, 0)));
    }

    private static UpdatePlanNode update(Optional<Expr> condition) {
        return new UpdatePlanNode(
                "employees",
                new Expr.ColumnRef("employees", "salary", 1),
                new Expr.Binary(Expr.Op.MUL, SALARY, new Expr.Literal(Value.ofFloat(1.05f))),
                condition);
    }

    private static CreateIndexPlanNode index(CreateIndexPlanNode.IndexKind kind) {
        return new CreateIndexPlanNode(
                "salary_idx", "employees", new Expr.ColumnRef("employees", "salary", 1), kind);
    }

    @Test
    void preparesInsertWithoutExecutingAndPreservesTableAndValueOrder() {
        var node = new InsertPlanNode("employees", List.of(Value.ofInt(7), Value.ofFloat(6000)));
        var command = prepareCommand(node);
        var request = factory.commands.getFirst();
        assertEquals("insert", request.kind());
        assertSame(node, request.node());
        assertNull(request.child());
        assertEquals(
                List.of(Value.ofInt(7), Value.ofFloat(6000)),
                ((InsertPlanNode) request.node()).values());
        assertTrue(factory.created.isEmpty());
        assertEquals(0, factory.executions);
        assertSame(factory.commandResult, command.action().execute());
        assertEquals(1, factory.executions);
    }

    @Test
    void preparesUpdateWithFilteredFullRowInputAndUnchangedAssignment() {
        var node = update(Optional.of(CONDITION));
        var command = prepareCommand(node);
        var request = factory.commands.getFirst();
        var filter = recorded(request.child());
        assertEquals("update", request.kind());
        assertSame(node, request.node());
        assertSame(node.value(), ((UpdatePlanNode) request.node()).value());
        assertEquals(List.of("scan", "filter"), factory.kinds());
        assertSame(CONDITION, filter.parameter);
        var scan = (TableScanNode) recorded(filter.children.getFirst()).parameter;
        assertEquals("employees", scan.table());
        assertNull(scan.alias());
        assertEquals(EMPLOYEE_SCHEMA, filter.schema());
        assertEquals(0, factory.executions);
        assertEquals(3, command.action().execute().affectedRows());
    }

    @Test
    void preparesDeleteWithFilterAndDefersWorkerExecution() {
        var node = new DeletePlanNode("employees", Optional.of(CONDITION));
        var command = prepareCommand(node);
        var request = factory.commands.getFirst();
        assertEquals("delete", request.kind());
        assertSame(node, request.node());
        var filter = recorded(request.child());
        assertSame(CONDITION, filter.parameter);
        assertEquals(EMPLOYEE_SCHEMA, filter.schema());
        assertEquals(List.of("scan", "filter"), factory.kinds());
        assertEquals(0, factory.executions);
        assertEquals(3, command.action().execute().affectedRows());
    }

    @Test
    void updatesAndDeletesWithoutWhereUseOnlyScan() {
        for (PlanNode node :
                List.of(
                        update(Optional.empty()),
                        new DeletePlanNode("employees", Optional.empty()))) {
            factory.created.clear();
            factory.commands.clear();
            prepareCommand(node);
            assertEquals(List.of("scan"), factory.kinds());
            assertSame(factory.created.getFirst(), factory.commands.getFirst().child());
            assertEquals(EMPLOYEE_SCHEMA, factory.commands.getFirst().child().schema());
        }
        assertEquals(0, factory.executions);
    }

    @Test
    void preparesBothIndexKindsWithoutScanOrEarlyExecution() {
        factory.commandResult = new ExecutionPlan.CommandResult(0, "Index created");
        for (var kind : CreateIndexPlanNode.IndexKind.values()) {
            factory.commands.clear();
            var node = index(kind);
            int before = factory.executions;
            var command = prepareCommand(node);
            var request = factory.commands.getFirst();
            assertEquals("createIndex", request.kind());
            assertSame(node, request.node());
            assertEquals(kind, ((CreateIndexPlanNode) request.node()).kind());
            assertNull(request.child());
            assertTrue(factory.created.isEmpty());
            assertEquals(before, factory.executions);
            var result = command.action().execute();
            assertEquals(0, result.affectedRows());
            assertEquals("Index created", result.status());
        }
    }

    @Test
    void executesEveryCommandAtMostOnceAndKeepsPreparationsIndependent() {
        for (PlanNode node :
                List.of(
                        new InsertPlanNode("employees", List.of(Value.ofInt(7))),
                        update(Optional.empty()),
                        new DeletePlanNode("employees", Optional.empty()),
                        index(CreateIndexPlanNode.IndexKind.BTREE))) {
            var first = prepareCommand(node);
            var second = prepareCommand(node);
            assertNotSame(first.action(), second.action());
            int before = factory.executions;
            first.action().execute();
            assertThrows(IllegalStateException.class, () -> first.action().execute());
            assertEquals(before + 1, factory.executions);
            second.action().execute();
            assertEquals(before + 2, factory.executions);
        }
    }

    @Test
    void propagatesExecutionFailureAndDoesNotRetryCommand() {
        var failure = new IllegalStateException("Write aborted");
        factory.executionFailure = failure;
        var command = prepareCommand(new DeletePlanNode("employees", Optional.empty()));
        assertEquals(0, factory.executions);
        assertSame(
                failure,
                assertThrows(IllegalStateException.class, () -> command.action().execute()));
        assertThrows(IllegalStateException.class, () -> command.action().execute());
        assertEquals(1, factory.executions);
    }

    @Test
    void rejectsNullCommandResultsWithoutRepeatingExecution() {
        factory.commandResult = null;
        var command = prepareCommand(index(CreateIndexPlanNode.IndexKind.BTREE));
        var error = assertThrows(NullPointerException.class, () -> command.action().execute());
        assertEquals("command result", error.getMessage());
        assertThrows(IllegalStateException.class, () -> command.action().execute());
        assertEquals(1, factory.executions);
    }

    @Test
    void rejectsNullActionsFromAllCommandFactoryMethods() {
        var plans =
                Map.<String, PlanNode>of(
                        "insert",
                        new InsertPlanNode("employees", List.of()),
                        "update",
                        update(Optional.empty()),
                        "delete",
                        new DeletePlanNode("employees", Optional.empty()),
                        "createIndex",
                        index(CreateIndexPlanNode.IndexKind.HASH));
        for (var entry : plans.entrySet()) {
            factory.nullKind = entry.getKey();
            var error =
                    assertThrows(
                            NullPointerException.class, () -> prepareCommand(entry.getValue()));
            assertEquals("factory command result", error.getMessage());
        }
        assertEquals(0, factory.executions);
    }

    @Test
    void rejectsCommandNodesNestedInsideReadPipelines() {
        var nested =
                new SelectionPlanNode(CONDITION, new DeletePlanNode("employees", Optional.empty()));
        var error = assertThrows(IllegalArgumentException.class, () -> translate(nested));
        assertTrue(error.getMessage().contains("DeletePlanNode"));
        assertTrue(factory.created.isEmpty());
        assertTrue(factory.commands.isEmpty());
        assertEquals(0, factory.executions);
    }

    private record SortKey(int offset, boolean ascending) {}

    private record CommandRequest(String kind, PlanNode node, Operator child) {}

    /** Mencatat permintaan translator tanpa menjalankan algoritma operator. */
    private static final class RecordingFactory implements OperatorFactory {
        private final List<RecordedOperator> created = new ArrayList<>();
        private final Map<String, List<ColumnSchema>> schemas =
                Map.of("employees", EMPLOYEE_SCHEMA, "departments", DEPARTMENT_SCHEMA);
        private List<ColumnSchema> projectionSchema = List.of();
        private String nullKind;
        private String failureKind;
        private RuntimeException failure;
        private final List<CommandRequest> commands = new ArrayList<>();
        private int executions;
        private RuntimeException executionFailure;
        private ExecutionPlan.CommandResult commandResult =
                new ExecutionPlan.CommandResult(3, "DML completed");

        private ExecutionPlan.CommandAction command(String kind, PlanNode node, Operator child) {
            if (kind.equals(nullKind)) return null;
            commands.add(new CommandRequest(kind, node, child));
            return () -> {
                executions++;
                if (executionFailure != null) throw executionFailure;
                return commandResult;
            };
        }

        private List<String> kinds() {
            return created.stream().map(operator -> operator.kind).toList();
        }

        private Operator record(
                String kind, List<Operator> children, Object parameter, List<ColumnSchema> schema) {
            if (kind.equals(failureKind)) throw failure;
            if (kind.equals(nullKind)) return null;
            var operator = new RecordedOperator(kind, children, parameter, schema);
            created.add(operator);
            return operator;
        }

        @Override
        public Operator createScan(TableScanNode node) {
            return record("scan", List.of(), node, schemas.get(node.table()));
        }

        @Override
        public Operator createIndexScan(IndexScanNode node) {
            return record("index", List.of(), node, schemas.get(node.table()));
        }

        @Override
        public Operator createFilter(Operator child, Expr condition) {
            return record("filter", List.of(child), condition, child.schema());
        }

        @Override
        public Operator createProject(Operator child, List<Expr> expressions) {
            return record("project", List.of(child), expressions, projectionSchema);
        }

        @Override
        public Operator createJoin(Operator outer, Operator inner, Expr condition) {
            var schema = new ArrayList<>(outer.schema());
            schema.addAll(inner.schema());
            return record("join", List.of(outer, inner), condition, schema);
        }

        @Override
        public Operator createSort(Operator child, int keyIndex, boolean ascending) {
            return record("sort", List.of(child), new SortKey(keyIndex, ascending), child.schema());
        }

        @Override
        public ExecutionPlan.CommandAction createInsert(InsertPlanNode node) {
            return command("insert", node, null);
        }

        @Override
        public ExecutionPlan.CommandAction createUpdate(UpdatePlanNode node, Operator child) {
            return command("update", node, child);
        }

        @Override
        public ExecutionPlan.CommandAction createDelete(DeletePlanNode node, Operator child) {
            return command("delete", node, child);
        }

        @Override
        public ExecutionPlan.CommandAction createIndex(CreateIndexPlanNode node) {
            return command("createIndex", node, null);
        }
    }

    /** Schema tersedia, tetapi setiap upaya menjalankan lifecycle membuat tes gagal. */
    private static final class RecordedOperator implements Operator {
        private final String kind;
        private final List<Operator> children;
        private final Object parameter;
        private final List<ColumnSchema> schema;

        private RecordedOperator(
                String kind, List<Operator> children, Object parameter, List<ColumnSchema> schema) {
            this.kind = kind;
            this.children = List.copyOf(children);
            this.parameter = parameter;
            this.schema = List.copyOf(schema);
        }

        @Override
        public List<ColumnSchema> schema() {
            return schema;
        }

        @Override
        public void open() {
            throw new AssertionError("Translator must not open operators");
        }

        @Override
        public ResultRow next() {
            throw new AssertionError("Translator must not read rows");
        }

        @Override
        public void close() {
            throw new AssertionError("Translator must not close operators during preparation");
        }
    }
}
