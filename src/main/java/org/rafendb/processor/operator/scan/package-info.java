/**
 * Sequential scan dan projection (QP-05). {@link org.rafendb.processor.operator.scan.TableScanOp}
 * baca tabel dari SM lewat {@link org.rafendb.api.storage.StorageQuery} lengkap sama RID, setelah
 * minta lock baca tabel lewat {@link org.rafendb.processor.txn.TableLocks}; qualifier kolomnya alias
 * atau nama tabel. {@link org.rafendb.processor.operator.scan.ProjectOp} ngambil kolom child sesuai
 * urutan SELECT, tanpa RID.
 *
 * <p>Owner: G1 Query Processor (QP3).
 */
package org.rafendb.processor.operator.scan;
