/**
 * Penerjemahan plan pembacaan QO menjadi pohon operator QP (QP-03).
 * DefaultPlanTranslator menelusuri child sebelum parent dan memakai OperatorFactory
 * untuk membentuk operator tanpa menjalankan lifecycle eksekusinya.
 * Node perintah sementara QP2 diterjemahkan menjadi aksi eager yang ditunda
 * sampai execute dipanggil; payload perintah belum merupakan kontrak final QO.
 *
 * <p>Owner: G1 Query Processor (QP2).
 */
package org.rafendb.processor.plan;
