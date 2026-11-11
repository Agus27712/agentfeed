# AGENTS.md - aturan area `app/`

Dibaca saat menyentuh `app/`: kode Kotlin produksi, `bridge/`, `engine/`,
UI/Compose, atau tes unit. Aturan umum (bahasa, branch, dokumen wajib,
verifikasi, larangan) ada di `AGENTS.md` akar.

## Arah yang berlaku

Aplikasi **Tokocrypto-only** dan berfungsi sebagai **server untuk agent OMP**.
Yang dibutuhkan hanya charting, jalur order riil/simulasi, portofolio, dan
Agent Bridge. **Keputusan serta perhitungan trading ada di agent OMP**; pengguna
hanya memantau chart. Halaman detail koin sudah dihapus - bekas pintu masuknya
membuka `LANDSCAPE_CHART` lewat `openChart(pair)`.

> Rebranding 2026-11-11 sudah tuntas: repo, jalur OMP, output, ID layanan,
> env token, dan tema memakai identitas `agentfeed` / `nadi agentfeed`.
> Yang tetap `agu.analys` hanyalah **ID paket Android + skema Room**;
> nama kelas `TradingViewModel*` dan atribusi pustaka Lightweight Charts
> juga dipertahankan. Lihat `AGENTS.md` §2.

## Yang tidak boleh dibangun ulang

- `MarketDataSource.INDODAX`, kelas `Indodax*`, kredensial `indodax*`, dan
  konstanta `FeeSchedule.INDODAX_*`.
- `AppScreen.DETAIL`, `DetailChartScreen`, `DetailUiState`,
  `TradingViewModelDetailState`, dan `ui/components/detail/`.
- Fitur kesiapan multi-horizon (`engine/readiness/*`, `HorizonReadinessEngine`,
  `ReadinessBoard`, kartu `N/5` lintas-horizon) - dibatalkan karena membuat dua
  angka untuk satu pertanyaan.
- Engine/spec baru, gate entry baru, atau klaim profit tanpa pengukuran.

## `engine/` dan worker

`LearningTradingEngine`, `CandidateScanWorker`, dan `engine/indicators` **tetap
ada** karena memasok candle dan indikator ke chart. Jangan dihapus, tetapi
**jangan pula dijadikan pengambil keputusan trading**. Aplikasi tidak boleh
memicu order atas inisiatif sendiri.

## Chart & mini-chart

- `LightweightChartView`: **`AndroidView.update` harus tetap kosong**. Mendorong
  data di sini membuat chart rekomposisi tiap frame dan merusak scroll/pinch.
  Data masuk lewat `LaunchedEffect`; `resetKey` memisahkan ganti
  pair/timeframe/`Fit` dari tick harga.
- `lightweight_chart.html`: `setCandles(input, resetView)` wajib
  `series.update()` inkremental dan menjaga visible logical range; `setData()` /
  `fitChart()` hanya untuk dataset baru/reset eksplisit. Jangan mematikan
  `horzTouchDrag` / `pinch` / `touch-action: none`.
- Dashboard: hasil ketik koin **masuk ke mini-chart dulu**
  (`MiniSparkline` inline -> `CoinMiniChartPreview`); chart penuh hanya dari
  tombol `Buka Chart Penuh`. Mini-chart memakai `MarketTick`/`CandleBar` nyata.

## Agent Bridge

- Sumber data pasar adalah `TokocryptoMarketService`,
  `TokocryptoSymbolRepository`, dan `FeeSchedule` yang sama dengan chart.
- Kegagalan fetch wajib menjadi `available=false` + `error`; **dilarang** angka
  sintetis.
- Rute `/api/v1/trading/*` fail-closed dengan `X-Agent-Token`; server hanya
  mengikat `127.0.0.1`.
- **Tangkap `Throwable`, bukan `Exception`.** `NoSuchMethodError` (turunan
  `Error`) pernah menutup aplikasi saat agent memanggil bridge. Pelindung
  pipeline `ApplicationCallPipeline.ApplicationPhase.Call` di
  `AgentHttpServer.installRoutes()` mengubah kegagalan jadi JSON 500;
  `CancellationException` tetap dilempar kembali. `/api/v1/market/context`
  tidak boleh melempar.
- Kontrak protokol: `AGENT_BRIDGE.md`. Perubahan rute wajib menyelaraskan
  `AgentHttpServer.kt`, `AgentMarketDataProvider.kt`, DTO di
  `AgentMarketModels.kt`, ekstensi `tools/oh-my-pi-extension/index.ts`, stub
  `tools/bridge-smoke`, dan dokumen.

## Fee & kuotasi

- `FeeSchedule` di `config/AppConfiguration.kt` adalah **satu-satunya** sumber
  tarif. Lihat `FEE_TRUTH.md`.
- Hanya kuotasi **USDT** dan **IDR**. Quote `BIDR` dieliminasi kecuali koin
  bernama `...BIDR` (`BNBIDR`, `SHIBIDR`).
- Cache, SharedPreferences, dan Room dipartisi per `exchange`; selalu teruskan
  exchange secara eksplisit ke `OrderBookDepthCache.updateOrderBook()`.

## Jaringan

- OkHttp untuk REST/WebSocket Tokocrypto; Ktor Server CIO untuk bridge lokal.
- **Dilarang** request ke `binance.com` / `stream.binance.com` di runtime
  produksi. Arsip data historis hanya boleh dipakai alat offline, bukan app.
- `minSdk 24`: jangan memakai `java.time.Duration` di `AgentHttpServer.kt`;
  pakai `kotlin.time.Duration` dari Ktor.

## Verifikasi

`./gradlew :app:testDebugUnitTest` (JDK 17). Perubahan bridge juga wajib lolos
`cd tools/bridge-smoke && gradle run` dan `python3 tools/check_md_text.py`.
Kontrol negatif: uji harus gagal saat perbaikan dimatikan.
