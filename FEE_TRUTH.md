# FEE TRUTH - Tarif All-In Tokocrypto

Sumber kebenaran biaya untuk `nadi agentfeed`. **SSOT kode** ada di
`object FeeSchedule` (`app/src/main/java/agu/analys/config/AppConfiguration.kt`);
dokumen ini menjelaskan angkanya. Aplikasi **Tokocrypto-only** - tidak ada tarif
bursa kedua.

> Dokumen ini bukan jaminan profit dan bukan bukti validasi strategi. Keputusan
> dan perhitungan trading berada di agent OMP; aplikasi hanya menyediakan angka
> biaya yang sama lewat `GET /api/v1/market/context` (field `fees`).

## 1. Prinsip

1. **Fee dan slippage dipisah.** Fee all-in adalah konstanta bursa; slippage
   adalah asumsi eksekusi yang perlu diuji per pair dan likuiditas.
2. **Beli dan jual tidak simetris pada pasangan IDR.** PPh final hanya dikenakan
   di sisi jual.
3. **Semua modul wajib memakai `FeeSchedule`.** Dilarang menulis angka fee
   sendiri di replay, UI, atau jurnal.
4. **Dilarang memakai asumsi lama tanpa label all-in** (misal model upstream
   `~0,72%` atau nominal `0,10%`).

## 2. Pasangan USDT / kripto-ke-kripto

Akun reguler, tanpa diskon TKO/VIP. Tarif all-in mencakup Trading Fee, PPh
Final, dan pungutan ICEx.

| Tipe order | Trading Fee | PPh Final | ICEx | Total per sisi |
|---|---:|---:|---:|---:|
| Maker (Limit) | 0,15% | 0,21% | 0,0444% | **0,4044%** |
| Taker (Market) | 0,15% | 0,21% | 0,0444% | **0,4044%** |

- Round-trip fee kotor: `0,4044% + 0,4044% = 0,8088%`.
- Maker dan taker all-in sama; tidak ada insentif maker dari sisi biaya.

## 3. Pasangan IDR (Rupiah)

| Arah | Tipe | Komponen | Total per sisi |
|---|---|---|---:|
| BELI | Maker | Trading fee 0,10% + ICEx 0,0222% (PPh beli 0%) | **0,1222%** |
| BELI | Taker | Trading fee 0,20% + ICEx 0,0222% | **0,2222%** |
| JUAL | Maker | Trading fee 0,10% + PPh 0,21% + ICEx 0,0222% | **0,3322%** |
| JUAL | Taker | Trading fee 0,20% + PPh 0,21% + ICEx 0,0222% | **0,4322%** |

- Round-trip taker kotor: `0,2222% + 0,4322% = 0,6544%`.
- Round-trip maker kotor: `0,1222% + 0,3322% = 0,4544%`.

## 4. Slippage

`0,08%` per sisi adalah **asumsi base case** (`FeeSchedule.DEFAULT_SLIPPAGE_PCT`),
bukan tarif resmi. Pair tipis atau order besar bisa jauh lebih tinggi.

| Skenario | Per sisi | Round-trip |
|---|---:|---:|
| Optimistis | 0,05% | 0,10% |
| Base case | 0,08% | 0,16% |
| Konservatif | 0,20% | 0,40% |
| Stress | 0,50%-1,00% | 1,00%-2,00% |

## 5. Ringkasan konstanta

| Pair | Mode | Fee round-trip | Slippage RT | Total friksi `F` |
|---|---|---:|---:|---:|
| Tokocrypto USDT | Maker/Taker | 0,8088% | 0,16% | **0,9688%** |
| Tokocrypto IDR | Taker | 0,6544% | 0,16% | **0,8144%** |
| Tokocrypto IDR | Maker | 0,4544% | 0,16% | **0,6144%** |

Rumus round-trip multiplikatif dipakai `FeeSchedule.roundTripPctMultiplicative()`;
rumus P&L net dipakai `FeeSchedule` sebagai satu-satunya implementasi.

## 6. Konstanta kode

| Konstanta | Nilai |
|---|---|
| `TOKOCRYPTO_USDT_BUY_TAKER` / `SELL_TAKER` | `0.004044` |
| `TOKOCRYPTO_IDR_BUY_TAKER` | `0.002222` |
| `TOKOCRYPTO_IDR_BUY_MAKER` | `0.001222` |
| `TOKOCRYPTO_IDR_SELL_TAKER` | `0.004322` |
| `TOKOCRYPTO_IDR_SELL_MAKER` | `0.003322` |
| `DEFAULT_SLIPPAGE_PCT` | `0.08` |

`TradingFeeConfig` menyimpan versi **persen** (`0.4044`, `0.2222`, dst.) lewat
`FeeSchedule.configFor()`. Penjaga konstanta ada di
`app/src/test/java/agu/analys/config/FeeTruthConstantsTest.kt`.

## 7. Perawatan

- Sumber tarif: halaman resmi Tokocrypto. Tarif dapat berubah karena promo,
  diskon TKO/VIP, perubahan ICEx, atau kebijakan bursa.
- Setiap perubahan tarif wajib menyelaraskan: tabel di dokumen ini, konstanta
  `FeeSchedule`, `TradingFeeConfig`, `AgentFeeSnapshot`, dan tes penjaga.
