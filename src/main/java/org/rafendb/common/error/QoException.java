package org.rafendb.common.error;

import org.rafendb.common.token.SourcePosition;

/**
 * Exception dasar untuk seluruh kegagalan Query Optimizer (QO).
 *
 * <p>Kelas ini adalah {@link RuntimeException} yang membawa:</p>
 * <ul>
 *   <li>{@link #getCode() kode kategori} ({@link ErrorCode}) — untuk routing
 *       terprogram oleh Query Processor (QP1) tanpa parsing pesan teks;</li>
 *   <li>pesan teks siap tampil — memenuhi kontrak error QP1
 *       ("satu kelas error dengan pesan siap tampil");</li>
 *   <li>{@link #getPosition() posisi sumber} opsional (line/column/offset)
 *       — memenuhi QO-01 AC "pesan error menyebutkan token atau posisi
 *       penyebabnya";</li>
 *   <li>{@code cause} opsional — untuk menjaga rantai penyebab saat
 *       membungkus kegagalan dari dependensi eksternal (SM, dsb.).</li>
 * </ul>
 *
 * <p><b>Hierarki turunan</b> (didefinisikan di modul common):</p>
 * <ul>
 *   <li>{@code SyntaxException} — dengan {@link ErrorCode#SYNTAX_ERROR}</li>
 *   <li>{@code ValidationException} — dengan {@link ErrorCode#VALIDATION_ERROR}</li>
 *   <li>{@code CostEstimationException} — dengan
 *       {@link ErrorCode#COST_ESTIMATION_ERROR}</li>
 *   <li>{@link ErrorCode#INTERNAL_ERROR} — dipakai langsung untuk fallback
 *       tak terduga di {@code QoFacade} agar QO tidak crash.</li>
 * </ul>
 *
 * @see ErrorCode
 * @see SourcePosition
 */
public class QoException extends RuntimeException {

    /** Kategori kegagalan QO. Tidak pernah {@code null}. */
    private final ErrorCode code;

    /** Posisi sumber penyebab (token/line/column), atau {@code null} bila tidak relevan. */
    private final SourcePosition position;

    /**
     * Membuat exception tanpa posisi sumber dan tanpa cause.
     *
     * <p>Cocok untuk kegagalan yang tidak terikat lokasi token, mis.
     * {@link ErrorCode#INTERNAL_ERROR} atau kegagalan cost estimation
     * yang tidak berasal dari input teks.</p>
     *
     * @param code    kategori kegagalan; tidak boleh {@code null}
     * @param message pesan siap tampil
     */
    public QoException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    /**
     * Membuat exception dengan posisi sumber, tanpa cause.
     *
     * <p>Cocok untuk kegagalan sintaks/validasi yang perlu menyebut
     * lokasi token atau posisi penyebab (QO-01 AC).</p>
     *
     * @param code     kategori kegagalan; tidak boleh {@code null}
     * @param message  pesan siap tampil
     * @param position posisi sumber penyebab, atau {@code null}
     */
    public QoException(ErrorCode code, String message, SourcePosition position) {
        this(code, message, position, null);
    }

    /**
     * Membuat exception lengkap dengan posisi sumber dan cause.
     *
     * <p>Gunakan varian ini saat membungkus kegagalan dari dependensi
     * eksternal (mis. I/O dari Storage Manager) sehingga rantai penyebab
     * tetap terjaga untuk debugging.</p>
     *
     * @param code     kategori kegagalan; tidak boleh {@code null}
     * @param message  pesan siap tampil
     * @param position posisi sumber penyebab, atau {@code null}
     * @param cause    penyebab asli, atau {@code null}
     */
    public QoException(ErrorCode code, String message, SourcePosition position, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.position = position;
    }

    /**
     * Mengembalikan kategori kegagalan QO.
     *
     * <p>Dipakai oleh {@code QoFacade} untuk memetakan ke
     * {@link ErrorMessage} dan oleh QP untuk routing terprogram.</p>
     *
     * @return kode kategori; tidak pernah {@code null}
     */
    public ErrorCode getCode() {
        return code;
    }

    /**
     * Mengembalikan posisi sumber penyebab kegagalan.
     *
     * @return posisi sumber, atau {@code null} bila tidak relevan
     */
    public SourcePosition getPosition() {
        return position;
    }

    /**
     * Representasi teks ringkas berformat
     * {@code CODE: message (position)}.
     *
     * <p>Bagian {@code (position)} hanya muncul bila posisi tersedia.
     * Berguna untuk log dan output driver pengujian (QO-16), tetapi
     * <b>bukan</b> format error yang dikirim ke QP — itu tugas
     * {@link ErrorMessage}.</p>
     *
     * @return string representasi ringkas
     */
    @Override
    public String toString() {
        return code + ": " + getMessage() + (position != null ? " (" + position + ")" : "");
    }
}