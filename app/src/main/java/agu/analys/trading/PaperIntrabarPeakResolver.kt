package agu.analys.trading

import agu.analys.model.CandleBar

/**
 * **P6 (bagian kedua)** — high intrabar M1 untuk `peak` dan level atas mesin exit paper.
 *
 * ## Kenapa kelas ini ada
 *
 * Kedua pemanggil [agu.analys.viewmodel.evaluatePaperAutoExit] menilai bar **sintetis dari tick**:
 * `open = high = low = close = currentPrice`, `volume = 0.0`. Akibatnya `peak = max(peak, bar.high)`
 * tidak pernah bisa melewati harga tick, walau candle M1 mencatat high yang jauh lebih tinggi di
 * menit yang sama. Trailing SL jalur latar jadi lebih longgar daripada hasil
 * `AUDIT-2026-11-08.md` — padahal paritas app ↔ replay adalah alasan utama jurnal ini ada.
 *
 * ## Aturan yang ditegakkan di sini
 *
 * - Cache M1 harus **segar** menurut [PaperExitMomentumWindow.isFresh] — ambang yang sama dengan
 *   jendela momentum (≥ [PaperExitMomentumWindow.M1_MIN_BARS] bar, umur ≤
 *   [PaperExitMomentumWindow.M1_MAX_AGE_MS]). Kalau tidak, `currentPrice` dikembalikan apa adanya.
 * - Hanya candle yang **dibuka setelah** posisi dibuka yang dihitung: `timestamp` ketat
 *   > [openedAtMs]. Bar tempat entry terjadi sengaja dilewati, sama seperti replay yang mulai
 *   menilai dari bar `i+1`.
 * - High non-finite diabaikan; hasilnya **tidak pernah** di bawah `currentPrice`.
 *
 * Fungsi ini murni — tanpa Android — supaya bisa diuji langsung.
 */
object PaperIntrabarPeakResolver {

    /**
     * High efektif untuk evaluasi bar sintetis pada satu tick.
     *
     * @param candles cache M1 (boleh `null`/tidak segar — hasilnya `currentPrice`)
     * @param openedAtMs waktu buka posisi (ms, dari `PaperAutoTradeRuntime.openedAt`); `<= 0`
     *   berarti tidak diketahui dan tidak ada candle yang boleh dipercaya
     * @param nowMs waktu evaluasi, untuk cek kesegaran cache
     * @param currentPrice harga tick saat ini; nilai tidak valid dikembalikan apa adanya
     * @return `max(currentPrice, high candle eligible)` — atau `currentPrice` bila tidak ada
     *   yang layak dipakai
     */
    fun effectiveHigh(
        candles: List<CandleBar>?,
        openedAtMs: Long,
        nowMs: Long,
        currentPrice: Double
    ): Double {
        if (!currentPrice.isFinite() || currentPrice <= 0.0) return currentPrice
        if (openedAtMs <= 0L) return currentPrice

        val fresh = candles?.takeIf { PaperExitMomentumWindow.isFresh(it, nowMs) } ?: return currentPrice

        var best = currentPrice
        for (candle in fresh) {
            if (PaperExitMomentumWindow.toMillis(candle.timestamp) <= openedAtMs) continue
            val high = candle.high
            if (high.isFinite() && high > best) best = high
        }
        return best
    }
}
