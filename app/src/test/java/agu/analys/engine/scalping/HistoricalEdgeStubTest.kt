package agu.analys.engine.scalping

import agu.analys.model.MarketRegime
import agu.analys.model.ScalpSetupType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalEdgeStubTest {

    @Test
    fun query_alwaysInsufficient_noFakeStats() {
        val r = HistoricalEdgeStub.query(
            HistoricalEdgeStub.Query(
                setup = ScalpSetupType.TREND_PULLBACK,
                regime = MarketRegime.TRENDING_UP,
                minRvol = 1.5,
                minNetRr = 1.5,
                symbol = "BTCUSDT",
                timeframe = "1m"
            )
        )
        assertEquals(HistoricalEdgeStub.Status.INSUFFICIENT, r.status)
        assertNull(r.occurrences)
        assertNull(r.tpFirstPct)
        assertNull(r.slFirstPct)
        assertNull(r.averageReturnPct)
        assertFalse(r.isDisplayable)
        assertTrue(r.displayText().contains("Belum cukup", ignoreCase = true))
        // Jangan pernah muncul angka win-rate palsu di stub
        assertFalse(r.displayText().contains("63.4"))
        assertFalse(r.displayText().contains("%"))
    }

    @Test
    fun stubMessage_nonEmptyIndonesian() {
        val msg = HistoricalEdgeStub.stubMessage(
            setup = ScalpSetupType.BREAKOUT,
            regime = MarketRegime.BREAKOUT
        )
        assertTrue(msg.isNotBlank())
        assertEquals(ScalpingConfig.HISTORICAL_EDGE_INSUFFICIENT, msg)
    }
}
