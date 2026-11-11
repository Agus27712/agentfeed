package agu.analys.engine.confluence

import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import org.junit.Assert.*
import org.junit.Test

class ConfluenceEvaluatorTest {

    private val fees = TradingFeeConfig()

    private fun createCandleSeries(
        count: Int,
        startPrice: Double,
        trendStep: Double,
        baseVol: Double = 1000.0,
        baseTime: Long = 1700000000000L,
        intervalMs: Long = 3600000L
    ): MutableList<CandleBar> {
        val list = mutableListOf<CandleBar>()
        var p = startPrice
        for (i in 0 until count) {
            val open = p
            val close = p + trendStep
            val high = maxOf(open, close) + 2.0
            val low = minOf(open, close) - 2.0
            list.add(CandleBar(baseTime + i * intervalMs, open, high, low, close, baseVol))
            p = close
        }
        return list
    }

    @Test
    fun testConfluenceEvaluatorStructure_Has6Checkpoints() {
        val macro = createCandleSeries(100, 10000.0, 10.0)
        val micro = createCandleSeries(60, 11000.0, 5.0)

        val result = ConfluenceEvaluator.evaluate(
            price = 11300.0,
            macroCandles = macro,
            microCandles = micro,
            strategyMode = StrategyMode.SWING,
            fees = fees
        )

        assertNotNull(result)
        assertEquals(6, result.checkpoints.size)
        assertEquals(1, result.checkpoints[0].number)
        assertEquals("MTF", result.checkpoints[0].code)
        assertEquals(2, result.checkpoints[1].number)
        assertEquals("AOV", result.checkpoints[1].code)
        assertEquals(3, result.checkpoints[2].number)
        assertEquals("VOL", result.checkpoints[2].code)
        assertEquals(4, result.checkpoints[3].number)
        assertEquals("TRG", result.checkpoints[3].code)
        assertEquals(5, result.checkpoints[4].number)
        assertEquals("MOM", result.checkpoints[4].code)
        assertEquals(6, result.checkpoints[5].number)
        assertEquals("RR", result.checkpoints[5].code)
    }

    @Test
    fun testRiskRewardCheckpoint_EvaluatesRatio() {
        val candles = createCandleSeries(100, 10000.0, 5.0)
        val currentPrice = candles.last().close

        val result = ConfluenceEvaluator.evaluate(
            price = currentPrice,
            macroCandles = candles,
            microCandles = candles,
            strategyMode = StrategyMode.SWING,
            fees = fees
        )

        val rrCheckpoint = result.checkpoints.find { it.code == "RR" }
        assertNotNull(rrCheckpoint)
        assertTrue(rrCheckpoint!!.metricValue.startsWith("Net R:R TP1 1:"))
    }

    @Test
    fun testAreaOfValueAndReversalPattern_ValidConfluence() {
        val baseTime = 1700000000000L
        val history = mutableListOf<CandleBar>()
        var p = 50000.0

        for (i in 0 until 80) {
            val open = p
            val close = p + 20.0
            history.add(CandleBar(baseTime + i * 3600000L, open, close + 5.0, open - 5.0, close, 1000.0))
            p = close
        }

        for (i in 1..5) {
            val open = p
            val close = p - 140.0
            history.add(CandleBar(baseTime + (80 + i) * 3600000L, open, open + 10.0, close - 20.0, close, 800.0))
            p = close
        }

        val triggerOpen = p
        val triggerClose = p + 80.0
        val triggerLow = p - 250.0
        val triggerHigh = triggerClose + 10.0
        history.add(CandleBar(baseTime + 86 * 3600000L, triggerOpen, triggerHigh, triggerLow, triggerClose, 3500.0))
        val currentPrice = triggerClose

        val result = ConfluenceEvaluator.evaluate(
            price = currentPrice,
            macroCandles = history,
            microCandles = history,
            strategyMode = StrategyMode.SWING,
            fees = fees
        )

        assertNotNull(result)
        val aov = result.checkpoints[1]
        val trigger = result.checkpoints[3]
        assertTrue("AOV or Trigger should register activity", aov.isOk || trigger.isOk || result.completedCount >= 2)
    }

    @Test
    fun testScalpingMode_EvaluatesWithOrderBook() {
        val macro = createCandleSeries(40, 20000.0, 15.0)
        val micro = createCandleSeries(40, 20500.0, 5.0)

        val bids = listOf(
            OrderBookItem(20700.0, 5.0, 103500.0, true),
            OrderBookItem(20690.0, 4.0, 82760.0, true)
        )
        val asks = listOf(
            OrderBookItem(20710.0, 2.0, 41420.0, false),
            OrderBookItem(20720.0, 2.0, 41440.0, false)
        )

        val result = ConfluenceEvaluator.evaluate(
            price = 20705.0,
            macroCandles = macro,
            microCandles = micro,
            strategyMode = StrategyMode.SCALPING,
            orderBookBids = bids,
            orderBookAsks = asks,
            fees = fees
        )

        assertNotNull(result)
        assertEquals(6, result.checkpoints.size)
        assertTrue(result.checkpoints[2].metricValue.isNotBlank())
    }

