package org.rafendb.processor.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.rafendb.api.optimizer.QueryOptimizer;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.UpdateQuery;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.engine.Engine;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.processor.plan.command.CreateIndexPlanNode;
import org.rafendb.processor.plan.command.CreateIndexPlanNode.IndexKind;
import org.rafendb.processor.plan.command.DeletePlanNode;
import org.rafendb.processor.plan.command.InsertPlanNode;
import org.rafendb.processor.plan.command.UpdatePlanNode;

/** Statements bound by the real optimizer over the mock SM, then planned. */
class CommandPlannerTest {
    private final QueryOptimizer optimizer = Engine.optimizer(new MockStorageManager());
    private final CommandPlanner planner = new CommandPlanner();

    @Test
    void insertValuesAreEvaluatedInColumnOrder() {
        var sum = planner.plan(bound("INSERT INTO mahasiswa VALUES (2 + 3, 'x', -1.5, 'IF')"));
        var nulls = planner.plan(bound("INSERT INTO mahasiswa VALUES (-7, 'y', NULL, 'IF')"));

        var insert = assertInstanceOf(InsertPlanNode.class, sum);
        assertEquals("mahasiswa", insert.table());
        assertEquals(4, insert.values().size());
        assertEquals(Value.ofInt(5), insert.values().get(0));
        assertEquals(Value.ofFloat(-1.5f), insert.values().get(2));
        assertEquals(Value.ofInt(-7), ((InsertPlanNode) nulls).values().get(0));
        assertEquals(Value.nullOf(DataType.FLOAT), ((InsertPlanNode) nulls).values().get(2));
    }

    @Test
    void updateTargetsTheColumnByNameAndKeepsTheBoundExpressions() {
        var update = (UpdateQuery) bound("UPDATE mahasiswa SET ipk = ipk + 1 WHERE nim = 1");

        assertEquals(
                new UpdatePlanNode(
                        "mahasiswa",
                        new Expr.ColumnRef("mahasiswa", "ipk"),
                        update.value(),
                        Optional.of(update.where())),
                planner.plan(update));
    }

    @Test
    void missingWhereMeansEveryRow() {
        assertEquals(
                new DeletePlanNode("prodi", Optional.empty()),
                planner.plan(bound("DELETE FROM prodi")));
        assertEquals(
                Optional.empty(),
                ((UpdatePlanNode) planner.plan(bound("UPDATE prodi SET nama = 'x'"))).condition());
    }

    @ParameterizedTest
    @CsvSource({"' USING HASH', HASH", "' USING BTREE', BTREE", "'', BTREE"})
    void createIndexMapsTheIndexType(String using, IndexKind kind) {
        assertEquals(
                new CreateIndexPlanNode(
                        "idx", "mahasiswa", new Expr.ColumnRef("mahasiswa", "nama"), kind),
                planner.plan(bound("CREATE INDEX idx ON mahasiswa(nama)" + using)));
    }

    @Test
    void anythingElseIsNotACommand() {
        ParsedQuery select = bound("SELECT * FROM prodi");

        assertFalse(CommandPlanner.handles(select));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(select));
    }

    private ParsedQuery bound(String sql) {
        return optimizer.parse(sql);
    }
}
