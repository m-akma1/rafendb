package org.rafendb.processor.plan;

import java.util.List;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.IndexScanNode;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.plan.command.CreateIndexPlanNode;
import org.rafendb.processor.plan.command.DeletePlanNode;
import org.rafendb.processor.plan.command.InsertPlanNode;
import org.rafendb.processor.plan.command.UpdatePlanNode;

/**
 * Membentuk operator dan aksi tanpa menjalankan query atau mengubah storage.
 * Setiap permintaan menghasilkan objek eksekusi independen dengan schema tersedia sebelum open.
 * Factory yang dibagi antar sesi harus aman dipanggil bersamaan.
 * Parameter dan hasil tidak boleh null, kecuali condition pada createJoin.
 *
 * <p>Method perintah memakai node sementara QP2 untuk implementasi lokal M2,
 * bukan kontrak final QO. CommandAction adalah adapter hasil internal QP2,
 * bukan pengganti interface DML worker yang menghasilkan jumlah baris.
 */
public interface OperatorFactory {
    /** Schema mengikuti urutan kolom tabel; qualifier memakai alias jika tersedia. */
    Operator createScan(TableScanNode node);

    /** Indeks dan lookup mengikuti descriptor node; output mengikuti schema lengkap tabel. */
    Operator createIndexScan(IndexScanNode node);

    /** Filter mempertahankan schema dan RID child; hanya kondisi TRUE meloloskan row. */
    Operator createFilter(Operator child, Expr condition);

    /** Urutan output mengikuti expressions; hasil proyeksi tidak membawa RID sumber. */
    Operator createProject(Operator child, List<Expr> expressions);

    /** Schema dan nilai berurutan outer lalu inner; condition null berarti Cartesian. */
    Operator createJoin(Operator outer, Operator inner, Expr condition);

    /** keyIndex relatif terhadap schema child; ascending true berarti ASC, false berarti DESC. */
    Operator createSort(Operator child, int keyIndex, boolean ascending);

    /** Nilai node sementara mengikuti urutan kolom tabel; aksi belum dijalankan. */
    ExecutionPlan.CommandAction createInsert(InsertPlanNode node);

    /** WHERE sudah diterapkan pada child yang membawa row lengkap dan RID; aksi mengelola lifecycle child. */
    ExecutionPlan.CommandAction createUpdate(UpdatePlanNode node, Operator child);

    /** Child sudah memuat target DELETE beserta RID; aksi mengelola lifecycle child. */
    ExecutionPlan.CommandAction createDelete(DeletePlanNode node, Operator child);

    /** Pembangunan indeks ditunda sampai execute; hasilnya nol affectedRows. */
    ExecutionPlan.CommandAction createIndex(CreateIndexPlanNode node);
}
