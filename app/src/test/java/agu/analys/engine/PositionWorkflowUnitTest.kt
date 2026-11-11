package agu.analys.engine

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.swing.SwingEvaluator
import agu.analys.model.CandleBar
import agu.analys.model.CheckpointStatus
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.PositionContext
import agu.analys.model.SellCheckpointEvaluator
import agu.analys.model.SellSignalState
import agu.analys.model.TradingWorkflow
import agu.analys.model.resolveWorkflow
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionState
import org.junit.Assert.*
import org.junit.Test

class PositionWorkflowUnitTest {

    @Test
    fun testWorkflowResolution() {
        val noPosContext = PositionContext(hasPosition = false)
        assertEquals(TradingWorkflow.BUY, resolveWorkflow(noPosContext))

        val hasPosContext = PositionContext(
            hasPosition = true,
            symbol = "BTC/IDR",
            entryPrice = 1_000_000_000.0,
            quantity = 0.01,
            currentPrice = 1_050_000_000.0
        )
        assertEquals(TradingWorkflow.HOLD_SELL, resolveWorkflow(hasPosContext))
    }

    @Test
    fun testPositionContextCreationFromSpotPosition() {
        val spotPos = SpotPosition(
            state = SpotPositionState.HOLDING,
            entryPrice = 1_000_000_000.0,
            quantity = 0.05,
            isReal = true,
            tp1Price = 1_080_000_000.0,
            stopLossPrice = 950_000_000.0
        )
        val context = PositionContext.create(
            symbol = "BTC/IDR",
            spotPosition = spotPos,
            holdingStatus = null,
            currentPrice = 1_050_000_000.0,
            fees = TradingFeeConfig()
        )

        assertTrue(context.hasPosition)
        assertTrue(context.isReal)
        assertEquals(1_000_000_000.0, context.entryPrice ?: 0.0, 0.001)
        assertEquals(0.05, context.quantity ?: 0.0, 0.0001)
        assertTrue((context.floatingProfitPct ?: 0.0) > 0.0)
    }

    @Test
    fun testSellCheckpointEvaluatorTargetReached() {
        val context = PositionContext(
            hasPosition = true,
            symbol = "ETH/IDR",
            entryPrice = 50_000_000.0,
            quantity = 1.0,
            currentPrice = 55_000_000.0,
            tp1 = 54_000_000.0,
            stopLoss = 47_000_000.0,
            floatingProfitPct = 10.0,
            floatingProfitNet = 5_000_000.0
        )
        val sellSignal = SellSignalState(
            state = agu.analys.model.SellLifecycleState.READY_TO_SELL,
            reason = "Target TP1 tercapai (+10.0%)",
            netProfitPct = 10.0
        )

        val items = SellCheckpointEvaluator.evaluate(context, sellSignal, "IDR")
        assertEquals(4, items.size)
        assertEquals("1. Posisi", items[0].tabLabel)
        assertEquals("2. Proteksi", items[1].tabLabel)
        assertEquals("3. Target", items[2].tabLabel)
        assertEquals("4. Keputusan", items[3].tabLabel)

        assertEquals(CheckpointStatus.COMPLETED, items[0].status)
        assertEquals(CheckpointStatus.COMPLETED, items[2].status)
        assertEquals(CheckpointStatus.READY, items[3].status)
    }

    @Test
    fun testSellCheckpointEvaluatorStopLossHit() {
        val context = PositionContext(
            hasPosition = true,
            symbol = "ETH/IDR",
            entryPrice = 50_000_000.0,
            quantity = 1.0,
            currentPrice = 46_000_000.0,
            stopLoss = 47_000_000.0,
            floatingProfitPct = -8.0,
            floatingProfitNet = -4_000_000.0
        )
        val sellSignal = SellSignalState(
            state = agu.analys.model.SellLifecycleState.STOP_LOSS_HIT,
            reason = "Harga menembus batas cut loss",
            netProfitPct = -8.0
        )

        val items = SellCheckpointEvaluator.evaluate(context, sellSignal, "IDR")
        assertEquals(4, items.size)
        assertEquals(CheckpointStatus.WARNING, items[1].status)
        assertEquals(CheckpointStatus.WARNING, items[3].status)
    }

