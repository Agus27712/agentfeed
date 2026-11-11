package agu.analys.engine.scalping

import agu.analys.model.OrderBookItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderImbalanceTest {

    private fun bid(price: Double, amount: Double) =
        OrderBookItem(price = price, amount = amount, total = amount, isBid = true)

    private fun ask(price: Double, amount: Double) =
        OrderBookItem(price = price, amount = amount, total = amount, isBid = false)

    @Test
    fun imbalance_zeroWhenEmpty() {
        assertEquals(0.0, OrderBookAnalyzer.calculateOrderImbalance(emptyList(), emptyList()), 1e-9)
    }

    @Test
    fun imbalance_positiveWhenBidsDominate() {
        val bids = listOf(bid(100.0, 80.0), bid(99.0, 20.0))
        val asks = listOf(ask(101.0, 20.0))
        // (100-20)/(100+20) = 80/120 ≈ 0.666
        val imb = OrderBookAnalyzer.calculateOrderImbalance(bids, asks, levels = 10)
        assertTrue("imb=$imb", imb in 0.65..0.67)
    }

    @Test
    fun imbalance_negativeWhenAsksDominate() {
        val bids = listOf(bid(100.0, 10.0))
        val asks = listOf(ask(101.0, 90.0))
        val imb = OrderBookAnalyzer.calculateOrderImbalance(bids, asks)
        assertTrue("imb=$imb", imb in -0.85..-0.75)
    }

    @Test
    fun analyzeOrderFlow_hasDepthAndPressure() {
        val bids = listOf(bid(100.0, 50.0))
        val asks = listOf(ask(100.5, 25.0))
        val flow = OrderBookAnalyzer.analyzeOrderFlow(bids, asks)
        assertTrue(flow.hasDepth)
        assertEquals(2.0, flow.buyPressure, 1e-9) // 50/25
        assertTrue(flow.orderImbalance > 0.3)
        assertEquals(50.0, flow.bidVolume, 1e-9)
        assertEquals(25.0, flow.askVolume, 1e-9)
    }

    @Test
    fun analyzeOrderFlow_empty_noDepth() {
        val flow = OrderBookAnalyzer.analyzeOrderFlow(emptyList(), emptyList())
        assertFalse(flow.hasDepth)
        assertEquals(1.0, flow.buyPressure, 1e-9)
        assertEquals(0.0, flow.orderImbalance, 1e-9)
    }
}
