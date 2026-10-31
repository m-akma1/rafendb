## Intinya
Menyediakan kontrak antarmuka Failure Recovery Manager (`api/recovery/`), integrasi `WalSync` dan `ILogFlusher`, penyeragaman LSN menjadi `long`, serta implementasi in-memory `MockRecoveryManager` yang lengkap mencakup seluruh Functional Requirements (FR-01 s.d. FR-07) untuk kesiapan Milestone 1.

## Spesifikasi dan issue terkait
- FR-01 Mencatat Perubahan Data ke Log (WAL - `writeLog` in-memory dengan alokasi LSN naik monotonik)
- FR-02 Memaksa Log ke Penyimpanan Stabil (Force Log / WalSync - `forceThrough` & `flushLogUntil`)
- FR-03 Menyediakan Antarmuka Recovery Manager & Inspeksi Log (Facade Lifecycle & `getLogsByTransaction`)
- FR-04 Membatalkan Transaksi (Transaction Rollback Mock - `rollbackTransaction` & `undo`)
- FR-05 Pemulihan ke Titik Waktu Tertentu (Point-In-Time Recovery Mock - `recoverToPointInTime`)
- FR-06 Pembuatan Checkpoint Sistem (Quiescent Checkpoint Mock - `createCheckpoint`)
- FR-07 Pemulihan Kegagalan Sistem (Crash Recovery / Redo & Undo Mock - `recoverFromCrash`, `redo`, `undo`)

## Contract impact
- [ ] Does not touch `api/` or `common/`
- [x] Touches `api/` or `common/`: labelled `contract-change`, every affected PIC is tagged below

## Related PIC
- @Akram17t (PIC Storage Manager - terkait kontrak `api/storage/WalSync`, `ILogFlusher`, & LSN `long`)
- @m-akma1 (Supergroup maintainer)
- @tengkuhrmn (PIC FR3 Rollback Manager)
- @AdharmaYudha (PIC PITR)

## Testing
- Jalankan Maven test suite dan verifikasi penuh:
  ```bash
  ./mvnw verify
  ```
  Hasil pengujian: **5/5 unit tests pass** (0 failures, 0 errors):
  - `MockRecoveryManagerTest.testBasicLifecycleAndLogs`: verifikasi alur begin, insert, commit, rollback status
  - `MockRecoveryManagerTest.testWriteLogWithRidAndNullCheck`: verifikasi payload record Rid dan null handling
  - `MockRecoveryManagerTest.testFilterLogsByTransaction`: verifikasi filter log per transaksi (FR-03)
  - `MockRecoveryManagerTest.testMocksAndForceThrough`: verifikasi checkpoint, PITR, crash recovery, redo/undo, dan forceThrough / flushLogUntil
  - `MockRecoveryManagerTest.testClear`: verifikasi reset state memory
- Format code Spotless: **100% clean** (110 files checked).
