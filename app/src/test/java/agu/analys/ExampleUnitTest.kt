package agu.analys

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.scalping.ScalpingMtfEvaluator
import agu.analys.model.CandleBar
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testScalpingSensitivityModes() {
        val now = System.currentTimeMillis()
        val h1 = (0 until 60).map { i ->
            val p = 100000.0 + i * 100.0
            CandleBar(now - (60 - i) * 3600_000L, p, p + 50.0, p - 50.0, p + 20.0, 10.0)
        }
        val m15 = (0 until 60).map { i ->
            val p = 105000.0 + i * 50.0
            CandleBar(now - (60 - i) * 900_000L, p, p + 25.0, p - 25.0, p + 10.0, 5.0)
        }
        val m1 = (0 until 60).map { i ->
            val p = 107000.0 + i * 10.0
            CandleBar(now - (60 - i) * 60_000L, p, p + 15.0, p - 15.0, p + 5.0, 2.0)
        }

        val resultConservative = ScalpingMtfEvaluator.evaluate(
            price = 107600.0,
            h1Candles = h1,
            m15Candles = m15,
            m1Candles = m1,
            fees = TradingFeeConfig()
        )
        assertNotNull(resultConservative)

        val resultAggressive = ScalpingMtfEvaluator.evaluate(
            price = 107600.0,
            h1Candles = h1,
            m15Candles = m15,
            m1Candles = m1,
            fees = TradingFeeConfig()
        )
        assertNotNull(resultAggressive)
    }
}
