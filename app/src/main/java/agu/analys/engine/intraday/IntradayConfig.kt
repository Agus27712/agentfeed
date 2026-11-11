package agu.analys.engine.intraday

import agu.analys.config.FeeSchedule

/**
 * Konfigurasi khusus mode **INTRADAY** (`StrategyMode.OFFICE_DAILY`).
 *
 * Kenapa terpisah dari [agu.analys.engine.scalping.ScalpingRuntimeConfig]:
 * - Config scalping diatur pengguna lewat **slider** dan JSON ekspor (`AUDIT-2026-11-08.md`).
 *   Mode intraday belum punya slider, jadi tidak boleh ikut menumpang config scalping — kalau
 *   menumpang, geseran satu slider scalping akan diam-diam mengubah sinyal intraday.
 * - Berkas ini **tidak** punya state global yang bisa diubah saat runtime (tidak ada `current`
 *   yang mutable). Semua nilai dioper eksplisit oleh pemanggil, sehingga replay dan app memakai
 *   angka yang sama tanpa jalur tersembunyi.
 *
 * Sejak paket C (28 Okt 2026), **geometri SL/TP hidup di sini** dan dihitung oleh
 * [IntradayGeometry.resolve] — satu definisi untuk evaluator dan checkpoint 6
 * `ConfluenceEvaluator`. Band persen lama di `IntradayEvaluator` (SL −1,5%…−2,8%,
 * TP1 +2,8%…+5,0%) dihapus karena tidak pernah memenuhi gate net R:R 1,5 pada biaya all-in
 * (net R:R ≈0,74–0,87) sehingga sinyal BUY intraday selalu nol.
 *
 * @property slippagePct asumsi slippage **per sisi** (%), default dari [FeeSchedule].
 * @property useMakerFee `false` = taker (bawaan, sesuai basis FSM: auto-buy/auto-exit adalah order
 *   market). `true` hanya untuk eksperimen eksplisit.
 * @property minNetRrTp1 ambang net R:R minimum yang **dihitung dari TP1** setelah fee + slippage.
 *   Keputusan owner (28 Okt 2026): ambang ini tetap 1,5, **tidak diturunkan** agar sinyal muncul.
 * @property flashDumpScanCandles jumlah candle terakhir yang dipindai untuk deteksi flash dump.
 * @property flashDumpTraumaCandles batas umur candle dump yang masih memblokir entry.
 * @property atrFloorPct / atrCapPct band volatilitas intraday sehat (% harga) — ATR di-clamp ke
 *   band ini sebelum dipakai geometri (perilaku lama `coerceIn(price*0.015, price*0.040)`).
 * @property slAtrMult jarak SL di bawah support = `slAtrMult × ATR` (bawaan 0,3, perilaku lama).
 * @property noSupportAtrMult jarak SL saat **tidak ada support** = `noSupportAtrMult × ATR`
 *   ditambah `slAtrMult × ATR` (bawaan 1,2 = pengganti literal lama di `IntradayGeometry`).
 *   ATR yang dipakai adalah ATR yang sudah di-clamp, sama dengan cabang support.
 * @property maxSlPct jarak SL maksimum dari harga (%). `minSlFrictionMultiple × impas net` bisa
 *   lebih besar dan ikut menentukan — SL tidak boleh lebih rapat dari beberapa kali biaya.
 * @property minSlPct jarak SL minimum (%): SL tidak boleh lebih rapat dari ini.
 * @property tp1AtrMult TP1 berbasis ATR = `price + tp1AtrMult × ATR`; bila hasilnya di bawah
 *   TP1-dari-R, TP1 ikut dinaikkan ke level R (lihat [IntradayGeometry]).
 * @property maxTp1Pct batas atas TP1 (% harga) — bersama resistance, inilah yang membuat gate
 *   masih bisa **gagal jujur** ketika ruang tidak cukup; tanpa itu TP1-dari-R membuat gate
 *   tautologis. Bawaan **5,0** = batas atas band TP1 lama. Nilai **8,0** yang sempat dipakai di
 *   commit `f6faf90` **belum disetujui owner** (review 28 Okt 2026: itu melonggarkan gate lewat
 *   pintu lain, praktis menjadi penentu lolos/tidak, dan baru diukur pada 11 pair satu window).
 *   Jangan menaikkannya tanpa keputusan owner + pengukuran di data lebih banyak.
 * @property tp2AtrMult / minTp2Pct / maxTp2Pct band TP2 (bawaan 1,4 ATR; +5,5%…+12%).
 * @property macdHistMinPct ambang histogram MACD yang **dinormalisasi ke % harga**
 *   (`macdHist / price × 100`). Unit lama (`macdHist >= -0,002`, satuan harga) praktis berarti
 *   "histogram tidak negatif" dan tidak berskala antar koin. Bawaan 0,0 = setara perilaku lama
 *   untuk koin berharga besar; alternatifnya diukur, tidak diubah diam-diam (arahan owner 28 Okt).
 */
data class IntradayConfig(
    val slippagePct: Double = FeeSchedule.DEFAULT_SLIPPAGE_PCT,
    val useMakerFee: Boolean = false,
    val minNetRrTp1: Double = 1.5,
    val flashDumpScanCandles: Int = 18,
    val flashDumpTraumaCandles: Int = 12,
    val atrFloorPct: Double = 1.5,
    val atrCapPct: Double = 4.0,
    val slAtrMult: Double = 0.3,
    val noSupportAtrMult: Double = 1.2,
    val maxSlPct: Double = 2.8,
    val minSlPct: Double = 1.5,
    val minSlFrictionMultiple: Double = 2.0,
    val tp1AtrMult: Double = 1.25,
    val maxTp1Pct: Double = 5.0,
    val tp2AtrMult: Double = 1.4,
    val minTp2Pct: Double = 5.5,
    val maxTp2Pct: Double = 12.0,
    val macdHistMinPct: Double = 0.0
) {
    companion object {
        val DEFAULT = IntradayConfig()
    }
}
