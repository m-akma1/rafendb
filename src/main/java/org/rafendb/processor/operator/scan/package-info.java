/**
 * Sequential scan dan projection (QP-05). {@link org.rafendb.processor.operator.scan.TableScanOp}
 * baca tabel dari SM lengkap sama RID, {@link org.rafendb.processor.operator.scan.ProjectOp}
 * ngambil kolom child sesuai urutan SELECT, tanpa RID.
 *
 * <p>Owner: G1 Query Processor (QP3).
 */
package org.rafendb.processor.operator.scan;
