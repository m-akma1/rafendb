package org.rafendb.processor.error;

/**
 * Dilempar operator QP pas komponen lain gagal di tengah eksekusi (misalnya SM gagal baca disk),
 * biar error checked-nya bisa lewat Operator dan nyampe ke client.
 */
public class QueryExecutionException extends QueryProcessorException {
    private static final long serialVersionUID = 1L;

    /**
     * Bikin exception dengan pesan dan penyebabnya.
     *
     * @param message apa yang gagal
     * @param cause error asli dari komponen lain
     */
    public QueryExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
