# nadi agentfeed - Server untuk Agent OMP (Tokocrypto-only)

> **Nama:** `nadi agentfeed` · **Package:** `agu.analys` ·
> **Repo:** `Agus27712/agentfeed` (sumber sekaligus tujuan rilis)

Aplikasi Android **Kotlin + Jetpack Compose M3** yang berfungsi sebagai
**server untuk agent OMP**. Pengguna hanya **memantau chart**; keputusan,
perhitungan, dan eksekusi trading **diserahkan kepada agent OMP** melalui
**Agent Bridge**.

Aplikasi ini **hanya mendukung Tokocrypto** (USDT + IDR). Dukungan Indodax
dihapus total dan **halaman detail koin dihapus**; pintu masuk lama langsung
membuka chart layar penuh.

## Pembagian tanggung jawab

| Lapisan | Pemilik | Keterangan |
|---|---|---|
| Keputusan & perhitungan trading | Agent OMP | Aplikasi tidak memutuskan beli/jual sendiri |
| Eksekusi order | Agent OMP via Agent Bridge | Aplikasi menyediakan jalur riil dan simulasi |
| Data pasar, candle, indikator, render chart | Aplikasi | Sumber tunggal: REST + WebSocket Tokocrypto |
| Portofolio & saldo | Aplikasi | Saldo riil, posisi spot, dompet simulasi |
| Monitoring | Pengguna | Cukup memantau chart |

> Pipeline analitik lama (`LearningTradingEngine`, `CandidateScanWorker`,
> `engine/indicators`) **tidak dihapus** karena masih memasok candle/indikator
> untuk chart. Ia bukan pengambil keputusan akhir.

## Empat kapabilitas

1. **Charting** - `LandscapeChartScreen` + Lightweight Charts, multi-timeframe
   (`1m`, `5m`, `15m`, `1h`, `4h`, `1d`), indikator EMA/Bollinger/StochRSI.
   `openChart(pair)` menggantikan `openCoinDetail(pair)`. Chart layar penuh kini
   stabil untuk di-scroll dan di-pinch, dan hasil ketik koin di dashboard masuk
   ke **mini-chart** lebih dulu (lihat di bawah).
2. **Jalur order riil / simulasi** - `TokocryptoTradeApi`, `RealTradeExecutor`,
   `SimulationOrderEngine`, dengan tipe bersama netral `SpotOrderResult` dan
   `SpotBalances` (`service/SpotTradeTypes.kt`).
3. **Portofolio** - `realSpotBalance`, posisi spot, `SimulationWallet`, ekspor
   log audit Markdown.
4. **Agent Bridge / server OMP** - `bridge/` + `AgentTradingCommandHandler`.
   Kontrak protokol lengkap ada di **`AGENT_BRIDGE.md`**.

## Stabilitas chart & alur mini-chart

Chart layar penuh (`LightweightChartView` + `assets/chart/lightweight_chart.html`)
sebelumnya **selalu rekomposisi ulang**, sehingga scroll dan pinch tidak pernah
bertahan. Perbaikannya:

- `AndroidView.update` **sengaja dikosongkan**; data hanya didorong lewat
  `LaunchedEffect` yang stabil. `resetKey` membedakan pergantian
  pair/timeframe/`Fit` dari tick harga langsung.
- JS `setCandles(input, resetView)` memakai `series.update()` inkremental untuk
  dataset yang sama dan **menjaga visible logical range**; `setData()` /
  `fitChart()` hanya pada dataset baru atau reset eksplisit.
- Gestur diaktifkan: `touch-action: none`, `horzTouchDrag`, `pinch`, zoom bawaan
  WebView dimatikan, parent tidak memotong sentuhan. Render dirapikan (tema gelap,
  label monospace, `priceFormat` adaptif, `ResizeObserver`).

Alur pencarian koin di dashboard: **ketik -> baris hasil memuat `MiniSparkline`
inline -> klik membuka `CoinMiniChartPreview` -> chart penuh hanya lewat tombol
`Buka Chart Penuh`**. Tidak ada lagi loncatan langsung ke layar penuh dari
hasil cari, dan mini-chart memakai `MarketTick`/`CandleBar` nyata (bukan angka
sintetis).

## Ketahanan Agent Bridge (anti force-close)

Saat agent memanggil fungsi bridge, kegagalan apa pun kini berubah menjadi
JSON 500 `{"success":false,"available":false,"error":...,"errorType":...}`
lewat pelindung pipeline `Throwable` di `AgentHttpServer.installRoutes()`.
`Error` seperti `NoSuchMethodError` tidak lagi menembus `catch (Exception)` dan
menutup aplikasi; `CancellationException` tetap dilempar kembali. Detail ada di
`AGENT_BRIDGE.md` §8.

## Sumber data tunggal: Tokocrypto

- `MarketDataSource.TOKOCRYPTO` adalah satu-satunya nilai enum.
- `TokocryptoSymbolRepository`: discovery pair, `LOT_SIZE`, `PRICE_FILTER`,
  `MIN_NOTIONAL`.
- `TokocryptoMarketService` + `TokocryptoMarketWebSocket`: ticker, candle,
  order book, recent trades, peringkat volume 24 jam.
