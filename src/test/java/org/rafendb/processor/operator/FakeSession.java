package org.rafendb.processor.operator;

import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.storage.StorageDefinition;
import org.rafendb.api.storage.StorageQuery;
import org.rafendb.common.txn.TxId;
import org.rafendb.processor.api.SessionView;

/**
 * Session palsu buat test operator: transaksi dan status open-nya diatur langsung oleh test, karena
 * operator cuma boleh baca. Mulai dalam transaksi {@code T1}.
 */
public final class FakeSession implements SessionView {
    private final ConcurrencyControl ccm;
    private final StorageQuery storage;
    private TxId tx = new TxId(1);
    private boolean open = true;

    /** Session palsu di atas CCM dan storage test; storage boleh null kalau test-nya ga butuh. */
    public FakeSession(ConcurrencyControl ccm, StorageQuery storage) {
        this.ccm = ccm;
        this.storage = storage;
    }

    /** Ganti transaksi session; null artinya ga ada transaksi. */
    public void setCurrentTx(TxId tx) {
        this.tx = tx;
    }

    /** Tutup session, kayak session asli yang ditutup dari thread lain. */
    public void close() {
        open = false;
    }

    @Override
    public TxId currentTx() {
        return tx;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public ConcurrencyControl ccm() {
        return ccm;
    }

    @Override
    public StorageQuery storageDQL() {
        return storage;
    }

    /** Storage yang sama buat DDL, kalau storage test-nya bisa DDL; null kalau ga. */
    @Override
    public StorageDefinition storageDDL() {
        return storage instanceof StorageDefinition definition ? definition : null;
    }
}
