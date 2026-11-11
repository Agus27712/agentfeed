package agu.analys.engine.scalping

import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import agu.analys.model.SignalAction
import agu.analys.util.ScalpingLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field pipeline pada AISignalState (dipakai UI, log sinyal, dan trade journal):
 * setup, skor, rincian skor, regime, arah, dan zona entry.
 */
class ScalpingSignalFieldsTest {

    private fun series(n: Int, base: Double, drift: Double, amp: Double, freq: Double, step: Long): List<CandleBar> {
        var prev = base
        return (0 until n).map { i ->
            val c = base + i * drift + amp * kotlin.math.sin(i * freq)
            val o = prev
            prev = c
            CandleBar((i + 1) * step, o, maxOf(o, c) + base * 0.0015, minOf(o, c) - base * 0.0015, c, 1000.0)
        }
    }

    private fun m1(): List<CandleBar> {
        val c = series(90, 1000.0, 0.3, 4.0, 0.8, 60_000L).toMutableList()
        val l = c.last()
        c.add(CandleBar(l.timestamp + 60_000, l.close, l.close + 4.0, l.close - 0.5, l.close + 3.5, 4500.0))
        return c
    }

    private val h1 = series(60, 900.0, 0.9, 12.0, 0.5, 3_600_000L)
    private val m15 = series(60, 950.0, 0.6, 8.0, 0.7, 900_000L)
    private val m5 = series(60, 970.0, 0.5, 6.0, 0.6, 300_000L)
    private val bids = listOf(OrderBookItem(price = 1000.0, amount = 30.0, total = 30_000.0, isBid = true))
    private val asks = listOf(OrderBookItem(price = 1001.0, amount = 10.0, total = 10_010.0, isBid = false))

    @Test
    fun `sinyal BUY membawa setup, skor, regime, arah, dan zona entry`() {
        val candles = m1()
        val price = candles.last().close
        val s = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = h1, m15Candles = m15, m5Candles = m5, m1Candles = candles,
            bids = bids, asks = asks, symbol = "BTCIDR"
        )!!.signal

        assertEquals(SignalAction.BUY, s.action)
        assertTrue(s.scalpingSetup.isNotBlank() && s.scalpingSetup != "NONE")
        assertTrue(s.scalpingScore >= ScalpingMtfEvaluator.MIN_SCORE_LONG)
        assertTrue(s.scalpingScoreCategory.isNotBlank())
        assertTrue(s.scalpingScoreDetail.contains("Struktur") && s.scalpingScoreDetail.contains("Volatilitas"))
        assertTrue(s.scalpingRegime.isNotBlank())
        assertEquals("LONG", s.scalpingDirection)
        assertTrue(s.entryZoneLow > 0.0 && s.entryZoneLow <= s.entryZoneHigh)
        assertTrue(s.stopLoss < s.entryZoneLow)
    }

    @Test
    fun `tanpa setup valid zona entry kosong dan setup NONE`() {
        val flat = (0 until 20).map { CandleBar((it + 1) * 60_000L, 1000.0, 1002.0, 998.0, 1000.0, 1000.0) }
        val s = ScalpingMtfEvaluator.evaluate(
            price = 1000.0, h1Candles = flat, m15Candles = flat, m1Candles = flat, symbol = "BTCIDR"
        )!!.signal

        assertEquals(SignalAction.HOLD, s.action)
        assertEquals("NONE", s.scalpingSetup)
        assertEquals(0.0, s.entryZoneLow, 0.0)
        assertEquals(0.0, s.entryZoneHigh, 0.0)
        assertEquals("WAIT", s.scalpingDirection)
    }

    @Test
    fun `label setup dalam Bahasa Indonesia`() {
        assertEquals("Breakout + Retest", ScalpingLabels.setup("BREAKOUT_RETEST"))
        assertEquals("Liquidity Sweep", ScalpingLabels.setup("LIQUIDITY_SWEEP"))
        assertEquals("Trend Pullback", ScalpingLabels.setup("TREND_PULLBACK"))
        assertEquals("Breakout", ScalpingLabels.setup("BREAKOUT"))
        assertEquals("Belum ada setup", ScalpingLabels.setup("NONE"))
    }
}
