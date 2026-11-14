package org.rafendb.processor.plan;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.IndexScanNode;
import org.rafendb.common.plan.SortPlanNode.Direction;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.common.type.Value;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.expr.ExpressionEvaluator;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.operator.filter.FilterOp;
import org.rafendb.processor.operator.join.BnlJoinOp;
import org.rafendb.processor.operator.scan.ProjectOp;
import org.rafendb.processor.operator.sort.SortOp;
import org.rafendb.processor.operator.write.DMLOperator;
import org.rafendb.processor.plan.command.CreateIndexPlanNode;
import org.rafendb.processor.plan.command.DeletePlanNode;
import org.rafendb.processor.plan.command.InsertPlanNode;
import org.rafendb.processor.plan.command.UpdatePlanNode;

/**
 * Membentuk operator dan aksi perintah tanpa menjalankannya.
 *
 * <p>Pembentuk yang diberikan harus menghasilkan objek eksekusi baru
 * pada setiap permintaan. Pembentukan tidak membuka cursor atau
 * mengubah storage.
 *
 * <p>Factory dirangkai untuk satu sesi. Pembentuk operator membawa
 * dependency dan context sesi yang dibutuhkan operator terkait.
 */
public final class DefaultOperatorFactory implements OperatorFactory {
    private final ExpressionEvaluator evaluator = new DefaultExpressionEvaluator();
    private final Function<TableScanNode, Operator> scanBuilder;
    private final Function<IndexScanNode, Operator> indexScanBuilder;
    private final Function<InsertPlanNode, DMLOperator> insertBuilder;
    private final BiFunction<UpdatePlanNode, Operator, DMLOperator> updateBuilder;
    private final BiFunction<DeletePlanNode, Operator, DMLOperator> deleteBuilder;
    private final Function<CreateIndexPlanNode, ExecutionPlan.CommandAction> createIndexBuilder;

    /**
     * Menyimpan pembentuk operator untuk satu sesi.
     *
     * <p>Seluruh pembentuk dan hasil pembentukannya harus non-null.
     * Pembentuk perintah hanya menyiapkan objek; eksekusi ditunda.
     *
     * @throws NullPointerException jika salah satu pembentuk null
     */
    public DefaultOperatorFactory(
            Function<TableScanNode, Operator> scanBuilder,
            Function<IndexScanNode, Operator> indexScanBuilder,
            Function<InsertPlanNode, DMLOperator> insertBuilder,
            BiFunction<UpdatePlanNode, Operator, DMLOperator> updateBuilder,
            BiFunction<DeletePlanNode, Operator, DMLOperator> deleteBuilder,
            Function<CreateIndexPlanNode, ExecutionPlan.CommandAction> createIndexBuilder) {
        this.scanBuilder = Objects.requireNonNull(scanBuilder, "scanBuilder");
        this.indexScanBuilder = Objects.requireNonNull(indexScanBuilder, "indexScanBuilder");
        this.insertBuilder = Objects.requireNonNull(insertBuilder, "insertBuilder");
        this.updateBuilder = Objects.requireNonNull(updateBuilder, "updateBuilder");
        this.deleteBuilder = Objects.requireNonNull(deleteBuilder, "deleteBuilder");
        this.createIndexBuilder = Objects.requireNonNull(createIndexBuilder, "createIndexBuilder");
    }

    /**
     * Mengikat constructor INSERT ke context satu sesi dan payload node QP2.
     *
     * <p>Constructor menerima nama tabel, nilai dalam urutan kolom tabel, dan context,
     * sesuai signature INSERT QP5. Setelah implementasinya tersedia, pemanggil dapat
     * memberikan {@code InsertOp::new}. Constructor hanya menyiapkan operator;
     * transaksi aktif harus disiapkan pemanggil sebelum aksi dijalankan.
     * Context diteruskan sebagai objek yang sama agar transaksi sesi dibaca saat eksekusi.
     *
     * @param context context satu sesi, tidak boleh null
     * @param constructor pembentuk operator INSERT baru, tidak boleh null
     * @return builder untuk parameter insertBuilder pada constructor factory
     * @throws NullPointerException jika context, constructor, node, atau hasil pembentukan null
     */
    public static Function<InsertPlanNode, DMLOperator> bindInsert(
            ExecutionContext context, InsertConstructor constructor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(constructor, "constructor");
        return node -> {
            Objects.requireNonNull(node, "node");
            return Objects.requireNonNull(
                    constructor.create(node.table(), node.values(), context), "INSERT operator");
        };
    }

