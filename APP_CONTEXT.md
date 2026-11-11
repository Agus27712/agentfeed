# Konteks Arsitektur & Domain - nadi agentfeed

Dokumen ringkas untuk fakta teknis yang sering dipakai. Arah produk dan aturan
kerja ada di `AGENTS.md`; kontrak protokol ada di `AGENT_BRIDGE.md`.

## Arah produk

Aplikasi **Tokocrypto-only** yang berfungsi sebagai **server untuk agent OMP**.
Pengguna memantau chart; keputusan, perhitungan, dan eksekusi trading ada di
agent. Empat kapabilitas: charting, jalur order riil/simulasi, portofolio, dan
Agent Bridge. Pipeline `engine/` tetap ada sebagai pemasok candle/indikator,
bukan pengambil keputusan. Halaman detail koin sudah dihapus.

## Fakta teknis kunci

| Aspek | Nilai terkini |
|---|---|
| Nama tampilan | `nadi agentfeed` |
| `applicationId` / namespace | `agu.analys` |
| Room Database | versi **8** - `RealTradeEntity`, `RealOpenOrderEntity`, `SignalLogEntity`, `TradeHistoryRecordEntity` |
| DAO | `realTradeDao` (termasuk `real_open_orders`), `signalLogDao`, `tradeHistoryRecordDao` |
| Android SDK | minSdk 24, targetSdk 35, compileSdk 36 |
| Jaringan bursa | OkHttp REST + WebSocket ke Tokocrypto |
| Agent Bridge | Ktor Server CIO, **hanya `127.0.0.1:8765`**, tanpa Netty/cloud |
| Bursa didukung | Tokocrypto, kuotasi **USDT + IDR**; Indodax dihapus total, Binance dilarang di runtime |
| Sumber tarif | `object FeeSchedule` di `config/AppConfiguration.kt` |
| Versi API bridge | `2026-11-08` |
| ID layanan | `agentfeed-bridge-v3` |

## Batasan yang mengikat

- `MarketDataSource` hanya punya `TOKOCRYPTO`. Tidak ada pemilih bursa ganda.
- Quote `BIDR` dieliminasi, kecuali koin yang memang bernama `...BIDR`
  (misal `BNBIDR`, `SHIBIDR`).
- Cache, SharedPreferences, dan Room dipartisi per `exchange`; query DAO
  memfilter kolom `exchange`.
- Format harga/PnL mengikuti `quoteAsset` (Rp untuk IDR, $ untuk USDT).
- Data pasar untuk agent berasal dari layanan yang **sama** dengan chart;
  kegagalan dilaporkan `available=false` + `error`, bukan angka sintetis.
- Bridge menangkap **`Throwable`** (bukan hanya `Exception`) lewat pelindung
  pipeline Ktor, mengubah kegagalan jadi JSON 500 `available=false`. Ini mencegah
  force-close saat agent memanggil fungsi bridge (insiden `NoSuchMethodError`).
- Chart penuh tidak boleh rekomposisi tiap tick: `AndroidView.update` kosong,
  data lewat `LaunchedEffect` + `resetKey`, JS `setCandles` inkremental menjaga
  visible range. Hasil cari koin di dashboard masuk mini-chart dulu.

## Peta berkas

| Berkas | Isi |
|---|---|
| `bridge/AgentHttpServer.kt` | Rute HTTP, WebSocket, SSE, gerbang token |
| `bridge/AgentMarketDataProvider.kt` | Penyedia ticker/klines/depth/simbol/biaya |
| `bridge/AgentMarketModels.kt` | DTO kontrak data pasar |
| `bridge/AgentTradingCommandHandler.kt` | Order, status, portofolio, open order |
| `bridge/AgentOutputBridge.kt` | Output dokumen OMP + push portofolio |
| `bridge/OmpBridgeInstaller.kt` | Instalasi `index.ts` dan token |
| `config/AppConfiguration.kt` | `MarketDataSource`, `FeeSchedule`, `TradingFeeConfig` |
| `service/TokocryptoMarketService.kt` | REST resmi Tokocrypto |
| `service/SpotTradeTypes.kt` | `SpotBalances`, `SpotOrderResult` |
| `ui/components/chart/LightweightChartView.kt` | Pembungkus WebView chart (stabil, anti-rekomposisi) |
| `assets/chart/lightweight_chart.html` | Runtime JS Lightweight Charts (scroll/pinch/inkremental) |
| `ui/components/dashboard/CoinMiniChartPreview.kt` | Pratinjau mini-chart dari hasil cari koin |
| `tools/oh-my-pi-extension/index.ts` | Ekstensi OMP (konsumen bridge) |
| `tools/bridge-smoke/` | Uji JVM server bridge dengan sumber produksi |
