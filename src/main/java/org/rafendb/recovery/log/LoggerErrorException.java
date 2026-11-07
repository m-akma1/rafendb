package org.rafendb.recovery.log;

import org.rafendb.common.error.Component;
import org.rafendb.common.error.RafenDBRuntimeException;

/**
 * Dilempar ketika logger gagal menulis atau mem-flush record log. Pemanggil tidak boleh
 * melanjutkan operasi yang mensyaratkan record tersebut sudah tersimpan permanen (aturan WAL).
 */
public class LoggerErrorException extends RafenDBRuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Membuat exception.
     *
     * @param errMessage penjelasan kesalahan
     */
    public LoggerErrorException(String errMessage) {
        super(Component.FRM, errMessage, null);
    }

    /**
     * Membuat exception beserta penyebabnya.
     *
     * @param errMessage penjelasan kesalahan
     * @param cause kegagalan asalnya, misalnya error I/O
     */
    public LoggerErrorException(String errMessage, Throwable cause) {
        super(Component.FRM, errMessage, cause);
    }

    public String getErrMessage() {
        return getMessage();
    }
}
