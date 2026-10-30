/**
 * Pembatalan transaksi (FR-04) dan pemulihan basis data ke titik waktu tertentu (FR-05).
 *
 * <p>{@link org.rafendb.recovery.rollback.RollbackManager} membaca record transaksi dari logger
 * FR2 ({@link org.rafendb.recovery.rollback.RollbackLog}), menulis record kompensasi untuk setiap
 * perubahan, lalu mengembalikan isi lama lewat {@link org.rafendb.recovery.rollback.UndoStorage}.
 *
 * <p>Owner: G5 Failure Recovery Manager (FR3).
 */
package org.rafendb.recovery.rollback;
