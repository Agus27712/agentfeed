package agu.analys.util

import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleAnchorTest {

    private val dayMs = 24L * 60L * 60L * 1000L
    // 00:00 WIB = 17:00 UTC hari sebelumnya: bukan kelipatan 24 jam dari UTC.
    private val base = 17L * 60L * 60L * 1000L

    private fun candle(ts: Long, close: Double) =
        CandleBar(timestamp = ts, open = close, high = close, low = close, close = close, volume = 1.0, isClosed = true)

    private fun tick(price: Double, ts: Long) =
        MarketTick("BTCIDR", price, price, price, 1.0, 0.0, ts)

    @Test
    fun sameWindow_updatesFormingCandleInPlace() {
        val baseCandles = listOf(candle(base, 100.0))
        val result = CandleTimeUtil.synthesizeRealtimeCandles(
            baseCandles, tick(110.0, base + 3_600_000L), Timeframe.D1, nowMs = base + 3_600_000L
        )
        assertEquals(1, result.size)
        assertEquals(base, result.last().timestamp)
        assertEquals(110.0, result.last().close, 0.0)
        assertFalse(result.last().isClosed)
    }

    @Test
    fun nextDay_startsNewCandleOnCandleGrid() {
        val baseCandles = listOf(candle(base, 100.0))
        val tickTime = base + dayMs + 3_600_000L // 1 jam setelah candle berikutnya dimulai
        val result = CandleTimeUtil.synthesizeRealtimeCandles(
            baseCandles, tick(105.0, tickTime), Timeframe.D1, nowMs = tickTime
        )
        assertEquals(2, result.size)
        assertTrue(result[0].isClosed)
        // Candle baru harus mulai tepat di grid candle terakhir (base + 1 hari), bukan tengah malam UTC.
        assertEquals(base + dayMs, result.last().timestamp)
        assertFalse(result.last().isClosed)
    }
}
