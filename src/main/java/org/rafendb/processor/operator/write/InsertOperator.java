package org.rafendb.processor.operator.write;

import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;

public class InsertOperator {
    private StorageManager sm;
    private String ccm;
    private String frm;

    public InsertOperator(StorageManager sm, String ccm, String frm) {
        this.sm = sm;
        this.ccm = ccm;
        this.frm = frm;
    }

    public Rid execute(int sessionId, int transactionId, String table, Row row) {

        return null;
    }
}
