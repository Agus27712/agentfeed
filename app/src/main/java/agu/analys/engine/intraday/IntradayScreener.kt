package agu.analys.engine.intraday

import agu.analys.model.MarketTick
import java.time.Instant
import java.time.ZoneOffset

data class FastIntradayScore(
    val score: Int,
    val isQualified: Boolean,
    val summary: String,
    /** true jika sekarang di jendela screening pagi (~daily close) */
    val inScreeningWindow: Boolean = false
)

/**
 * Fast screen Intraday — diselaraskan strategi trading harian:
 * - Screening ideal ~jam 7 pagi WIB (setelah daily close)
 * - Cari koin yang SUDAH dalam tren naik / pullback sehat (bukan recovery dump)
 * - Trigger mindset: lanjut tren + candle bullish (detail di evaluator)
 * - Setup tidak harus ada setiap hari → filter ketat
 */
object IntradayScreener {

    /** Offset WIB (UTC+7) — konstan, tanpa DST. */
    private val WIB_OFFSET: ZoneOffset = ZoneOffset.ofHours(7)

    /**
     * Jendela screening pagi WIB (setelah daily close crypto ~07:00).
     * Aritmetika ZoneOffset — jauh lebih murah dari Calendar.getInstance
     * (yang me-load timezone data) dan dipanggil per kartu per tick.
     */
    fun isMorningScreeningWindow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val hourWib = ((nowMs / 3_600_000L) + 7).mod(24L).toInt()
        // 06:00–09:30 WIB = zona screening utama
        return hourWib in 6..9
    }

    fun evaluateFast(tick: MarketTick?): FastIntradayScore {
        if (tick == null || tick.price <= 0.0) {
            return FastIntradayScore(0, false, "Data tidak valid")
        }

        val change = if (tick.change24h.isFinite()) tick.change24h else 0.0
        val volume = tick.volume24h
        val price = tick.price
        val high = tick.high24h
        val low = tick.low24h
        val rangePct = if (low > 0.0) ((high - low) / low) * 100.0 else 0.0
        val posInRange = if (high > low) ((price - low) / (high - low)).coerceIn(0.0, 1.0) else 0.5
        val inWindow = isMorningScreeningWindow()

        // ── Hard reject (bukan gaya Intraday) ─────────────────────
        // Dump tajam / recovery dari panic sell → bukan "yang sudah naik lanjut naik"
        if (change < -4.0) {
            return FastIntradayScore(
                score = 0,
                isQualified = false,
                summary = "❌ Bukan Intraday (tekanan 24h ${fmt(change)}%)",
                inScreeningWindow = inWindow
            )
        }
        // Volume terlalu kering → slippage / sulit dieksekusi santai
        if (volume < 300_000_000.0) {
            return FastIntradayScore(
                score = 0,
                isQualified = false,
                summary = "❌ Likuiditas rendah untuk Intraday",
                inScreeningWindow = inWindow
            )
        }
        // Range liar (noise ekstrem) → bukan setup santai
        if (rangePct > 28.0) {
            return FastIntradayScore(
                score = 0,
                isQualified = false,
                summary = "❌ Range 24h terlalu liar (${fmt(rangePct)}%)",
                inScreeningWindow = inWindow
            )
        }

        var score = 0

        // 1. Likuiditas (IDR volume)
        when {
            volume >= 15_000_000_000.0 -> score += 4
            volume >= 5_000_000_000.0 -> score += 3
            volume >= 1_500_000_000.0 -> score += 2
            volume >= 300_000_000.0 -> score += 1
        }

        // 2. Karakter pergerakan 24h — prefer tren naik / pullback ringan
        // Filosofi: "yang sudah naik, akan terus naik" + bukan dump recovery
        when {
            change in 2.0..12.0 -> score += 5          // tren naik sehat
            change in 0.0..2.0 -> score += 4           // konsolidasi di atas
            change in -2.0..0.0 -> score += 2          // pullback ringan (masih oke)
            change in 12.0..20.0 -> score += 2         // sudah extended, hati-hati
            change in -4.0..-2.0 -> score += 0         // lemah, hampir ditolak hard gate
            else -> score += 0
        }

        // 3. Posisi di range 24h
        // Dump recovery = harga nempel low → tolak
        // Ideal Intraday: mid–upper setelah strength, atau pullback terkontrol
        when {
            posInRange >= 0.35 && posInRange <= 0.85 && change >= 0.0 -> score += 3
            posInRange >= 0.25 && posInRange <= 0.70 && change in -2.0..2.0 -> score += 2 // pullback sehat
            posInRange < 0.20 && change < 0.0 -> score -= 3 // nempel floor setelah turun
            posInRange > 0.92 && change >= 10.0 -> score -= 1 // kejepit di top parabolic
            else -> score += 1
        }

        // 4. Volatilitas terukur (bukan dead, bukan crazy)
        when {
            rangePct in 3.0..14.0 -> score += 2
            rangePct in 2.0..20.0 -> score += 1
            else -> score += 0
        }

        // 5. Soft boost jendela screening pagi (7 WIB / daily close)
        if (inWindow) score += 1

        // Threshold ketat: setup tidak setiap hari
        val isCandidate = score >= 9

        val summary = when {
            isCandidate && score >= 12 && inWindow ->
                "⚡ Intraday READY (pagi · skor $score)"
            isCandidate && score >= 12 ->
                "⚡ High Conviction Intraday (skor $score)"
            isCandidate && inWindow ->
                "⚡ Potensi Intraday · screening pagi (skor $score)"
            isCandidate ->
                "⚡ Potensi Intraday (tren sehat · skor $score)"
            score >= 7 ->
                "👀 Pantau Intraday (belum qualify · skor $score)"
            else ->
                "— Belum cocok Intraday"
        }

        return FastIntradayScore(
            score = score,
            isQualified = isCandidate,
            summary = summary,
            inScreeningWindow = inWindow
        )
    }

    private fun fmt(v: Double) = agu.analys.util.PriceFormatter.fmt(v, 1)
}
