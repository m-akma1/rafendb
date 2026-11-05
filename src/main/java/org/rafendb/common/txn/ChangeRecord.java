package org.rafendb.common.txn;

import java.util.Optional;

/**
 * One physical row change. INSERT has no before-image; DELETE has no after-image; UPDATE has both.
 * At least one image must exist. SQL NULL is encoded inside an image, not as record absence.
 * The image encoding is not defined yet; it must restore the exact row. Relocation is DELETE plus
 * INSERT.
 * Preserve the table and full Rid; neither a narrowed numeric ID nor display text is a substitute
 * for lossless row identity and image encoding.
 *
 * <p>Not validated on construction; {@link
 * org.rafendb.api.concurrency.ConcurrencyLogging#logChange} rejects invalid records.
 *
 * @param resource the exact row identity, not a table resource; not null
 * @param beforeImage serialized previous row, or empty for INSERT; not null
 * @param afterImage serialized new row, or empty for DELETE; not null
 */
public record ChangeRecord(
        ResourceId resource, Optional<byte[]> beforeImage, Optional<byte[]> afterImage) {}
