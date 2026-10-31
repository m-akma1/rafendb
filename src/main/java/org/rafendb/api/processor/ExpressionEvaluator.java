package org.rafendb.api.processor;

import java.util.List;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.ColumnSchema;
import org.rafendb.common.tuple.ResultRow;
import org.rafendb.common.type.TruthValue;
import org.rafendb.common.type.Value;

/**
 * Mengevaluasi expression dan kondisi berdasarkan row dan schema.
 * Urutan schema harus sesuai dengan posisi nilai pada row,
 * dan jumlah kolom schema harus sama dengan jumlah nilai row.
 * Pada milestone 1, implementasi mock mengembalikan hasil konfigurasi;
 * perhitungan SQL dan validasi semantik pada kontrak ini belum diimplementasikan.
 */
public interface ExpressionEvaluator {
    /**
     * Menghitung nilai expression dari row berdasarkan schema
     * @return nilai skalar tidak null
     * @throws NullPointerException jika inputnya null
     * @throws IllegalArgumentException jika expressionnya atau kolom atau type operand invalid
     * @throws ArithmeticException jika dibagi dengan 0
     */
    Value evaluate(Expr expression, ResultRow row, List<ColumnSchema> schema);

    /**
     * Menentukan apakah baris lolos
     * @return true jika evaluate TRUE, selain itu hasilnya false
     * @throws NullPointerException jika inputnya null
     * @throws IllegalArgumentException jika expressionnya atau kolom atau type operand invalid
     * @throws ArithmeticException jika dibagi dengan 0
     */
    default boolean test(Expr condition, ResultRow row, List<ColumnSchema> schema) {
        return evaluateCondition(condition, row, schema).isTrue();
    }

    /**
     * Membandingkan kondisi dari query SQL
     * @return TRUE jika kondisi benar, FALSE untuk kondisi salah, UNKNOWN jika kebenarannya tidak dapat ditentukan seperti pada perbandingan dengan SQL NULL
     * @throws NullPointerException jika inputnya null
     * @throws IllegalArgumentException jika expressionnya atau kolom atau type operand invalid
     * @throws ArithmeticException jika dibagi dengan 0
     */
    TruthValue evaluateCondition(Expr condition, ResultRow row, List<ColumnSchema> schema);
}
