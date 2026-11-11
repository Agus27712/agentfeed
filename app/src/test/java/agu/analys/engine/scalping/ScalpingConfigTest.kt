package agu.analys.engine.scalping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpingConfigTest {

    @Test
    fun scoreGates_ordered() {
        assertTrue(ScalpingConfig.MIN_SCORE_LONG < ScalpingConfig.MIN_SCORE_STRONG)
        assertEquals(60, ScalpingConfig.MIN_SCORE_LONG)
        assertEquals(75, ScalpingConfig.MIN_SCORE_STRONG)
    }

    @Test
    fun riskRr_sensible() {
        assertTrue(ScalpingConfig.MIN_NET_RR < ScalpingConfig.TARGET_NET_RR)
        assertEquals(1.15, ScalpingConfig.MIN_NET_RR, 1e-9)
        // Target dinaikkan 1.25 -> 2.0 (2026-10-12) agar win-rate minimum impas turun.
        assertEquals(2.0, ScalpingConfig.TARGET_NET_RR, 1e-9)
        // Lantai risiko berbasis friksi: risk >= 6x friksi round-trip.
        assertEquals(6.0, ScalpingConfig.MIN_FRICTION_MULTIPLE, 1e-9)
        assertEquals(0.5, ScalpingConfig.MIN_RISK_PCT, 1e-9)
    }

    @Test
    fun rvolThresholds_ordered() {
        assertTrue(ScalpingConfig.RVOL_LOW < ScalpingConfig.RVOL_NORMAL)
        assertTrue(ScalpingConfig.RVOL_NORMAL < ScalpingConfig.RVOL_HIGH)
        assertTrue(ScalpingConfig.RVOL_HIGH < ScalpingConfig.RVOL_EXTREME)
        assertEquals(1.5, ScalpingConfig.RVOL_BREAKOUT_MIN, 1e-9)
    }

    @Test
    fun mtfKeys_sixSlots() {
        assertEquals(6, ScalpingConfig.MTF_KEYS_DEFAULT.size)
        assertTrue(ScalpingConfig.MTF_KEYS_DEFAULT.contains("1M"))
        assertTrue(ScalpingConfig.MTF_KEYS_DEFAULT.contains("1D"))
    }

    @Test
    fun historicalEdgeMessage_noFakePercent() {
        assertTrue(ScalpingConfig.HISTORICAL_EDGE_INSUFFICIENT.contains("Belum cukup"))
        assertTrue(!ScalpingConfig.HISTORICAL_EDGE_INSUFFICIENT.contains("63"))
    }
}
