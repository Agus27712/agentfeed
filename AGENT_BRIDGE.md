# Kontrak Agent Bridge V3 - nadi agentfeed

Dokumen ini adalah **sumber kebenaran protokol** antara aplikasi `nadi agentfeed`
dan agent OMP. Versi API **`2026-11-08`**, ID layanan
**`agentfeed-bridge-v3`**.

## 1. Transport & keamanan

| Aspek | Nilai |
|---|---|
| Host | `127.0.0.1` (**loopback only**, bukan `0.0.0.0`) |
| Port | `8765` |
| Mesin | Ktor Server CIO + WebSockets; tanpa Netty, tanpa cloud |
| HTTP | `http://127.0.0.1:8765` |
| WebSocket sinyal / portofolio | `/ws/signals`, `/ws/portfolio` |
| SSE | `/events` (`event: ping`, `signal`, `portfolio`) |

**Token.** Semua rute `/api/v1/trading/*` menuntut header `X-Agent-Token` yang
cocok dengan `AppPreferences.agentBridgeToken`. Bila token server kosong,
**semua** permintaan ditolak (fail-closed), bukan diterima tanpa token. Sumber
token bagi ekstensi: env `AGENTFEED_TOKEN` atau berkas
`agent-bridge-token.txt` yang ditulis `OmpBridgeInstaller` /
`APPLY_BRIDGE_V2.sh` ke direktori ekstensi OMP. Rute baca (termasuk seluruh
`/api/v1/market/*`) bersifat **publik** dan tidak mengirim token.

## 2. Discovery

`GET /` dan `GET /api/v1/capabilities` mengembalikan `AgentCapabilities`:
`service`, `apiVersion`, `app`, `exchange`, `quoteAssets`, `intervals`,
batas `limit`, daftar `readEndpoints` / `writeEndpoints` / `pushEndpoints`,
`authHeader`, `authScope`, `dataPolicy`, dan `serverTime`. Agent sebaiknya
memanggil ini lebih dulu agar tidak menebak nama endpoint.

`GET /health` mengembalikan `ok`, `service`, `apiVersion`, `running`, `ts`.

## 3. Endpoint baca

| Endpoint | Hasil |
|---|---|
| `GET /api/v1/market/time` | Waktu server bursa, waktu perangkat, `offsetMs` |
| `GET /api/v1/market/ticker?symbol=` | Harga, high/low/volume/perubahan 24 jam |
| `GET /api/v1/market/klines?symbol=&interval=&limit=` | Candle OHLCV + `closed` |
| `GET /api/v1/market/depth?symbol=&limit=` | Bids/asks, spread, imbalance |
| `GET /api/v1/market/symbols?quote=&limit=` | Daftar simbol + aturan eksekusi |
| `GET /api/v1/market/context?symbol=&interval=&limit=` | **Seluruh konteks** dalam satu panggilan |
| `GET /signals/latest`, `/signals/history?limit=` | Sinyal engine terakhir |
| `GET /portfolio` | Snapshot portofolio |

`interval` yang didukung: `1m`, `5m`, `15m`, `1h`, `4h`, `1d`.
`limit` klines: 10-1000; `limit` depth: 5-100; `limit` symbols: 1-500.
Kuotasi: `USDT` dan `IDR`. Simbol boleh ditulis `BTCUSDT`, `BTC_USDT`, atau
`BTC-USDT`; server menormalkan ke `BASE_QUOTE`.

### Kebijakan data (wajib dipatuhi agent)

- Setiap snapshot membawa `available` dan `source`. **Baca `available` dulu.**
- Bila `available=false`, payload memuat `error` dan angka nol **bukan** data.
- `source` untuk data nyata adalah `TOKOCRYPTO_REST`. Tidak ada angka sintetis.
- Fetch dibatasi timeout 9 detik supaya koneksi agent tidak menggantung.
- `GET /api/v1/market/context` menggabungkan ticker, klines, depth,
  `rules` (tickSize/stepSize/minQty/minNotional), `fees` (maker/taker,
  slippage, round-trip), sinyal terakhir, portofolio, dan status kesiapan
  order. Bagian yang belum tersedia bernilai `null` dan dijelaskan di `notes`.

## 4. Endpoint tulis (butuh token)

| Endpoint | Kegunaan |
|---|---|
| `GET /api/v1/trading/status` | Kesiapan Mode Real, sesi PIN, saldo, versi API |
| `GET /api/v1/trading/orders?symbol=&isSimulation=&exchange=` | **Daftar open order** untuk rekonsiliasi |
| `GET /api/v1/trading/orders/{id}?...` | Detail satu order |
| `POST /api/v1/trading/buy` | Order beli (`AgentTradeRequest`) |
| `POST /api/v1/trading/sell` | Order jual (`AgentTradeRequest`) |
| `POST /api/v1/trading/cancel` | Batalkan order (`AgentCancelRequest`) |

