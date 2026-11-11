package agu.analys.engine.regime

import agu.analys.model.CandleBar
import agu.analys.model.MarketRegime
import agu.analys.model.StructureBias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRegimeEngineTest {

    private fun candle(
        close: Double,
        volume: Double = 1000.0,
        high: Double = close * 1.001,
        low: Double = close * 0.999,
        open: Double = close,
        t: Long
    ) = CandleBar(t, open, high, low, close, volume)

    /** Strong monotonic uptrend with expanding range participation. */
    private fun trendingUpCandles(n: Int = 80): List<CandleBar> =
        (1..n).map { i ->
            val base = 50_000.0 + i * 80.0 // USDT-like scale OK; also works for IDR scale
            candle(
                close = base,
                high = base + 40.0,
                low = base - 20.0,
                volume = 2000.0 + i * 10.0,
                t = i * 60_000L
            )
        }

    private fun trendingDownCandles(n: Int = 80): List<CandleBar> =
        (1..n).map { i ->
            val base = 50_000.0 - i * 80.0
            candle(
                close = base,
                high = base + 20.0,
                low = base - 40.0,
                volume = 2000.0,
                t = i * 60_000L
            )
        }

    private fun rangingCandles(n: Int = 80): List<CandleBar> =
        (1..n).map { i ->
            val base = 50_000.0 + kotlin.math.sin(i / 3.0) * 30.0
            candle(
                close = base,
                high = base + 15.0,
                low = base - 15.0,
                volume = 1000.0,
                t = i * 60_000L
            )
        }

    @Test
    fun insufficientData_returnsRanging() {
        val few = (1..10).map { i -> candle(100.0, t = i * 60_000L) }
        val snap = MarketRegimeEngine.detect(few)
        assertEquals(MarketRegime.RANGING, snap.regime)
    }

    @Test
    fun strongUptrend_prefersTrendingUp() {
        val candles = trendingUpCandles()
        val snap = MarketRegimeEngine.detect(
            candles = candles,
            structureBias = StructureBias.BULLISH,
            rvol = 1.2
        )
        assertTrue(
            "Expected TRENDING_UP or BREAKOUT/HIGH_VOL, got ${snap.regime} | ${snap.explanation}",
            snap.regime == MarketRegime.TRENDING_UP ||
                snap.regime == MarketRegime.BREAKOUT ||
                snap.regime == MarketRegime.HIGH_VOLATILITY
        )
        assertTrue(snap.adx >= 0.0)
        assertTrue(snap.chop in 0.0..100.0)
    }

    @Test
    fun strongDowntrend_prefersTrendingDown() {
        val candles = trendingDownCandles()
        val snap = MarketRegimeEngine.detect(
            candles = candles,
            structureBias = StructureBias.BEARISH,
            rvol = 1.1
        )
        assertTrue(
            "Expected TRENDING_DOWN or HIGH_VOL, got ${snap.regime} | ${snap.explanation}",
            snap.regime == MarketRegime.TRENDING_DOWN ||
                snap.regime == MarketRegime.HIGH_VOLATILITY ||
                snap.regime == MarketRegime.BREAKOUT
        )
    }

    @Test
    fun sideways_prefersRangingOrLowVol() {
        val candles = rangingCandles()
        val snap = MarketRegimeEngine.detect(
            candles = candles,
            structureBias = StructureBias.NEUTRAL,
            rvol = 0.9
        )
        assertTrue(
            "Expected RANGING or LOW_VOLATILITY, got ${snap.regime} | ${snap.explanation}",
            snap.regime == MarketRegime.RANGING ||
                snap.regime == MarketRegime.LOW_VOLATILITY
        )
    }

    @Test
    fun breakoutFlag_withHighRvol_canBeBreakout() {
        val candles = trendingUpCandles(60)
        val snap = MarketRegimeEngine.detect(
            candles = candles,
            structureBias = StructureBias.BULLISH,
            rvol = 2.2,
            resistanceBroken = true
        )
        // Breakout takes priority when level broken + RVOL high (unless extreme ATR first)
        assertTrue(
            "Got ${snap.regime}: ${snap.explanation}",
            snap.regime == MarketRegime.BREAKOUT ||
                snap.regime == MarketRegime.TRENDING_UP ||
                snap.regime == MarketRegime.HIGH_VOLATILITY
        )
    }

    @Test
    fun worksOnIdrScalePrices() {
        // Bitcoin IDR-scale synthetic (~1e9)
        val candles = (1..60).map { i ->
            val base = 1_500_000_000.0 + i * 500_000.0
            candle(base, high = base * 1.001, low = base * 0.999, volume = 5.0, t = i * 60_000L)
        }
        val snap = MarketRegimeEngine.detect(candles, structureBias = StructureBias.BULLISH)
        assertTrue(snap.atrPct >= 0.0)
        assertTrue(snap.explanation.isNotBlank())
    }

    /**
     * P0 — regime harus memakai ambang dari runtime config, bukan literal di engine.
     * Dengan ADX trending 1000, data yang tadinya TRENDING_UP tidak boleh lagi disebut tren.
     */
    @Test
    fun thresholdsFromRuntimeConfig_overrideTrendClassification() {
        val candles = trendingUpCandles()
        val normal = MarketRegimeEngine.detect(
            candles = candles,
            structureBias = StructureBias.BULLISH,
            rvol = 1.2
        )
        assertEquals(MarketRegime.TRENDING_UP, normal.regime)

        val strict = MarketRegimeEngine.detect(
            candles = candles,
            structureBias = StructureBias.BULLISH,
            rvol = 1.2,
            thresholds = MarketRegimeEngine.Thresholds(
                adxTrending = 1_000.0,
                adxWeak = 1_000.0,
                chopRanging = 101.0,
                chopTrendMax = 101.0
            )
        )
        assertEquals("ADX 1000 tidak mungkin tercapai", MarketRegime.RANGING, strict.regime)
    }

    @Test
    fun thresholdsFromScalpingConfig_mapsEveryField() {
        val cfg = agu.analys.engine.scalping.ScalpingRuntimeConfig(
            regimeAdxTrending = 30.0,
            regimeAdxWeak = 22.0,
            regimeChopRanging = 64.0,
            regimeChopTrendMax = 50.0
        )
        val thresholds = MarketRegimeEngine.Thresholds.from(cfg)
        assertEquals(30.0, thresholds.adxTrending, 0.0001)
        assertEquals(22.0, thresholds.adxWeak, 0.0001)
        assertEquals(64.0, thresholds.chopRanging, 0.0001)
        assertEquals(50.0, thresholds.chopTrendMax, 0.0001)
    }
}
