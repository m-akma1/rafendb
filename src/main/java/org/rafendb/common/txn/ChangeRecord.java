package org.rafendb.common.txn;

import java.util.Optional;

/**
 * One physical row change. INSERT has no before-image; DELETE has no after-image; UPDATE has both.
 * At least one image must exist. SQL NULL is encoded inside an image, not as record absence.
 * The SM/FRM-agreed encoding must restore the exact row; relocation is DELETE plus INSERT.
 *
 * @param resource the exact row identity, not a table resource; not null
 * @param beforeImage serialized previous row, or empty for INSERT; not null
 * @param afterImage serialized new row, or empty for DELETE; not null
 */
public record ChangeRecord(
        ResourceId resource, Optional<byte[]> beforeImage, Optional<byte[]> afterImage) {}