`GET /api/v1/trading/orders` sengaja dideklarasikan **sebelum** rute
`/orders/{id}` agar rute statis tidak tertelan parameter. Sumbernya:
`SimulationCoordinator.openOrders` untuk paper, dan cache Room
`real_open_orders` untuk riil. Bila sumber belum siap, endpoint mengembalikan
`success=false` + pesan, **bukan** daftar kosong yang bisa dibaca agent sebagai
"tidak ada order".

## 5. Push

| Kanal | Isi |
|---|---|
| `WS /ws/signals` | Snapshot sinyal terakhir saat connect, lalu tiap sinyal baru |
| `WS /ws/portfolio` | Snapshot portofolio (replay = 1), lalu tiap perubahan |
| `SSE /events` | `ping`, `signal`, `portfolio` dalam satu koneksi |

## 6. Ekstensi OMP

Berkas `tools/oh-my-pi-extension/index.ts` dipasang ke
`~/.omp/agent/extensions/agentfeed/index.ts`. Ia menyediakan 23 tool,
dikelompokkan:

- **Sinyal & portofolio**: `get_latest_signal`, `subscribe_signals`,
  `get_signal_history`, `get_portfolio`, `subscribe_portfolio`, `bridge_health`.
- **Discovery & data pasar**: `get_bridge_capabilities`, `get_market_time`,
  `get_market_ticker`, `get_market_klines`, `get_market_depth`,
  `get_market_symbols`, `get_market_context`.
- **Trading**: `get_bridge_trading_status`, `place_buy_order`,
  `place_sell_order`, `cancel_order`, `get_order_status`, `list_open_orders`.
- **Jurnal paper**: `paper_trade_journal`, `watch_paper_position`,
  `buy_and_watch_paper`, `write_research_log`.

Harga ticker dan klines untuk jurnal/watch **juga** diambil dari endpoint lokal
ini. Tidak ada lagi host proxy pihak ketiga; keputusan agent memakai angka yang
identik dengan chart aplikasi.

## 7. Instalasi & pembuktian

- Pemasangan dari Pengaturan app (`OmpBridgeInstaller`) menulis `index.ts` dan
  token. Alternatif manual: `bash tools/oh-my-pi-extension/APPLY_BRIDGE_V2.sh`.
- Pemeriksaan cepat di Termux: `bash tools/oh-my-pi-extension/test-v2.sh`.
- Uji nyata di JVM: `cd tools/bridge-smoke && gradle run`. Proyek ini
  meng-compile **`AgentHttpServer.kt` beserta DTO produksi apa adanya** dan
  menjalankan HTTP, fail-closed 401, WebSocket, SSE, serta stop/restart.
- Type-check ekstensi: `tsc --noEmit` dengan stub modul OMP (lihat
  `tools/AGENTS.md`).

## 8. Ketahanan kegagalan (anti force-close)

Saat agent memanggil bridge, **tidak ada kegagalan yang boleh menutup aplikasi**.
Insiden lama `NoSuchMethodError` (turunan `Error`, bukan `Exception`) menembus
`catch (e: Exception)` dan mengakhiri proses. Aturan yang berlaku sekarang:

- `AgentHttpServer.installRoutes()` memasang pelindung pipeline
  `ApplicationCallPipeline.ApplicationPhase.Call` yang menangkap **`Throwable`**
  dan mengembalikan JSON 500:
  `{"success":false,"available":false,"error":"...","errorType":"..."}`.
- Handler trading, WebSocket, SSE, dan `/api/v1/market/context` juga menangkap
  `Throwable`; `CancellationException` tetap dilempar kembali agar pembatalan
  coroutine tidak tertelan.
- Kegagalan membangun `/api/v1/market/context` menjadi `notes` berisi alasan
  dengan `fees`/`rules` bernilai `null`, bukan pengecualian.
- `AgentOutputBridge.outputDir` selalu punya fallback ke penyimpanan internal
  `filesDir/agentfeed-output`; `OmpBridgeInstaller` menambah
  `FLAG_ACTIVITY_NEW_TASK` saat meluncurkan Termux dari application context.

**Bagi agent:** respons `5xx` dengan `available=false` berarti data tidak
tersedia. Jangan mengirim order sampai endpoint data kembali `available=true`.
