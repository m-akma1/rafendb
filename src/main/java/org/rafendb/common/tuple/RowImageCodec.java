package org.rafendb.common.tuple;

/**
 * Turns a {@link Row} into the {@code byte[]} stored as before/after image in a
 * {@link org.rafendb.common.txn.ChangeRecord}, and back.
 *
 * <p>The bytes are always valid UTF-8 text.
 * The image is self-describing: decoding needs no schema.
 * </p>
 */
public interface RowImageCodec {

    /** Encodes {@code row} losslessly (including SQL NULL and each value's type). */
    byte[] encode(Row row);

    /**
     * Rebuilds the row from bytes produced by {@link #encode}.
     *
     * @throws IllegalArgumentException if {@code image} is not a valid image
     */
    Row decode(byte[] image);

    RowImageCodec DEFAULT = new TextRowImageCodec();
}
