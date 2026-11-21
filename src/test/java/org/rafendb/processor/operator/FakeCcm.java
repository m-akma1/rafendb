package org.rafendb.processor.operator;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * CCM palsu buat test QP. Jawaban begin/requestAccess/commit/abort bisa diatur, LSN mulai dari 100,
 * dan tiap panggilan dicatat ke {@link EventLog}:
 *
 * <pre>
 * ccm.begin | ccm.access READ|WRITE &lt;table&gt; | ccm.log INSERT|UPDATE|DELETE lsn=N
 * ccm.log (fails) | ccm.commit T1 | ccm.abort T1
 * </pre>
 */
public final class FakeCcm implements ConcurrencyControl, ConcurrencyLogging {
    private final EventLog events;
    private final Deque<AccessResult> accessAnswers = new ArrayDeque<>();
    private final List<ChangeRecord> logged = new ArrayList<>();
    private long nextLsn = 100;
    private boolean failNextLog;
    private boolean failNextAbort;
    private TxId beginResult = new TxId(1);
    private TxResult commitResult = new TxResult(TxStatus.COMMITTED, "");
    private TxResult abortResult = new TxResult(TxStatus.ABORTED, "");

    /** CCM palsu dengan log kejadiannya sendiri. */
    public FakeCcm() {
        this(new EventLog());
    }

    /** CCM palsu yang nyatet ke log bersama (misalnya punya FakeStorage). */
    public FakeCcm(EventLog events) {
        this.events = events;
    }

    /** Antrikan jawaban requestAccess berikutnya. Kalau antriannya kosong, jawabannya ALLOWED. */
    public void answerNext(AccessResult answer) {
        accessAnswers.add(answer);
    }

    /** Atur TxId yang dibalikin begin(); boleh null. */
    public void beginReturns(TxId tx) {
        beginResult = tx;
    }

    /** Atur hasil commit(). */
    public void commitReturns(TxResult result) {
        commitResult = result;
    }

    /** Atur hasil abort(). */
    public void abortReturns(TxResult result) {
        abortResult = result;
    }

    /** Bikin logChange berikutnya gagal pakai ConcurrencyInternalException. */
    public void failNextLog() {
        failNextLog = true;
    }

    /** Bikin abort berikutnya gagal pakai ConcurrencyInternalException. */
    public void failNextAbort() {
        failNextAbort = true;
    }

    public EventLog events() {
        return events;
    }

    /** ChangeRecord yang berhasil di-log, urut sesuai LSN-nya. */
    public List<ChangeRecord> logged() {
        return logged;
    }

    @Override
    public TxId begin() {
        events.add("ccm.begin");
        return beginResult;
    }

    @Override
    public AccessResult requestAccess(TxId tx, AccessType type, ResourceId resource) {
        events.add("ccm.access " + type + " " + resource.table());
        return accessAnswers.isEmpty() ? AccessResult.allowed() : accessAnswers.poll();
    }

    @Override
    public long logChange(TxId tx, ChangeRecord change) throws ConcurrencyInternalException {
        if (failNextLog) {
            failNextLog = false;
            events.add("ccm.log (fails)");
            throw new ConcurrencyInternalException("log failed");
        }
        logged.add(change);
        long lsn = nextLsn++;
        events.add("ccm.log " + kind(change) + " lsn=" + lsn);
        return lsn;
    }

    @Override
    public TxResult commit(TxId tx) {
        events.add("ccm.commit " + tx);
        return commitResult;
    }

    @Override
    public TxResult abort(TxId tx) throws ConcurrencyInternalException {
        events.add("ccm.abort " + tx);
        if (failNextAbort) {
            failNextAbort = false;
            throw new ConcurrencyInternalException("rollback failed");
        }
        return abortResult;
    }

    private static String kind(ChangeRecord change) {
        if (change.beforeImage().isEmpty()) {
            return "INSERT";
        }
        return change.afterImage().isEmpty() ? "DELETE" : "UPDATE";
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
