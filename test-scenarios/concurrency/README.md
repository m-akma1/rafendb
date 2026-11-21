# Driver CCM

Gunakan JDK 25. Dari root repo, buat JAR lalu buka driver.

```sh
./mvnw -q package -DskipTests -Dspotless.apply.skip=true
scripts/run.sh driver concurrency
```

Driver menunggu input. Ketik `help` untuk daftar perintah atau `quit` untuk keluar.
Protokol default adalah `LOCK_BASED`. Pilih `--protocol=TIMESTAMP_BASED` saat
membuka driver untuk mencoba timestamp ordering. Driver memakai CCM, FRM,
dan SM asli secara default. `begin` pertama atau `reset` membuka database
dan WAL sementara. Setiap tabel menyimpan satu
nilai integer bertanda 32-bit. Jika baris belum ada, nilai yang ditampilkan
adalah 0. Tabel dibuat saat penulisan pertama dan bisa tersisa kosong
setelah rollback. File database dan WAL dihapus saat skenario di-reset
atau driver ditutup.

Gunakan `--mock=sm,frm` untuk menguji CCM dengan data dan rollback di
memori. Kedua dependensi diganti bersama dalam mode ini.

## Menjalankan file

Di prompt, jalankan `run test-scenarios/concurrency/wait-rollback.txt`.
Dari shell, gunakan opsi `--run=<file>` untuk menjalankan file lalu keluar.

```sh
scripts/run.sh driver concurrency --protocol=TIMESTAMP_BASED --run=test-scenarios/concurrency/wait-rollback.txt
```

Di Windows gunakan `scripts\run.cmd` dan beri tanda kutip pada setiap opsi
yang mengandung `=`, misalnya `"--protocol=TIMESTAMP_BASED"`.

Isi satu perintah per baris. Baris kosong dan baris yang dimulai dengan
`#` atau `--` dilewati. Gunakan tanda kutip ganda untuk path yang berisi
spasi. File skenario bawaan diawali `reset` agar dapat dijalankan berulang
dari prompt yang sama. Setelah file selesai, `RESULT` merangkum jumlah
pemeriksaan yang lulus dan gagal.
Untuk `--run=<file>`, selesaikan transaksi dan jalankan `verify` sebelum
`reset` atau akhir file. Driver melaporkan `FAIL` dan keluar dengan kode 1
jika skenario belum diverifikasi. Pemeriksaan di akhir file muncul
setelah ringkasan perintah dari driver bersama.

| File | Protokol | Pemeriksaan |
| --- | --- | --- |
| [wait-rollback.txt](wait-rollback.txt) | Keduanya | Dua pembaca, penulis menunggu, lalu rollback mengembalikan nilai commit sebelumnya dan menghapus baris nol yang belum commit. |
| [two-commits.txt](two-commits.txt) | Keduanya | Penulis kedua menunggu penulis pertama. Keduanya commit, nilai akhir 2. |
| [parallel-conflict.txt](parallel-conflict.txt) | Keduanya | Pembaca dan penulis dikirim lewat dua worker. Penulis menunggu pembaca commit. |
| [deadlock.txt](deadlock.txt) | `LOCK_BASED` | T2 menjadi korban, perubahannya dibatalkan, dan T1 bisa lanjut. |
| [deadlock-three.txt](deadlock-three.txt) | `LOCK_BASED` | T3 menjadi korban siklus tiga transaksi, lalu T1 dan T2 commit. |
| [deadlock-upgrade.txt](deadlock-upgrade.txt) | `LOCK_BASED` | Dua pembaca meminta upgrade. Survivor bisa menulis dan memakai lagi lock yang sudah dipegang. |
| [lock-fifo.txt](lock-fifo.txt) | `LOCK_BASED` | Penulis yang datang belakangan menunggu pembaca di depan antrean. |
| [timestamp-violation.txt](timestamp-violation.txt) | `TIMESTAMP_BASED` | Akses transaksi lama ditolak, rollback menghapus perubahannya, dan commit berikutnya ditolak. |

## Perintah skenario

| Perintah | Kegunaan |
| --- | --- |
| `reset` | Mulai dari keadaan kosong dengan protokol yang sama. |
| `begin <tx>` | Mulai transaksi dengan alias unik, misalnya `T1`. |
| `read <tx> <table> [status]` | Baca nilai tabel dan tampilkan nilainya jika diizinkan. |
| `write <tx> <table> <value> [status]` | Tulis nilai setelah akses diizinkan dan perubahan dicatat. |
| `parallel "<access>" "<access>"` | Kirim dua perintah `read` atau `write` ke worker transaksi berbeda. |
| `retry <tx> [status]` | Ulangi operasi yang sedang menunggu dengan argumen semula. |
| `commit <tx> [status]`, `abort <tx> [status]` | Selesaikan transaksi dan bandingkan statusnya. |
| `assert value <table> <value>` | Periksa nilai langsung dari data uji, misalnya setelah rollback. |
| `verify <serializable\|cyclic>` | Periksa schedule yang dieksekusi setelah semua transaksi selesai. |

Untuk `read`, `write`, dan `retry`, status yang diharapkan default-nya
`ALLOWED`. Tulis `WAITING` atau `ABORTED` bila itu hasil yang diharapkan.
Saat menerima `WAITING`, jalankan langkah transaksi lain, lalu gunakan
`retry` untuk mengulang permintaan semula. `commit` dan `abort` masing-masing
mengharapkan `COMMITTED` dan `ABORTED` secara default.

Urutan eksekusi di dalam `parallel` bergantung pada penjadwalan thread.
Gunakan perintah ini ketika kedua urutan memberi status yang sama,
misalnya dua pembaca pada satu tabel. Untuk skenario konflik yang perlu
diulang dengan hasil tetap, tulis langkah akses dan `retry` secara berurutan.

`verify` menampilkan akses yang benar-benar dieksekusi, precedence graph
transaksi yang commit, dan urutan serial jika grafnya asiklik. Untuk
urutan serial yang ditemukan, driver membandingkan hasil baca serta
keberadaan dan nilai baris akhir dengan eksekusi ulang transaksi yang commit.

## Memeriksa schedule tulisan tangan

Ketik `verify cyclic test-scenarios/concurrency/manual-cycle.txt` di prompt.
Untuk contoh asiklik, ketik `verify serializable test-scenarios/concurrency/manual-serializable.txt`.
Kedua perintah ini bisa dipakai langsung setelah driver dibuka. Mode ini
hanya membaca jadwal dan menghitung graf, tanpa membuka database.
Dalam file ini, tulis akses yang sudah terjadi dengan format
`read <tx> <table>` atau `write <tx> <table>`, tanpa argumen nilai atau status.
Tulis `commit <tx>` untuk transaksi yang commit. Akses dari transaksi tanpa
`commit` tidak ikut membentuk graf. Gunakan `verify serializable <file>`
untuk schedule yang diharapkan asiklik.
