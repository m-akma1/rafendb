package org.rafendb.storage.seed;

import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;

/** SM4 sample-data skeleton; accepting the facade at load time avoids a constructor cycle. */
public class SampleDatabaseLoader {
    /**
     * Loads at least three related tables with 50 rows each and all four types. No-op when tables
     * already exist. Caller provides exclusive bootstrap access and the agreed logging policy.
     */
    public void load(StorageManager storage) throws StorageInternalException {
        // TODO SM4: Isi lewat facade; koordinasikan LSN bootstrap dan pemulihan seed terputus.
        throw new UnsupportedOperationException("SM4: SampleDatabaseLoader.load");
    }
}
