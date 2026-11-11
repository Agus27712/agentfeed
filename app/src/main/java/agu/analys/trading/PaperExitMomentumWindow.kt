package agu.analys.trading

import agu.analys.model.CandleBar

/**
 * Satu sumber jendela momentum untuk mesin exit paper — **P6**.
 *
 * ## Kenapa kelas ini ada
 *
 * `evaluatePaperAutoExit` dipanggil dari **dua** tempat dengan jendela yang berbeda:
 *
 * ```kotlin
 * // TradingViewModel.kt — pair SEDANG DIBUKA: jendela momentum dikirim
 * runCatching { evaluatePaperAutoExit(symbol, currentEx, price, recentCandles.takeLast(15)) }
 *
 * // TradingViewModelSync.kt — posisi di LATAR: jendela momentum kosong
 * runCatching { evaluatePaperAutoExit(tick.symbol, currentEx, tick.price) }
 * ```
 *
 * `EARLY_PROFIT` hanya bisa terjadi bila jendela tidak kosong
 * (`PaperExitResolver.onBar:635` — `window14.isNotEmpty()`), jadi:
 *
 * - trade yang sama **yang kebetulan dibuka pair-nya** bisa keluar sebagai `EARLY_PROFIT`,
 * - trade yang sama **yang tidak dibuka** keluar sebagai `TP2`.
 *
 * Journal lalu berisi dua alasan berbeda untuk keputusan yang seharusnya identik, dan app tidak
 * sebanding dengan `AUDIT-2026-11-08.md` — padahal paritas itulah alasan jurnal ini ada.
 *
 * ## Aturan yang ditegakkan di sini
 *
 * **Jendela datang dari satu tempat: cache M1 `MtfCacheManager`**, untuk kedua pemanggil. Bukan
 * dari `recentCandles` milik layar yang sedang terbuka. Akibatnya keputusan exit tidak lagi
 * bergantung pada pair mana yang kebetulan pengguna buka.
 *
 * Ambang kesegarannya **mengikuti `MtfCacheManager.isCacheValid(Timeframe.M1)`** — 20 bar
 * minimum, umur maksimal 180 detik — supaya tidak ada dua definisi "M1 masih segar" dalam satu
 * aplikasi.
 */
object PaperExitMomentumWindow {

    /** Jumlah bar yang diteruskan ke mesin exit; sama dengan jendela replay (maks 15). */
    const val MAX_BARS = 15

    /** Minimum bar M1 — sama dengan syarat `MtfCacheManager.isCacheValid`. */
    const val M1_MIN_BARS = 20

    /** Umur maksimal bar M1 terakhir — sama dengan `MtfCacheManager.isCacheValid(M1)`. */
    const val M1_MAX_AGE_MS = 180_000L

    /**
     * Konversi stempel waktu bar ke milidetik. Ada dua konvensi di aplikasi ini: detik dan
     * milidetik; yang dipakai penulis data candle tidak seragam, jadi tebak dari besarnya nilai.
     */
    fun toMillis(timestamp: Long): Long =
        if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp

    /**
     * **P6** — true bila cache M1 ini masih layak dipakai sebagai jendela momentum.
     * Kosong, kurang dari [M1_MIN_BARS], atau lebih tua dari [M1_MAX_AGE_MS] ⇒ tidak layak.
     */
    fun isFresh(candles: List<CandleBar>?, nowMs: Long): Boolean {
        if (candles.isNullOrEmpty() || candles.size < M1_MIN_BARS) return false
        val ageMs = nowMs - toMillis(candles.last().timestamp)
        return ageMs <= M1_MAX_AGE_MS
    }

    /**
     * **P6** — jendela momentum untuk [symbol], dari sumber yang sama untuk **semua** pemanggil.
     * Mengembalikan daftar kosong bila cache tidak segar; mesin exit lalu menahan diri dari
     * early-profit, persis seperti perlakuannya selama ini di jalur latar.
     */
    fun resolve(candles: List<CandleBar>?, nowMs: Long): List<CandleBar> {
        val fresh = candles?.takeIf { isFresh(it, nowMs) } ?: return emptyList()
        return fresh.takeLast(MAX_BARS)
    }
}