    /** Constructor INSERT QP5; pembentukan tidak menjalankan operasi atau memulai transaksi. */
    @FunctionalInterface
    public interface InsertConstructor {
        /**
         * Menyiapkan operator INSERT baru tanpa memanggil execute.
         *
         * @param table nama tabel yang tidak null
         * @param values nilai bertipe dalam urutan kolom tabel, tidak null
         * @param context context sesi yang tidak null
         * @return operator baru yang tidak null dan dimiliki satu eksekusi
         */
        DMLOperator create(String table, List<Value> values, ExecutionContext context);
    }

    /** {@inheritDoc} */
    @Override
    public Operator createFilter(Operator child, Expr condition) {
        return new FilterOp(child, condition, evaluator);
    }

    /** {@inheritDoc} */
    @Override
    public Operator createProject(Operator child, List<Expr> expressions) {
        return new ProjectOp(child, expressions);
    }

    /** {@inheritDoc} */
    @Override
    public Operator createJoin(Operator outer, Operator inner, Expr condition) {
        return new BnlJoinOp(outer, inner, condition);
    }

    /** {@inheritDoc} */
    @Override
    public Operator createSort(Operator child, int keyIndex, boolean ascending) {
        Direction direction = ascending ? Direction.ASC : Direction.DESC;
        return new SortOp(child, keyIndex, direction);
    }

    /** {@inheritDoc} */
    @Override
    public Operator createScan(TableScanNode node) {
        Objects.requireNonNull(node, "node");
        return Objects.requireNonNull(scanBuilder.apply(node), "Table Scan operator");
    }

    /** {@inheritDoc} */
    @Override
    public Operator createIndexScan(IndexScanNode node) {
        Objects.requireNonNull(node, "node");
        return Objects.requireNonNull(indexScanBuilder.apply(node), "Index Scan operator");
    }

    /** {@inheritDoc} */
    @Override
    public ExecutionPlan.CommandAction createInsert(InsertPlanNode node) {
        Objects.requireNonNull(node, "node");
        return command(insertBuilder.apply(node), "INSERT");
    }

    /** {@inheritDoc} */
    @Override
    public ExecutionPlan.CommandAction createUpdate(UpdatePlanNode node, Operator child) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(child, "child");
        return command(updateBuilder.apply(node, child), "UPDATE");
    }

    /** {@inheritDoc} */
    @Override
    public ExecutionPlan.CommandAction createDelete(DeletePlanNode node, Operator child) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(child, "child");
        return command(deleteBuilder.apply(node, child), "DELETE");
    }

    /** {@inheritDoc} */
    @Override
    public ExecutionPlan.CommandAction createIndex(CreateIndexPlanNode node) {
        Objects.requireNonNull(node, "node");
        var action = Objects.requireNonNull(createIndexBuilder.apply(node), "CREATE INDEX action");
        return once(
                () -> {
                    var result = Objects.requireNonNull(action.execute(), "CREATE INDEX result");
                    if (result.affectedRows() != 0) {
                        throw new IllegalStateException(
                                "CREATE INDEX must report zero affected rows");
                    }
                    return result;
                });
    }

    /**
     * Mengadaptasi jumlah baris DML menjadi hasil perintah internal QP.
     * Interface DML mengizinkan null; hasil tersebut tidak dapat dijadikan jumlah baris
     * yang sah dan dilaporkan sebagai kegagalan eksekusi.
     */
    private static ExecutionPlan.CommandAction command(DMLOperator operator, String status) {
        Objects.requireNonNull(operator, "DML operator");

        return once(
                () -> {
                    Long affectedRows = operator.execute();
                    if (affectedRows == null) {
                        throw new IllegalStateException(
                                status + " operator returned no affected-row count");
                    }

                    return new ExecutionPlan.CommandResult(affectedRows, status);
                });
    }

    /** Menolak pengulangan eksekusi, termasuk setelah eksekusi pertama gagal. */
    private static ExecutionPlan.CommandAction once(ExecutionPlan.CommandAction action) {
        return new ExecutionPlan.CommandAction() {
            private boolean executed;

            @Override
            public ExecutionPlan.CommandResult execute() {
                if (executed) {
                    throw new IllegalStateException("Command already executed");
                }
                executed = true;
                return Objects.requireNonNull(action.execute(), "command result");
            }
        };
    }
}
