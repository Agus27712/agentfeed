package agu.analys.engine.scalping

import agu.analys.model.CandleBar
import agu.analys.model.MarketRegime
import agu.analys.model.RegimeSnapshot
import agu.analys.model.ScalpSetupType
import agu.analys.model.StructureBias
import agu.analys.model.StructureSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpSetupDetectorTest {

    private fun candle(
        close: Double,
        high: Double = close * 1.002,
        low: Double = close * 0.998,
        open: Double = close,
        volume: Double = 1000.0,
        t: Long
    ) = CandleBar(t, open, high, low, close, volume)

    /** Uptrend-ish M1 series ending near [price]. */
    private fun m1Near(price: Double, n: Int = 30): List<CandleBar> =
        (1..n).map { i ->
            val base = price * (0.97 + i * 0.001)
            candle(base, t = i * 60_000L)
        }

    private fun trendingRegime() = RegimeSnapshot(
        regime = MarketRegime.TRENDING_UP,
        adx = 28.0,
        chop = 34.0,
        atrPct = 1.2,
        emaAlignment = "bullish",
        explanation = "test trending"
    )

    private fun rangingRegime() = RegimeSnapshot(
        regime = MarketRegime.RANGING,
        adx = 12.0,
        chop = 68.0,
        atrPct = 0.9,
        emaAlignment = "mixed",
        explanation = "test ranging"
    )

    private fun breakoutRegime() = RegimeSnapshot(
        regime = MarketRegime.BREAKOUT,
        adx = 30.0,
        chop = 40.0,
        atrPct = 1.5,
        emaAlignment = "bullish",
        explanation = "test breakout"
    )

    @Test
    fun none_whenPriceInvalid() {
        val r = ScalpSetupDetector.detect(
            price = 0.0,
            structure = StructureSnapshot(),
            regime = trendingRegime()
        )
        assertEquals(ScalpSetupType.NONE, r.setup)
    }

    @Test
    fun liquiditySweep_whenSweepAndChoCH() {
        val structure = StructureSnapshot(
            bias = StructureBias.BULLISH,
            pattern = "CHOCH",
            bos = true,
            choch = true,
            strength = 70,
            support = 100.0,
            resistance = 110.0,
            liquiditySweepDetected = true
        )
        val m1 = m1Near(102.0) + candle(
            close = 102.5,
            open = 101.5,
            high = 102.8,
            low = 99.5,
            volume = 2000.0,
            t = 99_000L
        )
        val r = ScalpSetupDetector.detect(
            price = 102.5,
            structure = structure,
            regime = trendingRegime(),
            rvol = 1.5,
            buyPressure = 1.2,
            m1Candles = m1
        )
        assertEquals(ScalpSetupType.LIQUIDITY_SWEEP, r.setup)
        assertTrue(r.isLongBiased)
        assertTrue(r.explanation.contains("sweep", ignoreCase = true) || r.explanation.contains("CHoCH"))
    }

    @Test
    fun breakoutRetest_whenFlagValid_andRvolHigh() {
        val structure = StructureSnapshot(
            bias = StructureBias.BULLISH,
            pattern = "BOS",
            bos = true,
            strength = 65,
            resistance = 100.0,
            support = 95.0,
            isBreakoutRetestValid = true
        )
        val r = ScalpSetupDetector.detect(
            price = 100.5,
            structure = structure,
            regime = trendingRegime(),
            rvol = 1.6,
            buyPressure = 1.2
        )
        assertEquals(ScalpSetupType.BREAKOUT_RETEST, r.setup)
    }

    @Test
    fun breakoutRetest_rejected_whenRvolLow() {
        val structure = StructureSnapshot(
            bias = StructureBias.BULLISH,
            pattern = "BOS",
            bos = true,
            strength = 65,
            resistance = 100.0,
            support = 95.0,
            isBreakoutRetestValid = true
        )
        val r = ScalpSetupDetector.detect(
            price = 100.5,
            structure = structure,
            regime = trendingRegime(),
            rvol = 1.1,
            buyPressure = 1.2
        )
        assertNotEquals(ScalpSetupType.BREAKOUT_RETEST, r.setup)
    }

    @Test
    fun breakoutRetest_whenNearBrokenResistance_withRvolAndStrength() {
        val structure = StructureSnapshot(
            bias = StructureBias.BULLISH,
            pattern = "BOS",
            bos = true,
            strength = 65,
            resistance = 100.0,
            support = 94.0
        )
        val m1 = m1Near(100.3)
        val r = ScalpSetupDetector.detect(
            price = 100.3,
            structure = structure,
            regime = trendingRegime(),
            rvol = 1.6,
            buyPressure = 1.2,
            m1Candles = m1
        )
        assertEquals(ScalpSetupType.BREAKOUT_RETEST, r.setup)
    }

    @Test
    fun breakoutRetest_rejected_onHardRanging() {
        val structure = StructureSnapshot(
            bias = StructureBias.BULLISH,
            pattern = "BOS",
            bos = true,
            strength = 70,
            resistance = 100.0,
            support = 95.0,
            isBreakoutRetestValid = true
        )
        val r = ScalpSetupDetector.detect(
            price = 100.5,
            structure = structure,
            regime = rangingRegime(),
            rvol = 2.0,
            buyPressure = 1.3
        )
        assertNotEquals(
            "Retest must not fire on hard ranging", 
            ScalpSetupType.BREAKOUT_RETEST, 
            r.setup
        )
    }

    @Test
    fun breakout_whenPriceAboveResistanceWithVolumeAndFlow() {
        val structure = StructureSnapshot(
            bias = StructureBias.BULLISH,
            pattern = "HH_HL",
            bos = true,
            strength = 55,
            resistance = 100.0,
            support = 92.0
        )
        val m1 = m1Near(105.0)
        val r = ScalpSetupDetector.detect(
            price = 105.0,
            structure = structure,
            regime = breakoutRegime(),
            rvol = 2.0,
            buyPressure = 1.3,
            m1Candles = m1
        )
        assertEquals(ScalpSetupType.BREAKOUT, r.setup)
        assertTrue(r.explanation.contains("Breakout", ignoreCase = true))
    }

    @Test
    fun trendPullback_whenTrendingNearSupport() {
        val structure = StructureSnapshot(
            bias = StructureBias.BULLISH,
            pattern = "HH_HL",
            strength = 72,
            support = 100.0,
            resistance = 108.0
        )
        val m1 = m1Near(100.4)
        val r = ScalpSetupDetector.detect(
            price = 100.4,
            structure = structure,
            regime = trendingRegime(),
            rvol = 1.2,
            buyPressure = 1.15,
            m1Candles = m1
        )
        assertEquals(ScalpSetupType.TREND_PULLBACK, r.setup)
        assertTrue(r.isLongBiased)
    }

    @Test
    fun none_onHardRangingWithoutSweep() {
        val structure = StructureSnapshot(
            bias = StructureBias.NEUTRAL,
            pattern = "RANGING",
            strength = 30,
            support = 99.0,
            resistance = 101.0
        )
        val r = ScalpSetupDetector.detect(
            price = 100.0,
            structure = structure,
            regime = rangingRegime(),
            rvol = 1.0,
            buyPressure = 1.0,
            m1Candles = m1Near(100.0)
        )
        assertEquals(ScalpSetupType.NONE, r.setup)
    }

    @Test
    fun worksOnIdrScale() {
        val structure = StructureSnapshot(
            bias = StructureBias.BULLISH,
            pattern = "HH_HL",
            strength = 70,
            support = 1_500_000_000.0,
            resistance = 1_520_000_000.0
        )
        val price = 1_501_000_000.0
        val m1 = (1..30).map { i ->
            val base = price * (0.98 + i * 0.0007)
            candle(base, volume = 2.5, t = i * 60_000L)
        }
        val r = ScalpSetupDetector.detect(
            price = price,
            structure = structure,
            regime = trendingRegime(),
            rvol = 1.25,
            buyPressure = 1.2,
            m1Candles = m1
        )
        assertTrue(
            "IDR scale should yield pullback or none, got ${r.setup}",
            r.setup == ScalpSetupType.TREND_PULLBACK || r.setup == ScalpSetupType.NONE
        )
    }

    @Test
    fun minScoreForSetup_retestIs75() {
        assertEquals(75, ScalpingConfig.minScoreForSetup("BREAKOUT_RETEST"))
        assertEquals(60, ScalpingConfig.minScoreForSetup("TREND_PULLBACK"))
        assertEquals(60, ScalpingConfig.minScoreForSetup("LIQUIDITY_SWEEP"))
    }
}
