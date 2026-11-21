package org.rafendb.optimizer.join;

import static org.junit.jupiter.api.Assertions.*;
import static org.rafendb.optimizer.join.JoinAccessPathTestSupport.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.rafendb.common.error.OptimizerInternalException.CostEstimationException;
import org.rafendb.common.expr.Expr.*;
import org.rafendb.common.plan.*;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.optimizer.catalog.StorageManagerCatalog;
import org.rafendb.optimizer.cost.Cost;
import org.rafendb.optimizer.cost.StatsCostModel;

class IndexAccessPathTest {
    private final MockStorageManager storage = new MockStorageManager();
    private final AccessPathOptimizer access = new AccessPathOptimizer(storage);
    // The storage fixture has one catalog index per table.
    private final IndexInfo hash = storage.listIndexes(PROGRAMS.table()).getFirst();
    private final IndexInfo tree = storage.listIndexes(STUDENTS.table()).getFirst();
    private final ColumnRef programCode = new ColumnRef("p", "kode", 0);
    private final ColumnRef studentId = new ColumnRef("m", "nim", 0);
    private final Literal ifCode = new Literal(Value.ofText(DataType.CHAR, "IF"));
    private final Literal id = new Literal(Value.ofInt(13524275));
    private final Binary inInformatics =
            new Binary(Op.EQ, new ColumnRef("m", "kode_prodi", 3), ifCode);

    @Test
    void offersHashEqualityLookup() {
        assertEquals(IndexType.HASH, hash.type());
        assertLookupCandidates(PROGRAMS, hash, new Binary(Op.EQ, programCode, ifCode));
    }

    @ParameterizedTest
    @EnumSource(
            value = Op.class,
            names = {"GT", "GE", "LT", "LE"})
    void rejectsHashRangeLookup(Op comparison) {
        var filter = new SelectionPlanNode(new Binary(comparison, programCode, ifCode), PROGRAMS);

        assertEquals(
                List.of(filter),
                access.generateCandidates(filter),
                "Hash index cannot answer " + comparison);
    }

    @ParameterizedTest
    @EnumSource(
            value = Op.class,
            names = {"EQ", "GT", "GE", "LT", "LE"})
    void offersTreeLookup(Op comparison) {
        assertEquals(IndexType.B_PLUS_TREE, tree.type());
        assertLookupCandidates(STUDENTS, tree, new Binary(comparison, studentId, id));
    }

    @ParameterizedTest(name = "index seeks {0}, sequential seeks {1}")
    @CsvSource({"1, 8", "8, 1"})
    void choosesCheaperScanAndKeepsResidual(int indexSeeks, int sequentialSeeks)
            throws CostEstimationException {
        var lookup = new Binary(Op.EQ, studentId, id);
        var sequential = new SelectionPlanNode(new Binary(Op.AND, lookup, inInformatics), STUDENTS);
        var index =
                new IndexScanNode(
                        "m", "mahasiswa", new IndexScanDescriptor(tree.name(), "nim", lookup));
        var filteredIndex = new SelectionPlanNode(inInformatics, index);
        PlanCost costs =
                root ->
                        new Cost(
                                nodes(root, IndexScanNode.class).isEmpty()
                                        ? sequentialSeeks
                                        : indexSeeks,
                                0);

        var candidates = access.generateCandidates(sequential);
        var chosen =
                new JoinAccessPathOptimizer(storage, costs).optimize(new QueryPlan(sequential, 0));

        assertTrue(
                candidates.contains(filteredIndex),
                "Expected nim lookup with residual kode_prodi filter in " + candidates);
        assertEquals(indexSeeks < sequentialSeeks ? filteredIndex : sequential, chosen.root());
        assertEquals(
                new Cost(Math.min(indexSeeks, sequentialSeeks), 0).timeMs(), chosen.totalCost());
    }

    @Test
    void fallsBackToSequentialScan() throws CostEstimationException {
        var filter = new SelectionPlanNode(inInformatics, STUDENTS);
        var costs = new StatsCostModel(new StorageManagerCatalog(storage));

        var candidates = access.generateCandidates(filter);
        var chosen = new JoinAccessPathOptimizer(storage, costs).optimize(new QueryPlan(filter, 0));

        assertEquals(List.of(filter), candidates, "No index on mahasiswa.kode_prodi");
        assertEquals(filter, chosen.root());
    }

    private void assertLookupCandidates(TableScanNode scan, IndexInfo index, Binary lookup) {
        var sequential = new SelectionPlanNode(lookup, scan);
        var descriptor = new IndexScanDescriptor(index.name(), index.column(), lookup);
        var indexed = new IndexScanNode(scan.alias(), scan.table(), descriptor);

        assertEquals(
                Set.of(sequential, indexed),
                Set.copyOf(access.generateCandidates(sequential)),
                "Catalog alternatives for " + lookup);
    }
}
