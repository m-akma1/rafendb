package org.rafendb.processor.operator.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.RowImageCodec;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.txn.TxId;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.tuple.ResultRow;

/**
 * Operator DELETE (QP-07). Kumpulin dulu semua baris target (RID + isi) dari child, child ditutup,
 * baru dihapus satu-satu: log ke CCM dulu, baru hapus di SM. Lock tulis tabel diminta sekali di
 * awal.
 *
 * <p>Ga thread-safe, satu instance buat satu eksekusi.
 */
public final class DeleteOp implements DMLOperator {
    private final Operator child;
    private final String table;
    private final StorageManager storage;
    private final WriteSupport support;

    /**
     * Bikin DeleteOp, belum ngapus apa-apa.
     *
     * @param context konteks eksekusi session
     * @param child sumber baris yang mau dihapus, tiap baris harus bawa RID; lifecycle-nya diurus
     *     DeleteOp
     * @param table nama tabel
     * @throws NullPointerException kalau context, storage manager-nya, child, atau table null
     */
    public DeleteOp(ExecutionContext context, Operator child, String table) {
        Objects.requireNonNull(context, "context");
        this.child = Objects.requireNonNull(child, "child");
        this.table = Objects.requireNonNull(table, "table");
        this.storage = Objects.requireNonNull(context.getStorageManager(), "storage");
        this.support = new WriteSupport(context, RowImageCodec.DEFAULT);
    }

    /**
     * Hapus semua baris dari child. Balikin jumlah baris yang kehapus, ga pernah null.
     *
     * @throws IllegalStateException kalau session ga valid, ga ada transaksi aktif, atau child
     *     ngasih baris tanpa RID
     * @throws TransactionAbortedException kalau CCM batalin transaksinya, log gagal, atau hapus di
     *     SM gagal setelah log ditulis
     */
    @Override
    public Long execute() {
        TxId tx;
        List<StoredRow> targets = new ArrayList<>();
        try {
            tx = support.requireTx();
            support.acquireWrite(tx, table);
            child.open();
            ResultRow row = child.next();
            while (row != null) {
                if (row.rid() == null) {
                    throw new IllegalStateException("DeleteOp needs rows with RID");
                }
                targets.add(new StoredRow(row.rid(), new Row(row.values())));
                row = child.next();
            }
        } finally {
            child.close();
        }

        long deleted = 0;
        for (StoredRow target : targets) {
            long lsn = support.logDelete(tx, table, target.rid(), target.row());
            boolean removed;
            try {
                removed = storage.delete(table, target.rid(), lsn);
            } catch (StorageInternalException | RuntimeException e) {
                throw support.abortAfterLoggedFailure(tx, "deleting from " + table, e);
            }
            if (!removed) {
                IllegalStateException gone =
                        new IllegalStateException("Row " + target.rid() + " was already gone");
                throw support.abortAfterLoggedFailure(tx, "deleting from " + table, gone);
            }
            deleted++;
        }
        return deleted;
    }
}
