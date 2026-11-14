package org.rafendb.processor.operator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;

/**
 * CCM palsu buat test: jawaban requestAccess bisa diatur, nyatet urutan kejadian (request, log,
 * abort), LSN naik terus, dan log bisa disuruh gagal. Method lain ga dipakai.
 */
public final class FakeCcm implements ConcurrencyControl, ConcurrencyLogging {
    private final List<String> events = new ArrayList<>();
    private final List<AccessResult> answers = new ArrayList<>();
    private long nextLsn = 1;
    private int abortCount;
    private boolean failNextLog;
    private boolean failNextAbort;

    /** Atur jawaban requestAccess berikutnya. Kalau antriannya kosong, jawabannya ALLOWED. */
    public void answerNext(AccessResult answer) {
        answers.add(answer);
    }

    /** Bikin logChange berikutnya gagal pakai ConcurrencyInternalException. */
    public void failNextLog() {
        failNextLog = true;
    }

    /** Bikin abort berikutnya gagal pakai ConcurrencyInternalException. */
    public void failNextAbort() {
        failNextAbort = true;
    }

    /** Urutan kejadian, misalnya "request READ dosen" atau "abort T1". */
    public List<String> getEvents() {
        return events;
    }

    /** Berapa kali abort dipanggil. */
    public int getAbortCount() {
        return abortCount;
    }

    @Override
    public AccessResult requestAccess(TxId tx, AccessType type, ResourceId resource) {
        events.add("request " + type + " " + resource.table());
        if (answers.isEmpty()) {
            return AccessResult.allowed();
        }
        return answers.remove(0);
    }

    @Override
    public TxResult abort(TxId tx) throws ConcurrencyInternalException {
        abortCount++;
        events.add("abort " + tx);
        if (failNextAbort) {
            failNextAbort = false;
            throw new ConcurrencyInternalException("Rollback failed");
        }
        return new TxResult(TxStatus.ABORTED, "");
    }

    @Override
    public long logChange(TxId tx, ChangeRecord change) throws ConcurrencyInternalException {
        if (failNextLog) {
            failNextLog = false;
            throw new ConcurrencyInternalException("Log write failed");
        }
        events.add("log " + change.resource().table() + " " + change.resource().rid());
        long lsn = nextLsn;
        nextLsn++;
        return lsn;
    }

    @Override
    public TxId begin() {
        throw new UnsupportedOperationException();
    }

    @Override
    public TxResult commit(TxId tx) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void pauseNewTransactions() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean awaitNoActive(Duration timeout) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resumeNewTransactions() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProtocolType activeProtocol() {
        throw new UnsupportedOperationException();
    }
}
