package agu.analys.engine.scalping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpingRuntimeConfigTest {

    @Test
    fun defaultValuesMatchScalpingConfig() {
        val cfg = ScalpingRuntimeConfig.DEFAULT

        assertEquals(ScalpingConfig.MIN_SCORE_LONG, cfg.minScoreLong)
        assertEquals(ScalpingConfig.MIN_SCORE_STRONG, cfg.minScoreStrong)
        assertEquals(ScalpingConfig.MIN_SCORE_BREAKOUT_RETEST, cfg.minScoreBreakoutRetest)
        assertEquals(ScalpingConfig.MIN_NET_RR, cfg.minNetRr, 0.0001)
        assertEquals(ScalpingConfig.TARGET_NET_RR, cfg.targetNetRr, 0.0001)
        assertEquals(ScalpingConfig.RVOL_BREAKOUT_MIN, cfg.rvolBreakoutMin, 0.0001)
        assertEquals(ScalpingConfig.CHOP_HARD_RANGING, cfg.chopHardRanging, 0.0001)
        assertEquals(ScalpingConfig.RETEST_MAX_DIST_PCT, cfg.retestMaxDistPct, 0.0001)
        assertEquals(ScalpingConfig.RETEST_MIN_STRUCTURE_STRENGTH, cfg.retestMinStructureStrength)
        // P0 — default harus mengikuti konstanta pusat, bukan angka kedua di data class.
        assertEquals(ScalpingConfig.TTL_BARS_M1, cfg.ttlBarsM1)
        assertEquals(ScalpingConfig.MTF_MIN_BULLISH, cfg.mtfMinBullish)
        assertEquals(ScalpingConfig.MTF_BLOCK_HIGH_TF_BEARISH, cfg.mtfBlockHighTfBearish)
        assertEquals(ScalpingConfig.BTC_VETO_ENABLED, cfg.btcVetoEnabled)
        assertEquals(ScalpingConfig.BTC_RETURN_VETO_PCT, cfg.btcReturnVetoPct, 0.0001)
        assertEquals(ScalpingConfig.BTC_ATR_RATIO_VETO, cfg.btcAtrRatioVeto, 0.0001)
        assertEquals(ScalpingConfig.CHOP_TREND_MAX, cfg.regimeChopTrendMax, 0.0001)
    }

    @Test
    fun presetsAreTighterAndLooser() {
        val strict = ScalpingRuntimeConfig.STRICT
        val loose = ScalpingRuntimeConfig.LOOSE
        val defaultCfg = ScalpingRuntimeConfig.DEFAULT

        assertTrue("Strict should require higher score", strict.minScoreLong > defaultCfg.minScoreLong)
        assertTrue("Loose should allow lower score", loose.minScoreLong < defaultCfg.minScoreLong)
        assertTrue("Strict should require higher R:R", strict.minNetRr > defaultCfg.minNetRr)
        assertTrue("Loose should allow lower R:R", loose.minNetRr < defaultCfg.minNetRr)
        assertTrue("Strict should be more restrictive on CHOP", strict.chopHardRanging < defaultCfg.chopHardRanging)
    }

    @Test
    fun jsonRoundTripPreservesAllFields() {
        val original = ScalpingRuntimeConfig(
            minScoreLong = 65,
            minScoreStrong = 78,
            minScoreBreakoutRetest = 82,
            minNetRr = 1.35,
            targetNetRr = 1.60,
            minRiskPct = 0.6,
            maxTp2R = 4.5,
            slippagePct = 0.09,
            rvolBreakoutMin = 1.7,
            rvolSetupMin = 1.3,
            rvolRetestMin = 1.6,
            buyPressureBreakout = 1.20,
            buyPressureRetest = 1.20,
            chopHardRanging = 62.0,
            retestMaxDistPct = 1.1,
            breakoutAboveFactor = 0.997,
            retestAboveFactor = 0.994,
            retestMinStructureStrength = 65,
            pullbackSupportMaxDistPct = 1.1
        )

        val jsonStr = original.toJsonString()
        val parsed = ScalpingRuntimeConfig.fromJsonString(jsonStr)

        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun jsonRoundTripPreservesP0Fields() {
        val original = ScalpingRuntimeConfig(
            ttlBarsM1 = 7,
            mtfMinBullish = 4,
            mtfBlockHighTfBearish = false,
            btcVetoEnabled = false,
            btcReturnVetoPct = -1.1,
            btcAtrRatioVeto = 3.2,
            btcEmaPeriod = 34,
            regimeAdxTrending = 27.5,
            regimeAdxWeak = 21.0,
            regimeChopRanging = 60.0,
            regimeChopTrendMax = 52.0
        )

        val parsed = ScalpingRuntimeConfig.fromJsonString(original.toJsonString())
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun minScoreForSetupRespectsRuntimeConfig() {
        val cfg = ScalpingRuntimeConfig(minScoreLong = 55, minScoreBreakoutRetest = 72)
        assertEquals(72, cfg.minScoreForSetup("BREAKOUT_RETEST"))
        assertEquals(55, cfg.minScoreForSetup("BREAKOUT"))
        assertEquals(55, cfg.minScoreForSetup("LIQUIDITY_SWEEP"))
    }
}
