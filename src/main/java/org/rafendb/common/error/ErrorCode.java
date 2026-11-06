package org.rafendb.common.error;

/**
 * Klasifikasi resmi jenis kegagalan Query Optimizer (QO).
 *
 * <p>Enum ini menjadi bagian dari kontrak error dengan Query Processor (QP1):
 * QP dapat merutekan/menangani kegagalan secara terprogram berdasarkan
 * kategori, tanpa perlu mem-parsing pesan teks. Detail kegagalan (pesan,
 * token, posisi, nama tabel/kolom) disampaikan terpisah melalui
 * {@code ErrorMessage}.</p>
 *
 * <p>Setiap nilai memetakan 1:1 ke satu tahap pipeline QO:</p>
 * <ul>
 *   <li>{@link #SYNTAX_ERROR} — tahap tokenisasi/parsing (QO-01, QO-02, QO-04, QO-05, QO-06)</li>
 *   <li>{@link #VALIDATION_ERROR} — tahap binding terhadap katalog (QO-07)</li>
 *   <li>{@link #COST_ESTIMATION_ERROR} — tahap cost model &amp; pemilihan plan (QO-08, QO-12, QO-13, QO-14)</li>
 *   <li>{@link #INTERNAL_ERROR} — fallback catch-all di Facade agar QO tidak crash (QO-01 AC)</li>
 * </ul>
 *
 * @see ErrorMessage
 */
public enum ErrorCode {

    /**
     * Query ditolak pada tahap tokenisasi atau parsing karena tidak sesuai
     * tata bahasa.
     *
     * <p>Contoh pemicu (QO-01 AC): keyword salah eja, klausa tidak lengkap,
     * urutan klausa salah, kurung tidak seimbang, literal teks tanpa tanda
     * kutip penutup, atau ekspresi tidak lengkap (QO-04 AC).</p>
     *
     * <p>Sumber: {@code Tokenizer}, {@code Parser}, {@code ExpressionParser}
     * — dilempar sebagai {@code SyntaxException}.</p>
     */
    SYNTAX_ERROR,

    /**
     * Query valid secara sintaks tetapi gagal validasi semantik terhadap
     * katalog Storage Manager.
     *
     * <p>Contoh pemicu (QO-07 AC): tabel tidak ada, kolom tidak ada pada
     * tabel terkait, kolom tanpa prefix ambigu, operasi antar tipe tidak
     * kompatibel, jumlah/tipe nilai {@code INSERT} atau {@code SET} tidak
     * sesuai kolom tujuan. Pesan wajib menyebut nama tabel/kolom
     * bermasalah.</p>
     *
     * <p>Sumber: {@code Binder} — dilempar sebagai
     * {@code ValidationException}.</p>
     */
    VALIDATION_ERROR,

    /**
     * Query valid secara sintaks dan semantik, tetapi QO gagal menghitung
     * biaya atau memilih plan eksekusi.
     *
     * <p>Contoh pemicu: statistik tabel tidak tersedia atau korup dari
     * {@code StorageManagerStats}, algoritma join yang dibutuhkan tidak
     * disediakan {@code JoinAlgorithmProvider}, atau tidak ada kandidat
     * plan valid setelah pencarian.</p>
     *
     * <p>Sumber: {@code CostModel} dan {@code JoinOrderOptimizer} —
     * dilempar sebagai {@code CostEstimationException}.</p>
     */
    COST_ESTIMATION_ERROR,

    /**
     * Kegagalan tak terduga di internal QO yang tidak termasuk kategori
     * lain — mis. bug, invariant yang dilanggar, atau fitur yang belum
     * diimplementasi.
     *
     * <p>Bertindak sebagai catch-all di {@code QoFacade} agar tidak ada
     * {@code RuntimeException} mentah yang lolos ke QP (QO-01 AC: "bukan
     * crash").</p>
     */
    INTERNAL_ERROR
}
