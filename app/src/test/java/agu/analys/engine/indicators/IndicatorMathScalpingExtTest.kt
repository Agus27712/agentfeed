package agu.analys.engine.indicators

import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorMathScalpingExtTest {

    private fun candle(
        close: Double,
        volume: Double = 1000.0,
        high: Double = close * 1.002,
        low: Double = close * 0.998,
        open: Double = close,
        t: Long = System.currentTimeMillis()
    ) = CandleBar(t, open, high, low, close, volume)

    @Test
    fun relativeVolume_returnsOneWhenInsufficientData() {
        val candles = listOf(candle(100.0, 500.0))
        assertEquals(1.0, IndicatorMath.relativeVolume(candles, 20), 1e-9)
    }

    @Test
    fun relativeVolume_highWhenLastVolumeSpike() {
        val hist = (1..20).map { i ->
            candle(100.0 + i, volume = 1000.0, t = i * 60_000L)
        }
        val spike = candle(120.0, volume = 3000.0, t = 21 * 60_000L)
        val rvol = IndicatorMath.relativeVolume(hist + spike, 20)
        assertTrue("RVOL should be ~3x, was $rvol", rvol in 2.5..3.5)
    }

    @Test
    fun relativeVolume_lowWhenQuiet() {
        val hist = (1..20).map { i ->
            candle(100.0, volume = 2000.0, t = i * 60_000L)
        }
        val quiet = candle(100.0, volume = 500.0, t = 21 * 60_000L)
        val rvol = IndicatorMath.relativeVolume(hist + quiet, 20)
        assertTrue("RVOL should be ~0.25, was $rvol", rvol in 0.15..0.4)
    }

    // ─── P0-1: basis volume RVOL harus setara bar penuh ────────────────────────────

    @Test
    fun elapsedFraction_isClampedToSafeRange() {
        assertEquals(1.0, IndicatorMath.elapsedFraction(0L, 60_000L, 30_000L), 1e-9)
        assertEquals(1.0, IndicatorMath.elapsedFraction(60_000L, 0L, 90_000L), 1e-9)
        assertEquals(1.0, IndicatorMath.elapsedFraction(60_000L, 60_000L, 150_000L), 1e-9)
        assertEquals(0.05, IndicatorMath.elapsedFraction(60_000L, 60_000L, 60_000L), 1e-9)
        assertEquals(0.5, IndicatorMath.elapsedFraction(60_000L, 60_000L, 90_000L), 1e-9)
    }

    @Test
    fun projectVolumeToFullBar_scalesByElapsedFraction() {
        assertEquals(0.0, IndicatorMath.projectVolumeToFullBar(0.0, 0.5), 1e-9)
        assertEquals(600.0, IndicatorMath.projectVolumeToFullBar(600.0, 1.0), 1e-9)
        assertEquals(1200.0, IndicatorMath.projectVolumeToFullBar(600.0, 0.5), 1e-9)
        // fraksi mendekati nol di-clamp ke 0.05 agar tidak meledak
        assertEquals(600.0 / 0.05, IndicatorMath.projectVolumeToFullBar(600.0, 0.0), 1e-9)
    }

    @Test
    fun relativeVolume_projectsFormingBarToFullBarEquivalent() {
        val hist = (1..20).map { i -> candle(100.0, volume = 1000.0, t = i * 60_000L) }
        val barStart = 21 * 60_000L
        // Bar berjalan baru 30 detik (50%) dan sudah mengumpulkan 600 → setara 1200 → RVOL 1,2x.
        val forming = CandleBar(barStart, 100.0, 100.2, 99.8, 100.0, 600.0, isClosed = false)
        val rvol = IndicatorMath.relativeVolume(hist + forming, 20, 60_000L, barStart + 30_000L)
        assertEquals("RVOL harus diproyeksikan ke 1.2x, bukan 0.6x", 1.2, rvol, 1e-6)
    }

    @Test
    fun relativeVolume_closedLastBarIsNotProjected() {
        val hist = (1..20).map { i -> candle(100.0, volume = 1000.0, t = i * 60_000L) }
        val last = candle(100.0, volume = 3000.0, t = 21 * 60_000L) // isClosed = true
        val rvol = IndicatorMath.relativeVolume(hist + last, 20, 60_000L, 21 * 60_000L + 5_000L)
        assertEquals(3.0, rvol, 1e-6)
    }

    @Test
    fun relativeVolume_regressionGnsPartialMinuteNoLongerUnderstated() {
        // Reproduksi kasus nyata GNS/USDT (13 Okt 23:58 WIB):
        //   • bar M1 yang sedang berjalan baru 52% waktu (31,2 detik)
        //   • sudah mengumpulkan 30.215 volume (bukan 58.106 — itu volume akhir bar)
        //   • 20 bar M1 yang sudah tutup rata-rata 39.818
        // Nilai lama (tanpa proyeksi) = 30.215 / 39.818 = 0,76x → di bawah gate RVOL 1,2x,
        // sehingga breakout sehat salah dicap "thin volume fake breakout".
        // Nilai benar = (30.215 / 0,52) / 39.818 ≈ 1,46x → lolos gate.
        val closedAvg = 39_818.0
        val runningVol = 30_215.0
        val hist = (1..20).map { i -> candle(100.0, volume = closedAvg, t = i * 60_000L) }
        val barStart = 21 * 60_000L
        val forming = CandleBar(barStart, 0.718, 0.739, 0.715, 0.739, runningVol, isClosed = false)

        val oldStyle = runningVol / closedAvg
        val fixed = IndicatorMath.relativeVolume(hist + forming, 20, 60_000L, barStart + 31_200L)

        assertTrue("nilai lama harus ~0.76x, was $oldStyle", oldStyle in 0.70..0.80)
        assertTrue("nilai baru harus ~1.46x, was $fixed", fixed in 1.40..1.50)
        assertTrue("nilai baru harus lolos gate RVOL 1.2x, was $fixed", fixed > 1.2)
    }

    @Test
    fun relativeVolume_denominatorIgnoresFormingBar() {
        // Bar berjalan punya volume raksasa; ia TIDAK boleh ikut membentuk rata-rata
        // (kalau ikut, RVOL akan saling meniadakan dan selalu mendekati 1x).
        val hist = (1..20).map { i -> candle(100.0, volume = 1000.0, t = i * 60_000L) }
        val barStart = 21 * 60_000L
        val forming = CandleBar(barStart, 100.0, 100.0, 100.0, 100.0, 500.0, isClosed = false)
        val rvol = IndicatorMath.relativeVolume(hist + forming, 20, 60_000L, barStart + 60_000L)
        assertEquals("denominator harus tetap 1000 → 0.5x", 0.5, rvol, 1e-6)
    }

    @Test
    fun choppiness_inValidRange() {
        val candles = (1..40).map { i ->
            val base = 100.0 + (i % 5) * 0.3
            candle(base, high = base + 0.5, low = base - 0.5, t = i * 60_000L)
        }
        val chop = IndicatorMath.choppiness(candles, 14)
        assertTrue("CHOP must be 0..100, was $chop", chop in 0.0..100.0)
    }

    @Test
    fun choppiness_higherOnSidewaysThanStrongTrend() {
        // Sideways noise
        val sideways = (1..50).map { i ->
            val base = 100.0 + kotlin.math.sin(i / 2.0) * 0.4
            candle(base, high = base + 0.3, low = base - 0.3, volume = 1000.0, t = i * 60_000L)
        }
        // Strong uptrend
        val trend = (1..50).map { i ->
            val base = 100.0 + i * 1.5
            candle(base, high = base + 0.2, low = base - 0.1, volume = 1000.0, t = i * 60_000L)
        }
        val chopSide = IndicatorMath.choppiness(sideways, 14)
        val chopTrend = IndicatorMath.choppiness(trend, 14)
        assertTrue(
            "Sideways CHOP ($chopSide) should be >= trend CHOP ($chopTrend)",
            chopSide >= chopTrend - 5.0
        )
    }

    @Test
    fun adx_inValidRangeOnTrendingSeries() {
        val trend = (1..80).map { i ->
            val base = 1000.0 + i * 2.0
            candle(base, high = base + 1.0, low = base - 0.5, volume = 1500.0, t = i * 60_000L)
        }
        val adx = IndicatorMath.adx(trend, 14)
        assertTrue("ADX must be 0..100, was $adx", adx in 0.0..100.0)
    }

    @Test
    fun adx_returnsZeroOrLowWhenInsufficientBars() {
        val few = (1..10).map { i -> candle(100.0 + i, t = i * 60_000L) }
        val adx = IndicatorMath.adx(few, 14)
        assertTrue("ADX with few bars should be low, was $adx", adx in 0.0..100.0)
    }
}
