package agu.analys.engine.intraday

import agu.analys.engine.TestData
import agu.analys.model.MarketTick
import agu.analys.model.SignalAction
import org.junit.Assert.*
import org.junit.Test

class IntradayEvaluatorTest {

    @Test
    fun testIntradayEvaluationWithGoodData() {
        val history = TestData.generateCandles(200, 800.0, 0.001).toMutableList()
        val lastClose = history.last().close
        val time = history.last().timestamp
        for (i in 1..5) {
            val c = lastClose - (i * 1.5)
            history.add(agu.analys.model.CandleBar(time + (i * 3600000L), c + 1.0, c + 2.0, c - 2.0, c, 5000.0))
        }
        val price = history.last().close

        val result = IntradayEvaluator.evaluate(price, history)

        assertNotNull(result)
        assertTrue("Confidence should be at least 20 (got ${result.signal.confidence})", result.signal.confidence >= 20)
        assertNotNull(result.signal.riskRewardRatio)
    }

    @Test
    fun testIntradayWithLowData() {
        val price = 1000.0
        val history = TestData.generateCandles(5, 1000.0)

        val result = IntradayEvaluator.evaluate(price, history)

        assertEquals("Action should be HOLD with low data", SignalAction.HOLD, result.signal.action)
        assertTrue("Reasoning should mention data sync", result.signal.reasoning.any { it.contains("candle", true) || it.contains("data", true) })
    }

    @Test
    fun testIntradayScreenerFast() {
        val goodTick = MarketTick(
            symbol = "BTCIDR",
            price = 1_000_000_000.0,
            high24h = 1_050_000_000.0,
            low24h = 980_000_000.0,
            volume24h = 15_000_000_000.0,
            change24h = 3.5
        )

        val score = IntradayScreener.evaluateFast(goodTick)
        assertNotNull(score)
        assertTrue("Good tick should qualify for intraday", score.isQualified)
        assertTrue("Score should be >= 7", score.score >= 7)
    }
}