    @Test
    fun testVolumeValidation_ZeroVolumeCandleDoesNotPass() {
        val zeroVolCandles = (1..30).map { i ->
            CandleBar(
                timestamp = 1000000L + i * 60000L,
                open = 1000.0,
                high = 1010.0,
                low = 995.0,
                close = 1005.0,
                volume = 0.0,
                isClosed = true
            )
        }

        val result = ConfluenceEvaluator.evaluate(
            price = 1005.0,
            macroCandles = zeroVolCandles,
            microCandles = zeroVolCandles,
            strategyMode = StrategyMode.SCALPING,
            fees = fees
        )

        assertNotNull(result)
        val step3Vol = result.checkpoints[2]
        assertEquals("VOL", step3Vol.code)
        assertFalse("Checkpoint 3 MUST NOT pass when volume is zero", step3Vol.isOk)
        assertTrue(step3Vol.metricValue.contains("0.00×"))
        assertTrue(step3Vol.detail.contains("belum terbentuk", ignoreCase = true) || step3Vol.detail.contains("0", ignoreCase = true))
    }

    @Test
    fun testVolumeValidation_UsesLastClosedWhenFormingIsZero() {
        // 29 closed candles with healthy volume, and 1 forming candle with 0 volume (just opened)
        val candles = (1..29).map { i ->
            CandleBar(
                timestamp = 1000000L + i * 60000L,
                open = 1000.0,
                high = 1010.0,
                low = 995.0,
                close = 1005.0,
                volume = 1000.0,
                isClosed = true
            )
        }.toMutableList()

        // 30th candle is forming (unclosed, 0 volume)
        candles.add(
            CandleBar(
                timestamp = 1000000L + 30 * 60000L,
                open = 1005.0,
                high = 1006.0,
                low = 1004.0,
                close = 1005.0,
                volume = 0.0,
                isClosed = false
            )
        )

        val result = ConfluenceEvaluator.evaluate(
            price = 1005.0,
            macroCandles = candles,
            microCandles = candles,
            strategyMode = StrategyMode.SCALPING,
            fees = fees
        )

        assertNotNull(result)
        val step3Vol = result.checkpoints[2]
        assertEquals("VOL", step3Vol.code)
        // Should evaluate the 29th closed candle (1000.0 vol / 1000.0 avg = 1.00x)
        assertFalse(step3Vol.metricValue.contains("Vol 0.00×"))
        assertTrue(step3Vol.metricValue.contains("Vol 1.00×"))
    }

    @Test
    fun testVolumeValidation_MacroFallbackWhenMicroCandlesDry() {
        val macroCandles = (1..30).map { i ->
            CandleBar(
                timestamp = 1000000L + i * 900000L,
                open = 1000.0,
                high = 1020.0,
                low = 990.0,
                close = 1015.0,
                volume = 50000.0,
                isClosed = true
            )
        }

        // Micro candles have 0 volume (e.g. illiquid M1 altcoin)
        val dryMicroCandles = (1..30).map { i ->
            CandleBar(
                timestamp = 1000000L + i * 60000L,
                open = 1010.0,
                high = 1012.0,
                low = 1009.0,
                close = 1011.0,
                volume = 0.0,
                isClosed = true
            )
        }

        val result = ConfluenceEvaluator.evaluate(
            price = 1015.0,
            macroCandles = macroCandles,
            microCandles = dryMicroCandles,
            strategyMode = StrategyMode.SCALPING,
            fees = fees
        )

        assertNotNull(result)
        val step3Vol = result.checkpoints[2]
        assertEquals("VOL", step3Vol.code)
        // Checkpoint 3 should fall back to macroCandles instead of being 0.00x
        assertFalse(step3Vol.metricValue.contains("Vol 0.00×"))
        assertTrue(step3Vol.metricValue.contains("Vol 1.00×"))
    }

    @Test
    fun testIntradayMode_Evaluates6Checkpoints() {
        val candles = createCandleSeries(60, 15000.0, 8.0)

        val result = ConfluenceEvaluator.evaluate(
            price = 15480.0,
            macroCandles = candles,
            microCandles = candles,
            strategyMode = StrategyMode.OFFICE_DAILY,
            fees = fees
        )

        assertNotNull(result)
        assertEquals(6, result.checkpoints.size)
        val statusTitle = result.mtfSnapshot.statusTitle
        assertTrue(statusTitle.contains("INTRADAY", ignoreCase = true) || statusTitle.contains("HARIAN", ignoreCase = true))
    }
}
