package org.rafendb.processor.plan;

import java.util.Objects;
import org.rafendb.api.processor.ExecutionPlan;
import org.rafendb.api.processor.Operator;
import org.rafendb.api.processor.PlanTranslator;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.CartesianPlanNode;
import org.rafendb.common.plan.CreateIndexPlanNode;
import org.rafendb.common.plan.DeletePlanNode;
import org.rafendb.common.plan.IndexScanNode;
import org.rafendb.common.plan.InsertPlanNode;
import org.rafendb.common.plan.JoinAlgorithm;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.ProjectionPlanNode;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.plan.SelectionPlanNode;
import org.rafendb.common.plan.SortPlanNode;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.common.plan.UpdatePlanNode;

/**
 * Menerjemahkan plan pembacaan QO menjadi pohon operator QP.
 * INSERT, UPDATE, DELETE, dan CREATE INDEX memakai node sementara lokal QP2,
 * yang masih perlu diselaraskan dengan output plan QO saat integrasi.
 * Penerjemahan tidak membuka cursor atau menjalankan operator.
 *
 * <p>Pemanggilan bersamaan memerlukan factory yang aman dipakai
 * bersamaan dan menghasilkan operator baru untuk setiap permintaan.
 */
public class DefaultPlanTranslator implements PlanTranslator {
    private OperatorFactory factory;

    /* Menyimpan factory yang membentuk operator */
    public DefaultPlanTranslator(OperatorFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /* Menyiapkan hasil baris atau aksi perintah tanpa menjalankannya */
    @Override
    public ExecutionPlan translate(QueryPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return switch (plan.root()) {
            case InsertPlanNode insert -> command(factory.createInsert(insert));
            case UpdatePlanNode update -> {
                Operator child = commandInput(update.table(), update.condition().orElse(null));
                yield command(factory.createUpdate(update, child));
            }
            case DeletePlanNode delete -> {
                Operator child = commandInput(delete.table(), delete.condition().orElse(null));
                yield command(factory.createDelete(delete, child));
            }
            case CreateIndexPlanNode index -> command(factory.createIndex(index));
            default -> rows(plan.root());
        };
    }

    /** Membungkus pohon pembacaan tanpa membuka operator. */
    private ExecutionPlan.Rows rows(PlanNode node) {
        Operator root = translateNode(node);
        return new ExecutionPlan.Rows(root.schema(), root);
    }

    /** Node perintah lokal membawa WHERE sebagai field, sehingga target dibentuk dari Scan/Filter. */
    private Operator commandInput(String table, Expr condition) {
        PlanNode input = new TableScanNode(null, table);
        if (condition != null) input = new SelectionPlanNode(condition, input);
        return translateNode(input);
    }

    /** Mencegah pengulangan perintah, termasuk setelah eksekusi pertama gagal. */
    private static ExecutionPlan.Command command(ExecutionPlan.CommandAction action) {
        Objects.requireNonNull(action, "factory command result");
        return new ExecutionPlan.Command(
                new ExecutionPlan.CommandAction() {
                    private boolean executed;

                    @Override
                    public ExecutionPlan.CommandResult execute() {
                        if (executed) throw new IllegalStateException("Command already executed");
                        executed = true;
                        return Objects.requireNonNull(action.execute(), "command result");
                    }
                });
    }

    /* Memilih penerjemah sesuai jenis node */
    private Operator translateNode(PlanNode node) {
        Objects.requireNonNull(node, "node");
        Operator operator =
                switch (node) {
                    case TableScanNode scan -> factory.createScan(scan);
                    case IndexScanNode scan -> factory.createIndexScan(scan);
                    case SelectionPlanNode selection -> translateSelection(selection);
                    case ProjectionPlanNode projection -> translateProjection(projection);
                    case JoinPlanNode join -> translateJoin(join);
                    case CartesianPlanNode cartesian -> translateCartesian(cartesian);
                    case SortPlanNode sort -> translateSort(sort);
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported plan node: " + node.getClass().getSimpleName());
                };
        return Objects.requireNonNull(operator, "factory result");
    }

    /* Filter setelah operator child selesai dibentuk (Selection) */
    private Operator translateSelection(SelectionPlanNode node) {
        Operator child = translateNode(node.child());
        return factory.createFilter(child, node.condition());
    }

    /* Project dengan urutan ekspresi dari QO (Projection) */
    private Operator translateProjection(ProjectionPlanNode node) {
        Operator child = translateNode(node.child());
        return factory.createProject(child, node.columns());
    }

    /* Join dengan child kiri outer */
    private Operator translateJoin(JoinPlanNode node) {
        requireBnl(node.algorithm());
        if (node.condition() == null) {
            throw new IllegalArgumentException(
                    "NATURAL JOIN with null condition " + "is not supported yet");
        }
        Operator outer = translateNode(node.left());
        Operator inner = translateNode(node.right());
        return factory.createJoin(outer, inner, node.condition());
    }

    /* Cartesian join tanpa kondisi */
    private Operator translateCartesian(CartesianPlanNode node) {
        requireBnl(node.algorithm());
        Operator outer = translateNode(node.left());
        Operator inner = translateNode(node.right());
        return factory.createJoin(outer, inner, null);
    }

    private static void requireBnl(JoinAlgorithm algorithm) {
        if (algorithm != JoinAlgorithm.BLOCK_NESTED_LOOP) {
            throw new IllegalArgumentException("Unsupported join algorithm: " + algorithm);
        }
    }

    /* Sort menggunakan offset pada schema child */
    private Operator translateSort(SortPlanNode node) {
        Operator child = translateNode(node.child());
        int keyIndex = node.column().offset();
        if (keyIndex < 0 || keyIndex >= child.schema().size()) {
            throw new IllegalArgumentException("Invalid sort column offset: " + keyIndex);
        }
        boolean ascending = node.direction() == SortPlanNode.Direction.ASC;
        return factory.createSort(child, keyIndex, ascending);
    }
}