- `FeeSchedule` di `config/AppConfiguration.kt`: satu-satunya sumber tarif
  (didokumentasikan di `FEE_TRUTH.md`).
- Agent mengambil data yang **sama** lewat endpoint lokal
  `/api/v1/market/*`; tidak ada proxy harga eksternal.

## Arsitektur

- Kotlin 100%, Jetpack Compose M3, MVVM + Coordinator.
- Coroutines, StateFlow, SharedFlow; Room v8; Encrypted SharedPreferences.
- OkHttp untuk bursa, **Ktor Server CIO** untuk Agent Bridge lokal
  (`127.0.0.1:8765`, tanpa Netty, tanpa cloud).

```
app/src/main/java/agu/analys/
├── bridge/          # AgentHttpServer, AgentMarketDataProvider, AgentMarketModels,
│                    # AgentTradingCommandHandler, AgentOutputBridge, OmpBridgeInstaller
├── config/          # AppConfiguration, FeeSchedule, MarketDataSource
├── data/            # TokocryptoSymbolRepository, OrderBookDepthCache
├── database/        # Room v8 + DAO
├── engine/          # Pipeline indikator & analitik (suplai data chart)
├── model/           # TradingPair, MarketKey, state UI
├── network/         # NetworkClientProvider
├── service/         # Layanan pasar Tokocrypto, SpotTradeTypes, worker
├── trading/         # SimulationOrderEngine, TradeLogExporter
├── ui/              # screens/, components/, theme/
├── viewmodel/       # TradingViewModel + coordinator
└── util/            # AppPreferences, AppLogManager, PriceFormatter
```

> Folder `data/` dan `docs/` di **root repo** sudah dihapus (arsip riset lama).
> `app/src/main/java/agu/analys/data/` di atas adalah **paket Kotlin**, bukan
> folder arsip itu.

## Lingkungan & konfigurasi

- Min SDK 24, Target SDK 35, Compile SDK 36, JDK 17 untuk build.
- Kredensial Tokocrypto (opsional) di *Pengaturan -> Mode Real Tokocrypto*,
  tersimpan terenkripsi.
- Gemini API Key (opsional) untuk kurasi berita.
- Agent Bridge diaktifkan dari Pengaturan; token dipakai agent untuk rute tulis.

## Build & verifikasi

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
python3 tools/check_md_text.py
cd tools/bridge-smoke && gradle run
```

**Signing bukan lagi penghalang.** `app/build.gradle.kts` membuat keystore rilis
**opsional**: bila `RELEASE_KEYSTORE_BASE64` (atau `RELEASE_KEYSTORE_PATH` beserta
password/alias) tidak diisi, build release memakai **debug key bawaan AGP**
sehingga APK tetap bisa dipasang. Semua workflow meneruskan secret apa adanya
dan tidak pernah gagal hanya karena secret kosong.

**Batas memori build.** Runner CI maupun container lokal hanya punya ~7–8 GiB.
`gradle.properties` karenanya memakai heap 2 GB, 2 worker, paralel dimatikan, dan
Kotlin compiler in-process; `app/build.gradle.kts` menonaktifkan lint otomatis
pada build rilis. Kombinasi lama (heap 4 GB + daemon Kotlin terpisah + 4 worker +
lint vital) memicu OOM di tengah R8. Pemeriksaan lint tetap tersedia lewat
`./gradlew :app:lint`. Jangan menaikkan angka-angka itu tanpa mengukur ulang.

Semua workflow memakai composite action lokal **`.github/actions/android-sdk`**
yang memanfaatkan SDK bawaan runner. `android-actions/setup-android@v3` tidak
dipakai karena menjalankan `sdkmanager tools` (paket usang) dan menggagalkan job
sebelum build dimulai — insiden run `43052078287`, 2026-11-11.

Build Android dijalankan lewat GitHub Actions manual (`workflow_dispatch`):

| Workflow | Hasil |
|---|---|
| `APK Debug` (`apkdebug.yml`) | APK debug / release / keduanya, diverifikasi `apksigner`, diunggah sebagai artifact |
| `Release APK` (`release.yml`) | GitHub Release berisi APK rilis |
| `Bridge Smoke V3` | Bukti R8 pada APK rilis + smoke server Agent Bridge V3 |
| `Unit Tests (Manual)` | Tes unit per cakupan |

Push tidak memicu rilis. Untuk sekadar mendapatkan APK yang bisa dipasang, pakai
**`APK Debug`**.

## Dokumen

| Dokumen | Isi |
|---|---|
| `AGENTS.md` | Aturan repo, arah produk, larangan |
| `app/AGENTS.md` | Aturan area `app/` |
| `tools/AGENTS.md` | Aturan area `tools/` dan smoke |
| `AGENT_BRIDGE.md` | Kontrak protokol Agent Bridge V3 |
| `APP_CONTEXT.md` | Fakta teknis kunci |
| `FEE_TRUTH.md` | Model biaya all-in Tokocrypto |
| `TOKOCRYPTO_API_COMPLIANCE.md` | Kepatuhan signed trading API |