    @Test
    fun testSwingEvaluatorIsPureMarketAnalyzer() {
        val now = System.currentTimeMillis()
        val candles = (0 until 80).map { i ->
            val p = 100_000.0 + i * 200.0
            CandleBar(now - (80 - i) * 3600_000L, p, p + 100.0, p - 50.0, p + 50.0, 100.0)
        }
        val result = SwingEvaluator.evaluate(
            price = 116_000.0,
            history = candles,
            fees = TradingFeeConfig()
        )

        assertNotNull(result)
        assertNotNull(result.signal)
    }

    @Test
    fun testNoCurrentPriceSubstitutionWhenEntryMissing() {
        val holding = CoinHoldingStatus(
            quantity = 2.0,
            entryPrice = 0.0, // No recorded entry price
            isHolding = true
        )
        val context = PositionContext.create(
            symbol = "SOL/IDR",
            spotPosition = null,
            holdingStatus = holding,
            currentPrice = 3_000_000.0,
            fees = TradingFeeConfig()
        )

        assertTrue(context.hasPosition)
        assertNull("Entry price must NOT be substituted with currentPrice", context.entryPrice)
        assertNull("Cost basis must be null when entry price is missing", context.costBasis)
        assertNull("Floating profit pct must be null when entry price is missing", context.floatingProfitPct)
    }

    @Test
    fun testNoEntrySubstitutionWhenCurrentPriceMissing() {
        val holding = CoinHoldingStatus(
            quantity = 100.0,
            entryPrice = 634.0,
            isHolding = true
        )
        val context = PositionContext.create(
            symbol = "HBAR/IDR",
            spotPosition = null,
            holdingStatus = holding,
            currentPrice = 0.0,
            fees = TradingFeeConfig()
        )

        assertTrue(context.hasPosition)
        assertEquals(634.0, context.entryPrice ?: 0.0, 0.001)
        assertNull("Current price must NOT fallback to entry price when 0.0", context.currentPrice)
        assertNull("Gross value must be null when current price is missing", context.grossValue)
        assertNull("Net value must be null when current price is missing", context.netValue)
        assertNull("Floating profit pct must be null when current price is missing", context.floatingProfitPct)
    }

    @Test
    fun testRsiOverboughtSellBranchIsReachableAndUnified() {
        val context = PositionContext(
            hasPosition = true,
            symbol = "BTC/IDR",
            entryPrice = 1_000_000_000.0,
            quantity = 0.1,
            currentPrice = 1_010_000_000.0, // ~1% profit, not hitting TP1 or 2% profit
            tp1 = 1_100_000_000.0,
            stopLoss = 900_000_000.0,
            floatingProfitPct = 1.0,
            floatingProfitNet = 1_000_000.0
        )
        val indicators = agu.analys.model.TechnicalIndicators(rsi14 = 78.5)

        val sellState = agu.analys.engine.sell.SellSignalEvaluator.evaluate(
            context = context,
            indicators = indicators,
            tradingFees = TradingFeeConfig(),
            high24h = 1_050_000_000.0
        )

        assertEquals(agu.analys.model.SellLifecycleState.READY_TO_SELL, sellState.state)
        assertEquals("RSI Overbought", sellState.reason)

        // Verify ReadySellBadgeEvaluator delegates and produces matching badge
        val holding = CoinHoldingStatus(
            quantity = 0.1,
            entryPrice = 1_000_000_000.0,
            tp1Price = 1_100_000_000.0,
            stopLossPrice = 900_000_000.0,
            isHolding = true
        )
        val tick = agu.analys.model.MarketTick(
            symbol = "BTC/IDR",
            price = 1_010_000_000.0,
            high24h = 1_050_000_000.0,
            low24h = 990_000_000.0,
            volume24h = 100.0,
            change24h = 1.0
        )
        val badge = agu.analys.ui.components.dashboard.ReadySellBadgeEvaluator.computeReadyBadge(
            holding = holding,
            tick = tick,
            tradingFees = TradingFeeConfig(),
            rsi = 78.5
        )
        assertNotNull(badge)
        assertEquals("⚠️ RSI OVERBOUGHT", badge!!.label)
        assertTrue(badge.isExitDecisionEvent)
    }
}
