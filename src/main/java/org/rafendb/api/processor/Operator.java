package org.rafendb.api.processor;

import java.util.List;
import org.rafendb.common.schema.ColumnSchema;
import org.rafendb.common.tuple.Row;

/**
 * Kontrak pembacaan baris. Setiap objek dipakai untuk satu eksekusi dan tidak aman dipakai bersamaan.
 * Input dan layanan diberikan saat objek dibuat; API ini tidak menjanjikan perubahan data SQL.
 */
public interface Operator extends AutoCloseable {
    /**
     * Memulai atau mengulang pembacaan dari awal, termasuk saat masih terbuka, habis, atau ditutup.
     * Menginisialisasi ulang operator anak sesuai kebutuhan dan melepas resource lama sebelum diganti.
     * Jika terjadi kegagalan, close tetap harus aman dipanggil untuk membersihkan resource.
     */
    void open();

    /**
     * Mengembalikan daftar identitas kolom yang tidak null dan tidak dapat diubah, sesuai urutan nilai row.
     * Tersedia sebelum open dan setelah close, termasuk saat hasil kosong. Urutan join adalah outer
     * lalu inner. Setiap kolom menyimpan identitas dan tipe datanya.
     */
    List<ColumnSchema> schema();

    /**
     * Mengembalikan baris berikutnya, atau null jika habis; panggilan berikutnya tetap null sampai di-reset.
     * @throws IllegalStateException jika belum dibuka atau sudah ditutup
     */
    Row next();

    /** Melepas resource; aman dipanggil sebelum open maupun berulang kali. */
    @Override
    void close();
}
