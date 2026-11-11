package agu.analys.engine.sell

import agu.analys.config.TradingFeeConfig
import agu.analys.model.MarketTick
import agu.analys.model.SellLifecycleState
import agu.analys.model.SellSignalState
import agu.analys.model.TechnicalIndicators
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SellSignalEvaluatorTest {

    /**
     * Konfigurasi **tanpa biaya apa pun**, dipakai uji ambang keputusan.
     *
     * Sejak 2026-10-15 `SellSignalEvaluator` memakai basis **taker + slippage dua sisi** — sama
     * dengan FSM paper trade — bukan lagi fee jual maker saja. Uji di kelas ini menguji *ambang
     * keputusan*, bukan besaran biaya, jadi seluruh komponen biaya dinolkan: keempat tarif fee
     * **dan** `slippagePct` yang kini menjadi parameter eksplisit. Besaran biayanya sendiri diuji
     * di `NetPnlParityTest`.
     */
    private val tanpaBiaya = TradingFeeConfig(
        buyMakerPct = 0.0,
        buyTakerPct = 0.0,
        sellMakerPct = 0.0,
        sellTakerPct = 0.0
    )

    @Test
    fun testNotHolding() {
        val position = SpotPosition(state = SpotPositionState.NO_POSITION)
        val tick = MarketTick("BTCIDR", 100000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, TradingFeeConfig())
        
        assertEquals(SellLifecycleState.NOT_HOLDING, result.state)
    }

    @Test
    fun testNotHolding_ZeroQuantity() {
        val position = SpotPosition(state = SpotPositionState.HOLDING, quantity = 0.0)
        val tick = MarketTick("BTCIDR", 100000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, TradingFeeConfig())
        
        assertEquals(SellLifecycleState.NOT_HOLDING, result.state)
    }

    @Test
    fun testMonitoring() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0
        )
        // Price is slightly down or break-even, so net profit is < 0 due to fees
        val tick = MarketTick("BTCIDR", 100000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, TradingFeeConfig())
        
        assertEquals(SellLifecycleState.MONITORING, result.state)
    }

    @Test
    fun testReadyToSell_HighProfit() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0
        )
        // Price increased by 10%
        val tick = MarketTick("BTCIDR", 110000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, tanpaBiaya, slippagePct = 0.0)
        
        assertEquals(SellLifecycleState.READY_TO_SELL, result.state)
        assertEquals(10.0, result.netProfitPct, 0.01)
        assertEquals("Profit +5%", result.reason)
    }

    @Test
    fun testReadyToSell_TP1Reached() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0,
            tp1Price = 103000.0
        )
        val tick = MarketTick("BTCIDR", 103000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, tanpaBiaya, slippagePct = 0.0)
        
        assertEquals(SellLifecycleState.READY_TO_SELL, result.state)
        assertEquals("Target TP1 tercapai", result.reason)
        assertEquals(3.0, result.netProfitPct, 0.01)
    }

    @Test
    fun testReadyToSell_Near24hHigh() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0
        )
        // 24h high is 102000, current price is 101500 (>= 102000 * 0.98 = 99960) and profit is 1.5%
        val tick = MarketTick("BTCIDR", 101500.0, 102000.0, 95000.0, 1000.0, 1.5)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, tanpaBiaya, slippagePct = 0.0)
        
        assertEquals(SellLifecycleState.READY_TO_SELL, result.state)
        assertEquals("Dekat High 24j", result.reason)
    }

    @Test
    fun testReadyToSell_RsiOverbought() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0
        )
        // Slightly profitable (0.5%), RSI is 75 (overbought)
        val tick = MarketTick("BTCIDR", 100500.0, 105000.0, 95000.0, 1000.0, 0.5)
        val indicators = TechnicalIndicators(rsi14 = 75.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, indicators, tanpaBiaya, slippagePct = 0.0)
        
        assertEquals(SellLifecycleState.READY_TO_SELL, result.state)
        assertEquals("RSI Overbought", result.reason)
    }

    @Test
    fun testTrailingTriggered() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0,
            isTrailingEnabled = true,
            isTrailingTriggered = true
        )
        val tick = MarketTick("BTCIDR", 105000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, tanpaBiaya, slippagePct = 0.0)
        
        assertEquals(SellLifecycleState.TRAILING_TRIGGERED, result.state)
        assertEquals("Trailing stop terpicu", result.reason)
    }

    @Test
    fun testReadyToSell_TP2Reached() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0,
            tp2Price = 105000.0
        )
        val tick = MarketTick("BTCIDR", 105000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, tanpaBiaya, slippagePct = 0.0)
        
        assertEquals(SellLifecycleState.READY_TO_SELL, result.state)
        assertEquals("Target TP2 tercapai", result.reason)
        assertEquals(5.0, result.netProfitPct, 0.01)
    }

    @Test
    fun testStopLossHit_CalculatedFromEntryMinus1Percent() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0 // Default SL is 100000 * 0.99 = 99000
        )
        // Price drops to 98900 (<= 99000)
        val tick = MarketTick("BTCIDR", 98900.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, tanpaBiaya, slippagePct = 0.0)
        
        assertEquals(SellLifecycleState.STOP_LOSS_HIT, result.state)
        assertEquals("Stop loss aplikasi terpicu", result.reason)
    }

    @Test
    fun testRapidDropExit_1mDrop() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 95000.0 // SL is 95000 * 0.99 = 94050. Current price 97000 is above SL.
        )
        // Current price 97000, 1m ago 101000 -> drop ~3.96% (>= 3.0%)
        val tick = MarketTick("BTCIDR", 97000.0, 0.0, 0.0, 0.0, 0.0)
        val snapshot = agu.analys.model.SellRiskSnapshot(
            currentPrice = 97000.0,
            price1mAgo = 101000.0
        )
        val result = SellSignalEvaluator.evaluate(
            position = position,
            tick = tick,
            indicators = null,
            tradingFees = tanpaBiaya,
            slippagePct = 0.0,
            riskSnapshot = snapshot
        )

        assertEquals(SellLifecycleState.RAPID_DROP_EXIT, result.state)
        assertTrue(result.reason.contains("Drop 1m"))
    }

    @Test
    fun testRapidDropExit_DrawdownFromPeak() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0,
            peakPrice = 110000.0
        )
        // Current price 105000 (still profit vs entry 100k, but drawdown from peak 110k is ~4.5% >= 4.0%)
        val tick = MarketTick("BTCIDR", 105000.0, 0.0, 0.0, 0.0, 0.0)
        val snapshot = agu.analys.model.SellRiskSnapshot(
            currentPrice = 105000.0,
            peakPrice = 110000.0
        )
        val result = SellSignalEvaluator.evaluate(
            position = position,
            tick = tick,
            indicators = null,
            tradingFees = tanpaBiaya,
            slippagePct = 0.0,
            riskSnapshot = snapshot
        )

        assertEquals(SellLifecycleState.RAPID_DROP_EXIT, result.state)
        assertTrue(result.reason.contains("Drawdown Peak"))
    }

    @Test
    fun testRapidDropExit_PriorityOverProfit() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0,
            tp1Price = 105000.0 // target TP1 105000
        )
        // Current price is 106000 (above TP1!), but price dropped rapidly from 110000 in 1 minute (-3.6%)
        val tick = MarketTick("BTCIDR", 106000.0, 0.0, 0.0, 0.0, 0.0)
        val snapshot = agu.analys.model.SellRiskSnapshot(
            currentPrice = 106000.0,
            price1mAgo = 110000.0
        )
        val result = SellSignalEvaluator.evaluate(
            position = position,
            tick = tick,
            indicators = null,
            tradingFees = tanpaBiaya,
            slippagePct = 0.0,
            riskSnapshot = snapshot
        )

        // Rapid Drop Exit MUST take precedence over TP1 to avoid getting dumped on
        assertEquals(SellLifecycleState.RAPID_DROP_EXIT, result.state)
    }

    @Test
    fun testApproachingTP1() {
        val position = SpotPosition(
            state = SpotPositionState.HOLDING,
            quantity = 1.0,
            entryPrice = 100000.0,
            tp1Price = 100000.0 * 1.05 // 105000
        )
        // Current price is 103000 (>= 105000 * 0.98 = 102900, < 105000)
        val tick = MarketTick("BTCIDR", 103000.0, 0.0, 0.0, 0.0, 0.0)
        
        val result = SellSignalEvaluator.evaluate(position, tick, null, tanpaBiaya, slippagePct = 0.0)
        
        assertEquals(SellLifecycleState.APPROACHING_TARGET, result.state)
        assertEquals("Mendekati target TP1", result.reason)
    }

    @Test
    fun testLifecycleManager_TransitionDetectionAndDeduplication() {
        val symbol = "TESTIDR"
        SellSignalLifecycleManager.reset(symbol)

        // 1st tick: Monitoring -> should not trigger notification
        val stateMonitoring = SellSignalState(state = SellLifecycleState.MONITORING)
        val t1 = SellSignalLifecycleManager.process(symbol, stateMonitoring)
        assertFalse(t1.hasTriggeringTransition)

        // 2nd tick: Transition to READY_TO_SELL -> should trigger edge notification
        val stateReady = SellSignalState(state = SellLifecycleState.READY_TO_SELL, reason = "Target TP1 tercapai", netProfitPct = 4.5)
        val t2 = SellSignalLifecycleManager.process(symbol, stateReady)
        assertTrue(t2.hasTriggeringTransition)
        assertTrue(t2.isNewReadyToSell)

        // 3rd tick: Still READY_TO_SELL -> should NOT trigger duplicate notification
        val t3 = SellSignalLifecycleManager.process(symbol, stateReady)
        assertFalse(t3.hasTriggeringTransition)
        assertFalse(t3.isNewReadyToSell)

        // Transition to TRAILING_TRIGGERED -> should trigger edge notification
        val stateTrailing = SellSignalState(state = SellLifecycleState.TRAILING_TRIGGERED, reason = "Trailing stop terpicu")
        val t4 = SellSignalLifecycleManager.process(symbol, stateTrailing)
        assertTrue(t4.hasTriggeringTransition)
        assertTrue(t4.isNewTrailingTriggered)

        // Reset when sold
        SellSignalLifecycleManager.reset(symbol)
        
        // After reset, new READY_TO_SELL should trigger again
        val t5 = SellSignalLifecycleManager.process(symbol, stateReady)
        assertTrue(t5.hasTriggeringTransition)
        assertTrue(t5.isNewReadyToSell)
    }
}
