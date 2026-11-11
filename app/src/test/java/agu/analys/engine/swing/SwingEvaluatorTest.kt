package agu.analys.engine.swing

import agu.analys.engine.TestData
import agu.analys.model.SignalAction
import org.junit.Assert.*
import org.junit.Test

class SwingEvaluatorTest {

    @Test
    fun testSwingQualified() {
        // Uptrend 150 bar dengan koreksi sehat 4 bar (-2.5%) dan rejection candle di support
        val baseTime = 1700000000000L
        val history = mutableListOf<agu.analys.model.CandleBar>()
        var p = 800.0
        for (i in 0 until 150) {
            val step = 1.6
            val open = p
            val close = p + step
            history.add(agu.analys.model.CandleBar(baseTime + i * 3600000L, open, close + 0.5, open - 0.5, close, 2500.0))
            p = close
        }
        // Peak di sekitar 1040. Koreksi 4 bar ke support ~1012
        val peak = p
        val corrections = listOf(peak - 8.0, peak - 16.0, peak - 23.0, peak - 28.0)
        for ((idx, targetC) in corrections.withIndex()) {
            val open = p
            val close = targetC
            history.add(agu.analys.model.CandleBar(baseTime + (150 + idx) * 3600000L, open, open + 1.0, close - 1.0, close, 2000.0))
            p = close
        }
        // Rejection candle di support: pantulan naik dengan volume tinggi
        val lastOpen = p
        val lastClose = p + 6.0 // Naik ke ~1018, masih 2.1% di bawah peak 1040 (aman dari pucuk)
        history.add(agu.analys.model.CandleBar(baseTime + 154 * 3600000L, lastOpen, lastClose + 0.5, lastOpen - 2.0, lastClose, 12000.0))
        val currentPrice = lastClose
        
        val result = SwingEvaluator.evaluate(currentPrice, history)
        
        assertNotNull(result)
        assertTrue("Confidence should be reasonable (got ${result.signal.confidence}, reasons: ${result.signal.reasoning})", result.signal.confidence >= 20)
    }

    @Test
    fun testSwingWithLowData() {
        val price = 1000.0
        val history = TestData.generateCandles(5, 1000.0)
        
        val result = SwingEvaluator.evaluate(price, history)
        
        assertEquals("Action should be HOLD with low data", SignalAction.HOLD, result.signal.action)
        assertTrue("Reasoning should mention data sync", result.signal.reasoning.any { it.contains("data", true) })
    }

    @Test
    fun testSwingBearishTrend() {
        val price = 1000.0
        // Bearish trend setup causing HOLD / defensive signal
        val history = TestData.generateCandles(100, 1500.0, -0.005)
        
        val result = SwingEvaluator.evaluate(price = price, history = history)
        assertNotNull(result)
        // In strong downtrend without setup, action should not be BUY
        assertNotEquals("Should not be BUY in clear downtrend without setup", SignalAction.BUY, result.signal.action)
    }
}
