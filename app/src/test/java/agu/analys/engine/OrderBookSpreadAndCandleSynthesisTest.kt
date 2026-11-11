package agu.analys.engine

import agu.analys.engine.scalping.EntryExecutionType
import agu.analys.engine.scalping.OrderBookAnalyzer
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.Timeframe
import agu.analys.util.CandleTimeUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderBookSpreadAndCandleSynthesisTest {

    @Test
    fun testSpreadAnalysis_tightSpread_givesMarketTaker() {
        val bids = listOf(OrderBookItem(price = 1000.0, amount = 50.0, total = 50.0, isBid = true))
        val asks = listOf(OrderBookItem(price = 1002.0, amount = 40.0, total = 40.0, isBid = false)) // spread 0.2% <= 0.4%

        val analysis = OrderBookAnalyzer.analyzeSpread(bids, asks, currentPrice = 1001.0, tolerancePct = 0.40, maxGuardPct = 1.20)

        assertEquals(EntryExecutionType.MARKET_TAKER, analysis.executionType)
        assertFalse(analysis.isSpreadGuardActive)
        assertEquals(1002.0, analysis.recommendedEntryPrice, 0.001)
    }

    @Test
    fun testSpreadAnalysis_moderateSpread_givesLimitMaker() {
        val bids = listOf(OrderBookItem(price = 1000.0, amount = 50.0, total = 50.0, isBid = true))
        val asks = listOf(OrderBookItem(price = 1008.0, amount = 40.0, total = 40.0, isBid = false)) // spread 0.8% (> 0.4% but <= 1.2%)

        val analysis = OrderBookAnalyzer.analyzeSpread(bids, asks, currentPrice = 1004.0, tolerancePct = 0.40, maxGuardPct = 1.20)

        assertEquals(EntryExecutionType.LIMIT_MAKER, analysis.executionType)
        assertFalse(analysis.isSpreadGuardActive)
        assertEquals(1000.0, analysis.recommendedEntryPrice, 0.001)
    }

    @Test
    fun testSpreadAnalysis_wideSpread_triggersSpreadGuard() {
        val bids = listOf(OrderBookItem(price = 1000.0, amount = 50.0, total = 50.0, isBid = true))
        val asks = listOf(OrderBookItem(price = 1025.0, amount = 40.0, total = 40.0, isBid = false)) // spread 2.5% (> 1.2%)

        val analysis = OrderBookAnalyzer.analyzeSpread(bids, asks, currentPrice = 1010.0, tolerancePct = 0.40, maxGuardPct = 1.20)

        assertEquals(EntryExecutionType.SPREAD_GUARD_VETO, analysis.executionType)
        assertTrue(analysis.isSpreadGuardActive)
        assertEquals(1000.0, analysis.recommendedEntryPrice, 0.001)
    }

    @Test
    fun testRealtimeTickSynthesis_updatesFormingCandle() {
        val baseCandles = listOf(
            CandleBar(timestamp = 0L, open = 100.0, high = 105.0, low = 95.0, close = 102.0, volume = 10.0, isClosed = true),
            CandleBar(timestamp = 900_000L, open = 102.0, high = 103.0, low = 101.0, close = 102.5, volume = 5.0, isClosed = false)
        )
        val tick = MarketTick(symbol = "MANTAIDR", price = 110.0, high24h = 115.0, low24h = 90.0, volume24h = 1000.0, change24h = 2.0, timestamp = 950_000L)

        val synthesized = CandleTimeUtil.synthesizeRealtimeCandles(baseCandles, tick, Timeframe.M15, nowMs = 950_000L)

        assertEquals(2, synthesized.size)
        val last = synthesized.last()
        assertEquals(110.0, last.high, 0.001)
        assertEquals(110.0, last.close, 0.001)
        assertFalse(last.isClosed)
    }

    @Test
    fun testRealtimeTickSynthesis_appendsNewFormingCandle_whenPeriodCrosses() {
        val baseCandles = listOf(
            CandleBar(timestamp = 0L, open = 100.0, high = 105.0, low = 95.0, close = 102.0, volume = 10.0, isClosed = true),
            CandleBar(timestamp = 900_000L, open = 102.0, high = 103.0, low = 101.0, close = 102.5, volume = 5.0, isClosed = true)
        )
        val tick = MarketTick(symbol = "MANTAIDR", price = 112.0, high24h = 115.0, low24h = 90.0, volume24h = 1000.0, change24h = 2.0, timestamp = 1_850_000L)

        val synthesized = CandleTimeUtil.synthesizeRealtimeCandles(baseCandles, tick, Timeframe.M15, nowMs = 1_850_000L)

        assertEquals(3, synthesized.size)
        val last = synthesized.last()
        assertEquals(1_800_000L, last.timestamp)
        assertEquals(112.0, last.close, 0.001)
        assertFalse(last.isClosed)
    }
}
