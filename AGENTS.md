# AGENTS.md - nadi agentfeed

Aturan repo. Baca sebelum menyentuh kode, dokumen, atau workflow. Dokumen ini
**ringkas dan otoritatif**; tidak ada lagi `workplan2.md`, `docs/`, atau `data/`.

## 1. Arah produk

Aplikasi Android Kotlin (Jetpack Compose M3), nama tampilan **`nadi agentfeed`**.
Fungsinya sekarang **server untuk agent OMP**: pengguna hanya memantau chart;
keputusan, perhitungan, dan eksekusi trading berada di agent OMP.

Yang disediakan aplikasi, hanya empat:

1. **Charting** - candle dan indikator dari Tokocrypto.
2. **Jalur order riil dan simulasi** - dipanggil agent lewat Agent Bridge.
3. **Portofolio** - saldo, posisi, dan snapshot.
4. **Agent Bridge / server OMP** - HTTP, WebSocket, SSE di `127.0.0.1:8765`.

Konsekuensi yang mengikat:

- **Tokocrypto-only**: `MarketDataSource.TOKOCRYPTO`, kuotasi **USDT + IDR** saja.
  Dukungan Indodax dihapus total dari kode, tes, dan dokumen aktif.
- **Halaman detail koin dihapus**; pintu masuk lama membuka `LANDSCAPE_CHART`
  lewat `openChart(pair)`.
- Pipeline `engine/` dan worker **tidak dihapus** karena masih memasok candle dan
  indikator ke chart, tetapi **bukan pengambil keputusan trading**.
- Arsip riset `data/` dan `docs/` **dihapus** (2026-11-08), sudah masuk
  `.gitignore`, dan jangan dibuat ulang. Termasuk `workplan2.md`, `SCALP_EXIT.md`,
  skrip replay CSV, dan dataset lama.

## 2. Identitas (satu nama, dipakai konsisten)

Rebranding selesai 2026-11-11: **seluruh identitas lama sudah dihapus** dari
kode, tes, tools, maupun dokumen. Tidak ada lagi nama produk lawas, slug repo
lawas, env token lawas, User-Agent lawas, atau tema lawas. Semua kontrak di
bawah memakai identitas baru dan harus diubah bersamaan.

| Kontrak | Nilai |
|---|---|
| `applicationId` / `namespace` | `agu.analys` (ID paket, bukan nama brand) |
| Nama tampilan | `nadi agentfeed` |
| Repo & in-app updater | `Agus27712/agentfeed` |
| Direktori ekstensi OMP | `~/.omp/agent/extensions/agentfeed` |
| Direktori output OMP | `Documents/agentfeed/output` |
| ID layanan bridge | `agentfeed-bridge-v3` |
| Env token ekstensi | `AGENTFEED_TOKEN` |
| Tema Compose | `NadiAgentfeedTheme` |
| Versi API bridge | `2026-11-08` |

Semua nilai di atas dipakai `OmpBridgeInstaller`, `AgentOutputBridge`,
`AgentMarketDataProvider`, `GitHubUpdater`, dan ekstensi OMP. **Ubah bersamaan**
atau koneksi agent terputus. Tiga pengecualian yang sengaja dipertahankan:
`agu.analys` (ID paket Android + skema Room), nama kelas `TradingViewModel*` /
`TradingViewBridge` (nama kelas internal), dan atribusi pustaka pihak ketiga
**TradingView Lightweight Charts** di `app/src/main/assets/chart/` (wajib lisensi).

## 3. Aturan kerja

- **Bahasa Indonesia** untuk balasan ke owner, dokumen, komentar kode, dan pesan
  commit. Istilah teknis boleh tetap bahasa Inggris. Dilarang aksara CJK.
- **Branch kerja `main`.** Push hanya atas instruksi owner; instruksi aktif saat
  ini adalah push ke `Agus27712/agentfeed`.
- **Setiap perubahan wajib memperbarui dokumen terkait** di §7. Pekerjaan tanpa
  jejak dokumen dianggap belum selesai.
- **Verifikasi sebelum push**: `python3 tools/check_md_text.py`,
  `git diff --check`, type-check `tools/oh-my-pi-extension/index.ts`, dan smoke
  `gradle run` di `tools/bridge-smoke`.
- Perangkat owner tidak punya JDK/Android SDK; build Android dibuktikan lewat
  GitHub Actions yang dijalankan manual (`workflow_dispatch`). Workflow `APK
  Debug` (`apkdebug.yml`) adalah jalur utama untuk menghasilkan APK yang bisa
  dipasang; `Release APK`, `Bridge Smoke V3`, dan `Unit Tests (Manual)` melengkapi.
- **Keystore rilis opsional.** `app/build.gradle.kts` memakai debug key bawaan
  AGP bila secret signing tidak ada. Workflow **dilarang** `exit 1` hanya karena
  `RELEASE_KEYSTORE_BASE64` kosong; APK harus tetap terbangun.
