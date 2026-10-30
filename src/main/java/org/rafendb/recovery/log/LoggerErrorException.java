package org.rafendb.recovery.log;

/**
 * Dilempar ketika logger gagal menulis atau mem-flush record log. Pemanggil tidak boleh
 * melanjutkan operasi yang mensyaratkan record tersebut sudah tersimpan permanen (aturan WAL).
 */
public class LoggerErrorException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Membuat exception.
     *
     * @param errMessage penjelasan kesalahan
     */
    public LoggerErrorException(String errMessage) {
        super(errMessage);
    }

    /**
     * Membuat exception beserta penyebabnya.
     *
     * @param errMessage penjelasan kesalahan
     * @param cause kegagalan asalnya, misalnya error I/O
     */
    public LoggerErrorException(String errMessage, Throwable cause) {
        super(errMessage, cause);
    }
}
