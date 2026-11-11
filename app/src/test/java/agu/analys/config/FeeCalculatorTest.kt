package agu.analys.config

import org.junit.Assert.*
import org.junit.Test

class FeeCalculatorTest {

    @Test
    fun testNormalRoundTrip() {
        val fees = TradingFeeConfig(0.1, 0.2, 0.3, 0.4)
        val result = FeeCalculator.roundTrip(
            entry = 1000.0,
            stopLoss = 980.0,
            takeProfit = 1050.0,
            fees = fees,
            useMaker = false,
            slippagePct = 0.08
        )
        assertEquals(0.6, result.feePct, 0.0001)
        assertEquals(0.76, result.totalCostPct, 0.0001)
        assertTrue(result.netRr > 0.0)
    }

    @Test
    fun testStopLossEqualsEntryEdgeCase() {
        val fees = TradingFeeConfig()
        val result = FeeCalculator.roundTrip(
            entry = 1000.0,
            stopLoss = 1000.0,
            takeProfit = 1050.0,
            fees = fees,
            useMaker = false,
            slippagePct = 0.08
        )
        assertFalse("Net RR should not be Double.POSITIVE_INFINITY or NaN", result.netRr.isNaN() || result.netRr.isInfinite())
        assertTrue("Risk should equal total cost pct when stopLoss == entry", result.netRiskPct > 0.0)
    }

    @Test
    fun testInvalidInputs() {
        val fees = TradingFeeConfig()
        val result = FeeCalculator.roundTrip(0.0, 900.0, 1100.0, fees)
        assertEquals(0.0, result.netRr, 0.0001)
    }
}