- **Dilarang** memakai `android-actions/setup-android@v3`: ia memanggil
  `sdkmanager tools` yang sudah tidak ada dan menggagalkan job sebelum build
  (run `43052078287`). Semua workflow wajib memakai composite action lokal
  `.github/actions/android-sdk` yang berbasis SDK bawaan runner.
- **Kontrol negatif**: uji harus gagal saat perbaikan dimatikan. Uji yang selalu
  hijau tidak membuktikan apa pun.
- **Klaim angka wajib diukur**, bukan diingat dari pesan lama.
- Bila ragu, ambil keputusan terbaik dan catat alasannya di dokumen.

## 4. Aturan khusus Agent Bridge

- Semua endpoint data pasar berasal dari `TokocryptoMarketService`,
  `TokocryptoSymbolRepository`, dan `FeeSchedule` yang **sama** dengan chart.
- Kegagalan ambil data wajib dilaporkan sebagai `available=false` + `error`.
  **Dilarang** mengisi angka nol atau hasil tebakan seolah itu harga nyata.
- Ekstensi OMP **tidak boleh** memanggil host harga/klines eksternal; seluruh
  data pasar diambil dari `http://127.0.0.1:8765/api/v1/market/*`.
- Rute `/api/v1/trading/*` wajib header `X-Agent-Token` dan **fail-closed**
  bila token server kosong. Rute baca bersifat publik.
- Server mengikat **hanya `127.0.0.1`**, bukan `0.0.0.0`.
- **Tangkap `Throwable`, bukan `Exception`.** Insiden lama `NoSuchMethodError`
  (turunan `Error`) menembus `catch (e: Exception)` dan menutup aplikasi saat
  agent memanggil bridge. `AgentHttpServer.installRoutes()` memasang pelindung
  pipeline `ApplicationCallPipeline.ApplicationPhase.Call` yang mengubah
  kegagalan apa pun menjadi JSON 500; handler trading, WebSocket, dan SSE
  juga menangkap `Throwable`. `CancellationException` tetap dilempar kembali.
- `/api/v1/market/context` **tidak boleh melempar**: kegagalan menjadi `notes`
  berisi alasan dengan `fees`/`rules` bernilai `null`.
- Kontrak lengkap ada di **`AGENT_BRIDGE.md`**.

## 5. Larangan

- Menambah kembali `MarketDataSource.INDODAX`, kelas `Indodax*`, kredensial
  `indodax*`, atau konstanta `FeeSchedule.INDODAX_*`.
- Menambah kembali `AppScreen.DETAIL`, `DetailChartScreen`, `DetailUiState`,
  atau `ui/components/detail/`.
- Request ke `binance.com` / `stream.binance.com` di runtime produksi.
- Membuat ulang `data/`, `docs/`, `workplan2.md`, `SCALP_EXIT.md`, atau alat
  replay berbasis CSV.
- Menjadikan `engine/` sebagai pengambil keputusan trading.
- Commit `.env`, `*.keystore`, `*.jks`, atau token dalam bentuk apa pun.
- Menulis di UI bahwa sinyal punya winrate/expectancy historis tanpa data cukup.

## 6. Perintah

```sh
./gradlew :app:testDebugUnitTest                  # unit test (JDK 17)
./gradlew :app:assembleDebug :app:assembleRelease # APK debug + rilis (tanpa secret)
python3 tools/check_md_text.py                    # kebersihan Markdown
git diff --check                                  # higiene whitespace
cd tools/bridge-smoke && gradle run               # smoke server bridge nyata
tsc --noEmit ...                                  # type-check index.ts (lihat tools/AGENTS.md)
```

Rilis dibuat manual lewat Actions → `Release APK`. Untuk APK siap pasang tanpa
membuat rilis, pakai Actions → `APK Debug`. Push tidak memicu rilis.

## 7. Dokumen rujukan

| Dokumen | Isi |
|---|---|
| `README.md` | Ringkasan produk, kapabilitas, struktur direktori |
| `AGENT_BRIDGE.md` | Kontrak protokol, endpoint, keamanan, tool OMP |
| `APP_CONTEXT.md` | Fakta teknis kunci (Room, SDK, jaringan) |
| `FEE_TRUTH.md` | Tarif all-in Tokocrypto dan konstanta `FeeSchedule` |
| `TOKOCRYPTO_API_COMPLIANCE.md` | Kepatuhan signed trading API Tokocrypto |
| `app/AGENTS.md` | Aturan area kode `app/` |
| `tools/AGENTS.md` | Aturan area `tools/` dan smoke |

## 8. Markdown & Git

- UTF-8 tanpa BOM, akhir baris LF, tanpa CJK, tanpa mojibake. Periksa dengan
  `python3 tools/check_md_text.py`.
- Identitas commit: `agus27712@omp <owner@omp.android>` (setel per clone dengan
  `git config --local`). Pesan commit jelas dan berbahasa Indonesia.
- Jangan commit artefak build, keystore, atau kredensial.
