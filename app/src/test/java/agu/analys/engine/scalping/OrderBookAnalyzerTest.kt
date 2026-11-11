package agu.analys.engine.scalping

import agu.analys.model.OrderBookItem
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderBookAnalyzerTest {

    private fun level(amount: Double, isBid: Boolean) =
        OrderBookItem(price = 100.0, amount = amount, total = amount * 100.0, isBid = isBid)

    @Test
    fun emptyOrderBookIsNeutral() {
        assertEquals(1.0, OrderBookAnalyzer.calculateBuyPressure(emptyList(), emptyList()), 0.0)
    }

    @Test
    fun bidsOnlyAreNotClassifiedAsNeutral() {
        assertEquals(1.5, OrderBookAnalyzer.calculateBuyPressure(listOf(level(10.0, true)), emptyList()), 0.0)
    }

    @Test
    fun asksOnlyIndicateSellPressure() {
        assertEquals(0.5, OrderBookAnalyzer.calculateBuyPressure(emptyList(), listOf(level(10.0, false))), 0.0)
    }

    @Test
    fun pressureUsesBidAskAmountRatio() {
        val bids = listOf(level(20.0, true))
        val asks = listOf(level(10.0, false))
        assertEquals(2.0, OrderBookAnalyzer.calculateBuyPressure(bids, asks), 0.0)
    }
}
