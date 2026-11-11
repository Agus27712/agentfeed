package agu.analys.engine.swing

import agu.analys.config.FeeSchedule

/**
 * Konfigurasi mode **SWING** (`StrategyMode.SWING`).
 *
 * Kenapa terpisah dari config mode lain: `app/AGENTS.md` §79.1 melarang parameter satu mode
 * menumpang config mode lain — geseran satu slider SCALPING/INTRADAY tidak boleh diam-diam
 * mengubah sinyal SWING, demikian pula sebaliknya.
 *
 * **Asal nilai.** Semua bawaan di bawah ini **adalah literal yang sebelumnya berdiri langsung di
 * badan `SwingEvaluator`** (dipindahkan apa adanya, tanpa mengubah satu angka pun). Dengan begitu,
 * `SwingConfig.DEFAULT` = perilaku engine SWING sebelum slider ada; uji
 * `SwingEvaluatorGoldenTest` mengunci kesetaraan itu.
 *
 * Satuan yang dipakai konsisten dengan mode lain:
 * - field ber-akhiran `…Pct` = **persen harga** (1,2 berarti 1,2%);
 * - field ber-akhiran `…Atr` / `…Ratio` / `…Score` = kelipatan/skor biasa.
 *
 * Yang **sengaja belum** dipindahkan ke config (dicatat sebagai batas, lihat `AUDIT-2026-11-08.md` §90):
 * periode indikator (RSI 14, EMA 20/50, MACD 12/26/9, BB 20, ATR 14), jendela volume rata-rata
 * 8 candle, momentum 10 candle, bobot skor tiap komponen, toleransi mikro deteksi setup,
 * `minCheckpointScore` (38), `heavySellScore` (42), `retestBodyRatioMin` (0,35),
 * `wickToBodyMax` (0,55), lookback recent-high 15, fallback support/resistance 1,6/2,0 ATR,
 * lantai TP1 7% / TP2 16% / jarak TP1→TP2 8%, dan variasi SL per setup breakout/tanpa-setup.
 *
 * @property minNetRr ambang net R:R minimum untuk gerbang masuk. Bawaan 2,0 — literal lama.
 *   Gerbang memakai **net R:R mentah** (angka tampilan tetap dibawahkan ke 1,4 seperti dulu),
 *   supaya slider di bawah 1,4 benar-benar berlaku pada keputusan, bukan dekorasi.
 * @property minEntryScore skor akumulasi `buy` minimum pada keputusan akhir. Bawaan 42 — literal lama.
 * @property minConfluenceCheckpoints jumlah checkpoint konfluensi (dari 6) yang wajib lolos agar
 *   BUY dikwalifikasi. Bawaan 4 — literal lama.
 * @property minBuySellRatio tekanan beli minimum relatif terhadap tekanan jual, diterapkan dua kali
 *   (gerbang berat jual dan gerbang akhir). Bawaan 1,15 — literal lama.
 * @property overboughtRsi ambang RSI yang menyatakan koin jenuh beli; memblokir entry dan
 *   menaikkan skor `sell`. Bawaan 72 — literal lama.
 * @property maxAtrExtension ekstensi harga dari rata-rata, dalam kelipatan ATR, yang masih boleh
 *   dianggap belum overextended. Bawaan 1,8 — literal lama.
 * @property strongVolumeRatio rasio volume terhadap rata-rata yang menyatakan "volume kuat".
 *   Bawaan 1,25 — literal lama.
 * @property nearLevelPct jarak harga ke support/resistance (persen harga) yang masih dianggap
 *   "di level". Bawaan 1,2 — literal lama (0,012).
 * @property rejectionBodyRatioMin rasio badan candle minimum untuk setup REJECTION. Bawaan 0,45 —
 *   literal lama.
 * @property breakoutBodyRatioMin rasio badan candle minimum untuk setup BREAKOUT/BREAKDOWN solid.
 *   Bawaan 0,40 — literal lama.
 * @property maxDistToHighPct jarak harga ke recent high (persen) yang masih dianggap "terlalu dekat
 *   pucuk". Bawaan 1,2 — literal lama (0,012).
 * @property slBufferAtrRejection jarak stop loss di bawah low/level pada cabang
 *   REJECTION/RETEST/RECLAIM, dalam kelipatan ATR. Bawaan 0,20 — literal lama. Cabang
 *   breakout/tanpa-setup memakai nilai internalnya sendiri (lihat KDoc atas).
 * @property slAtrMaxRejection jarak SL maksimum dari harga pada cabang yang sama, dalam kelipatan
 *   ATR. Bawaan 1,4 — literal lama.
 * @property slMaxDistancePct jarak SL maksimum dari harga (persen) pada cabang yang sama
 *   (dulu `price * 0.93` = 7%). Bawaan 7,0 — literal lama.
 * @property slMinDistancePct jarak SL minimum dari harga (persen) pada cabang yang sama — SL tidak
 *   boleh lebih rapat dari ini (dulu `price * 0.985`). Bawaan 1,5 — literal lama.
 * @property tp1AtrMult jarak TP1 dari harga dalam kelipatan ATR (dulu 2,0). Bawaan 2,0 — literal lama.
 * @property tp2AtrMult jarak TP2 dari harga dalam kelipatan ATR (dulu 3,4). Bawaan 3,4 — literal lama.
 * @property slippagePct asumsi slippage **per sisi** (%) untuk perhitungan net R:R SWING. Bawaan
 *   [FeeSchedule.DEFAULT_SLIPPAGE_PCT] (0,08) — sama dengan bawaan `FeeCalculator.roundTrip` lama.
 *   Hanya milik perhitungan sinyal SWING; TIDAK disalurkan ke basis P&L simulasi.
 */
data class SwingConfig(
    val minNetRr: Double = 2.0,
    val minEntryScore: Double = 42.0,
    val minConfluenceCheckpoints: Int = 4,
    val minBuySellRatio: Double = 1.15,
    val overboughtRsi: Double = 72.0,
    val maxAtrExtension: Double = 1.8,
    val strongVolumeRatio: Double = 1.25,
    val nearLevelPct: Double = 1.2,
    val rejectionBodyRatioMin: Double = 0.45,
    val breakoutBodyRatioMin: Double = 0.40,
    val maxDistToHighPct: Double = 1.2,
    val slBufferAtrRejection: Double = 0.20,
    val slAtrMaxRejection: Double = 1.4,
    val slMaxDistancePct: Double = 7.0,
    val slMinDistancePct: Double = 1.5,
    val tp1AtrMult: Double = 2.0,
    val tp2AtrMult: Double = 3.4,
    val slippagePct: Double = FeeSchedule.DEFAULT_SLIPPAGE_PCT
) {
    companion object {
        /** Bawaan = semua literal `SwingEvaluator` lama, apa adanya. */
        val DEFAULT = SwingConfig()
    }
}
