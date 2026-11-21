package org.rafendb.optimizer.join;

import static org.junit.jupiter.api.Assertions.*;
import static org.rafendb.common.plan.JoinAlgorithm.BLOCK_NESTED_LOOP;
import static org.rafendb.optimizer.join.JoinAccessPathTestSupport.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.OptimizerInternalException;
import org.rafendb.common.error.OptimizerInternalException.CostEstimationException;
import org.rafendb.common.expr.Expr.*;
import org.rafendb.common.plan.*;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.optimizer.binder.QueryBinder;
import org.rafendb.optimizer.catalog.StorageManagerCatalog;
import org.rafendb.optimizer.cost.Cost;
import org.rafendb.optimizer.cost.StatsCostModel;
import org.rafendb.optimizer.parser.QueryParser;
import org.rafendb.optimizer.plan.InitialPlanBuilder;

class JoinReorderingTest {
    private final MockStorageManager storage = new MockStorageManager();
    private final JoinOptimizer joins = new JoinOptimizer();
    private final PlanValidator validator =
            new PlanValidator(storage, new AccessPathOptimizer(storage));
    // m has four columns and p has two. Predicates bind to their immediate input layout.
    private final ColumnRef studentCode = new ColumnRef("m", "kode_prodi", 3);
    private final ColumnRef programCode = new ColumnRef("p", "kode", 4);
    private final JoinPlanNode theta =
            new JoinPlanNode(
                    BLOCK_NESTED_LOOP,
                    new Binary(Op.LT, studentCode, programCode),
                    STUDENTS,
                    PROGRAMS);

    @Test
    void swapsThetaJoinAndRestoresColumns() throws CostEstimationException {
        var swapped = swappedCandidate(theta, List.of("p", "m"));
        var join = nodes(swapped, JoinPlanNode.class).getFirst();

        var student = new ColumnRef("m", "kode_prodi", 5);
        var program = new ColumnRef("p", "kode", 0);
        var equivalent =
                Set.of(new Binary(Op.LT, student, program), new Binary(Op.GT, program, student));
        assertTrue(
                equivalent.contains(join.condition()),
                "Expected m.kode_prodi < p.kode in p,m layout, got " + join.condition());
        assertEquals(rows(storage, theta), rows(storage, swapped));
    }

    @Test
    void swapsBoundNaturalJoin() throws OptimizerInternalException {
        var catalog = new StorageManagerCatalog(storage);
        var parsed = new QueryParser().parse("SELECT * FROM prodi AS p NATURAL JOIN prodi AS q");
        var bound = (SelectQuery) new QueryBinder(catalog).bind(parsed);
        var original = new InitialPlanBuilder(new StatsCostModel(catalog)).build(bound).root();

        var swapped = swappedCandidate(original, List.of("q", "p"));

        var expected = rows(storage, PROGRAMS);
        assertEquals(expected, rows(storage, original), "NATURAL JOIN * merges shared columns");
        assertEquals(expected, rows(storage, swapped));
    }

    @Test
    void regroupsThreeInputsWithLocalPredicates() throws CostEstimationException {
        var peers = new TableScanNode("n", "mahasiswa");
        var enrolledIn = new Binary(Op.EQ, studentCode, programCode);
        var studentJoin = new JoinPlanNode(BLOCK_NESTED_LOOP, enrolledIn, STUDENTS, PROGRAMS);
        var sameProgram = new Binary(Op.EQ, programCode, new ColumnRef("n", "kode_prodi", 9));
        var original = new JoinPlanNode(BLOCK_NESTED_LOOP, sameProgram, studentJoin, peers);

        JoinPlanNode regrouped = null;
        for (var candidate : joins.generateCandidates(original, storage)) {
            if (candidate instanceof JoinPlanNode outer
                    && outer.left().equals(STUDENTS)
                    && outer.right() instanceof JoinPlanNode inner
                    && inner.children().equals(List.of(PROGRAMS, peers))) {
                regrouped = outer;
                break;
            }
        }
        assertNotNull(regrouped, "Expected m JOIN (p JOIN n)");
        var inner = assertInstanceOf(JoinPlanNode.class, regrouped.right());
        var local =
                new Binary(
                        Op.EQ, new ColumnRef("p", "kode", 0), new ColumnRef("n", "kode_prodi", 5));
        assertEquals(local, inner.condition(), "p,n offsets after removing m from the input");
        assertEquals(
                enrolledIn, regrouped.condition(), "Predicate requiring m stays at the outer join");
        validator.validate(regrouped);
        assertEquals(rows(storage, original), rows(storage, regrouped));
    }

    @Test
    void choosesCheaperJoinOrder() throws CostEstimationException {
        PlanCost costs = root -> new Cost(scanOrder(root).getFirst().equals("p") ? 1 : 8, 0);
        var optimizer = new JoinAccessPathOptimizer(storage, costs);

        var chosen = optimizer.optimize(new QueryPlan(theta, 0));

        assertEquals(List.of("p", "m"), scanOrder(chosen.root()));
        assertEquals(new Cost(1, 0).timeMs(), chosen.totalCost());
        assertEquals(
                BLOCK_NESTED_LOOP, nodes(chosen.root(), JoinPlanNode.class).getFirst().algorithm());
        assertEquals(rows(storage, theta), rows(storage, chosen.root()));
    }

    private PlanNode swappedCandidate(PlanNode original, List<String> expectedOrder)
            throws CostEstimationException {
        for (var candidate : joins.generateCandidates(original, storage)) {
            if (scanOrder(candidate).equals(expectedOrder)) {
                validator.validate(candidate);
                return candidate;
            }
        }
        return fail("Expected swapped candidate " + expectedOrder);
    }
}
