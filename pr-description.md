## Intinya
Menyediakan kontrak antarmuka Recovery Manager (`api/recovery/`), integrasi `WalSync`, penyeragaman LSN menjadi `long`, implementasi facade `FRManager`, in-memory mock WAL `Logger`, mock Redo & Undo `CrashRecoveryEngine`, serta mengintegrasikan mock `RollbackManager` dan `PitrManager` untuk kesiapan Milestone 1.

## Spesifikasi dan issue terkait
- FR-01 Mencatat Perubahan Data ke Log (WAL)
- FR-02 Memaksa Log ke Penyimpanan Stabil (Force Log / WalSync)
- FR-03 Menyediakan Antarmuka Recovery Manager (FRM Facade & MockRecoveryManager)
- FR-04 Membatalkan Transaksi (Transaction Rollback Mock)
- FR-05 Pemulihan ke Titik Waktu Tertentu (Point-In-Time Recovery Mock)
- FR-06 Pembuatan Checkpoint Sistem (Checkpoint Mock)
- FR-07 Pemulihan Kegagalan Sistem (Crash Recovery / Redo & Undo Mock)

## Contract impact
- [ ] Does not touch `api/` or `common/`
- [x] Touches `api/` or `common/`: labelled `contract-change`, every affected PIC is tagged below

## Related PIC
- @Akram17t (PIC Storage Manager - terkait kontrak `api/storage/WalSync` & `common/`)
- @m-akma1 (Supergroup maintainer)
- @tengkuhrmn (PIC FR3 Rollback Manager)
- @AdharmaYudha (PIC PITR)

## Testing
- Jalankan Maven test suite dan verifikasi penuh:
  ```bash
  ./mvnw verify
  ```
  Hasil pengujian: **32/32 unit tests pass** (0 failures, 0 errors):
  - `MockRecoveryManagerTest` (4 tests)
  - `FRManagerTest` (9 tests)
  - `LoggerTest` (2 tests)
  - `PitrManagerTest` (4 tests)
  - `CrashRecoveryEngineTest` (3 tests)
  - `RollbackManagerTest` (10 tests)
- Format code Spotless: **100% clean** (128 files checked).

