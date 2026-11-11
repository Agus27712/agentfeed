# Kepatuhan Signed Trading API Tokocrypto

Catatan ringkas untuk `app/src/main/java/agu/analys/service/TokocryptoTradeApi.kt`
dan `AgentMarketDataProvider`. Bukan spesifikasi resmi bursa; bila ada perbedaan,
dokumentasi resmi Tokocrypto yang berlaku.

## Endpoint

Base URL: `https://www.tokocrypto.com`

| Endpoint | Kegunaan |
|---|---|
| `GET /open/v1/account/spot` | Saldo spot (semua aset) |
| `POST /open/v1/orders` | Buat order |
| `GET /open/v1/orders/detail` | Detail order |
| `POST /open/v1/orders/cancel` | Batalkan order |
| `POST /open/v1/user-listen-token` | Token user stream |

`GET /open/v1/account/spot/asset` membutuhkan parameter `asset` dan tidak cocok
untuk mengambil seluruh saldo; `getAccount()` memakai `/open/v1/account/spot`.

## Aturan tanda tangan

1. Header `X-MBX-APIKEY` berisi API key.
2. Payload tanda tangan adalah query string **`key=value` yang diurutkan secara
   alfabetis** (`sortedBy { it.first }`), lalu di-HMAC-SHA256 dengan secret key
   dan di-hex-kan lowercase. Urutan yang tidak konsisten menghasilkan
   `Signature for this request is not valid` / `code: -1022`.
3. `timestamp` harus epoch ms saat ini dan `recvWindow` wajib dikirim.
4. Semua request signed memakai pola yang sama; `createOrder()` sudah memakai
   `sortedBy`. Saat menambah request signed baru, pakai helper yang sama dan
   uji dengan kontrol negatif (urutan diacak harus gagal).

## Format nilai

- Simbol memakai `BASE_QUOTE` (misal `BTC_USDT`), bukan `BTCUSDT`.
  `TokocryptoSymbolRepository.getSymbolInfo()` adalah sumber utama.
- `side` dan `type` dikirim sebagai kode angka (`TokocryptoOrderSide`,
  `TokocryptoOrderType`).
- `timeInForce` dipetakan ke kode: `GTC=1`, `IOC=2`, `FOK=3`, `GTX=4`.
- **Jangan** mengirim `clientId` teks custom; format itu ditolak bursa
  (kode `3703`).
- Harga dan quantity dibulatkan sesuai `tickSize` / `stepSize` dari filter
  simbol sebelum dikirim.

## Kaitan dengan Agent Bridge

Agent tidak menandatangani request sendiri. Ia memanggil endpoint lokal
`/api/v1/trading/*`; aplikasi yang memegang kredensial terenkripsi dan
menjalankan `TokocryptoTradeApi`. Aturan eksekusi (`tickSize`, `stepSize`,
`minQty`, `minNotional`) diterbitkan lewat `/api/v1/market/symbols` dan
`/api/v1/market/context` supaya agent bisa menyusun order yang valid sebelum
mengirimnya.
