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

    /** Membentuk operator Scan baru tanpa membuka cursor atau meminta lock. */
    @FunctionalInterface
    public interface ScanConstructor {
        /**
         * Menyiapkan operator pembacaan tabel.
         *
         * @param context context sesi yang tidak null, sumber storage dan transaksi
         * @param table nama tabel yang tidak null
         * @param alias alias tabel, boleh null
         * @return operator baru yang tidak null
         */
        Operator create(ExecutionContext context, String table, String alias);
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

    /** Constructor UPDATE QP5; child membawa row tabel lengkap beserta RID. */
    @FunctionalInterface
    public interface UpdateConstructor {
        /**
         * Menyiapkan UPDATE tanpa membuka child atau mengubah storage.
         *
         * @param table nama tabel yang tidak null
         * @param column target assignment yang tidak null
         * @param value ekspresi nilai baru yang tidak null
         * @param child input lengkap beserta RID yang tidak null
         * @param evaluator evaluator ekspresi yang tidak null
         * @param context context sesi yang tidak null
         * @return operator baru yang tidak null
         */
        DMLOperator create(
                String table,
                Expr.ColumnRef column,
                Expr value,
                Operator child,
                ExpressionEvaluator evaluator,
                ExecutionContext context);
    }

    /** Constructor DELETE QP3; lifecycle child dimiliki operator DELETE. */
    @FunctionalInterface
    public interface DeleteConstructor {
        /**
         * Menyiapkan DELETE tanpa membuka child atau mengubah storage.
         *
         * @param context context sesi yang tidak null
         * @param child input lengkap beserta RID yang tidak null
         * @param table nama tabel yang tidak null
         * @return operator baru yang tidak null
         */
        DMLOperator create(ExecutionContext context, Operator child, String table);
    }

    /** Constructor CREATE INDEX QP5; pembentukan belum meminta lock atau mengubah storage. */
    @FunctionalInterface
    public interface CreateIndexConstructor {
        /**
         * Menyiapkan aksi CREATE INDEX baru.
         *
         * @param context context sesi yang tidak null
         * @param node payload CREATE INDEX yang tidak null
         * @return aksi baru yang tidak null
         */
        ExecutionPlan.CommandAction create(ExecutionContext context, CreateIndexPlanNode node);
    }

    /**
     * Mengikat QP5 ke context satu sesi.
     * Node diteruskan utuh; konversi jenis indeks menjadi tipe storage dimiliki worker.
     * Pemanggil menyiapkan transaksi aktif sebelum menjalankan aksi melalui factory.
     *
     * @param context context sesi yang tidak null
     * @param constructor pembentuk aksi CREATE INDEX yang tidak null
     * @return builder untuk parameter createIndexBuilder pada constructor factory
     * @throws NullPointerException jika context, storage, constructor, node, atau hasil null
     */
    public static Function<CreateIndexPlanNode, ExecutionPlan.CommandAction> bindCreateIndex(
            ExecutionContext context, CreateIndexConstructor constructor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(constructor, "constructor");
        Objects.requireNonNull(context.getStorageManager(), "storage");
        return node -> {
            Objects.requireNonNull(node, "node");
            return Objects.requireNonNull(constructor.create(context, node), "CREATE INDEX action");
        };
    }

    /**
     * Mengikat UPDATE QP5 dengan context sesi dan evaluator.
     * WHERE sudah diterapkan translator pada child. Target dan ekspresi SET diteruskan
     * tanpa diubah, dan child lengkap beserta RID tetap menjadi input worker.
     *
     * @param context context satu sesi yang tidak null
     * @param evaluator evaluator yang tidak null dan aman dipakai oleh eksekusi terkait
     * @param constructor pembentuk UPDATE yang tidak null
     * @return builder UPDATE; tidak menjalankan child atau memulai transaksi
     * @throws NullPointerException jika parameter, node, child, atau hasil pembentukan null
     */
    public static BiFunction<UpdatePlanNode, Operator, DMLOperator> bindUpdate(
            ExecutionContext context,
            ExpressionEvaluator evaluator,
            UpdateConstructor constructor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(constructor, "constructor");
        return (node, child) -> {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(child, "child");
            return Objects.requireNonNull(
                    constructor.create(
                            node.table(), node.column(), node.value(), child, evaluator, context), "UPDATE operator");
        };
    }

    /**
     * Mengikat DELETE QP3 dengan context satu sesi. WHERE sudah diterapkan pada child;
     * binding mempertahankan child beserta RID dan tidak menjalankan lifecycle-nya.
     *
     * @param context context satu sesi yang tidak null
     * @param constructor pembentuk DELETE yang tidak null
     * @return builder DELETE yang meneruskan context, child, dan tabel
     * @throws NullPointerException jika parameter, node, child, atau hasil pembentukan null
     */
    public static BiFunction<DeletePlanNode, Operator, DMLOperator> bindDelete(
            ExecutionContext context, DeleteConstructor constructor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(constructor, "constructor");
        return (node, child) -> {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(child, "child");
            return Objects.requireNonNull(
                    constructor.create(context, child, node.table()), "DELETE operator");
        };
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

    /**
     * Mengikat constructor Scan QP3 dengan context satu sesi.
     *
     * <p>Context diteruskan sebagai objek yang sama agar READ lock memakai transaksi aktif
     * ketika operator dibuka. Binding tidak membuka cursor, meminta lock, atau memulai
     * transaksi. Pemanggil menjalankan statement melalui AutoCommit atau setelah BEGIN.
     *
     * @param context context sesi yang tidak null
     * @param constructor pembentuk Scan yang tidak null
     * @return builder yang meneruskan nama tabel dan alias dari node
     * @throws NullPointerException jika context, storage, constructor,
     *     node, atau operator hasil pembentukan null
     */
    public static Function<TableScanNode, Operator> bindScan(
            ExecutionContext context, ScanConstructor constructor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(constructor, "constructor");

        Objects.requireNonNull(context.getStorageManager(), "storage");

        return node -> {
            Objects.requireNonNull(node, "node");

            return Objects.requireNonNull(
                    constructor.create(context, node.table(), node.alias()), "Table Scan operator");
        };
    }
}
