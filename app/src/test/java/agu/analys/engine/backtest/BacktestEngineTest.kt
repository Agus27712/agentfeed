package agu.analys.engine.backtest

import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes deterministik BacktestEngine: trigger SMA-cross, exit gap-aware,
 * single-count slippage, dan guard metrik.
 */
class BacktestEngineTest {

    private val zeroFee = TradingFeeConfig(0.0, 0.0, 0.0, 0.0)

    /** 28 bar flat (SMA20 = 100, ATR ≈ 4) + 1 bar trigger + 1 bar exit (min 30 bar). */
    private fun series(exitBar: CandleBar): List<CandleBar> {
        val bars = mutableListOf<CandleBar>()
        for (i in 0 until 28) {
            bars += CandleBar(i * 60_000L, 100.0, 102.0, 98.0, 100.0, 1000.0)
        }
        bars += CandleBar(28 * 60_000L, 100.0, 106.0, 104.0, 105.0, 1000.0) // trigger cross
        bars += exitBar
        return bars
    }

    private fun exitBar(t: Long, o: Double, h: Double, l: Double, c: Double) =
        CandleBar(t, o, h, l, c, 1000.0)

    @Test
    fun entry_hasNoSlippageMarkup_and_tpExit() {
        // SL = 105*0.985 = 103.425 (floor), TP = 105 + 2.2*4 = 113.8
        val res = BacktestEngine.runBacktest(
            series(exitBar(29 * 60_000L, 105.0, 200.0, 104.0, 150.0)),
            feeConfig = zeroFee, slippagePct = 0.0
        )
        assertEquals(1, res.totalTrades)
        val t = res.trades.single()
        assertEquals(105.0, t.entryPrice, 1e-9) // tanpa markup slippage
        assertEquals("TAKE_PROFIT", t.exitReason)
        assertEquals(113.8, t.exitPrice, 1e-9)
        assertTrue(t.isWin)
    }

    @Test
    fun sameBar_slPrioritized() {
        val res = BacktestEngine.runBacktest(
            series(exitBar(29 * 60_000L, 105.0, 200.0, 50.0, 150.0)),
            feeConfig = zeroFee, slippagePct = 0.0
        )
        assertEquals(1, res.totalTrades)
        val t = res.trades.single()
        assertEquals("STOP_LOSS", t.exitReason)
        assertEquals(103.425, t.exitPrice, 1e-9)
    }

    @Test
    fun gapDown_exitAtOpen() {
        val res = BacktestEngine.runBacktest(
            series(exitBar(29 * 60_000L, 50.0, 51.0, 49.0, 50.0)),
            feeConfig = zeroFee, slippagePct = 0.0
        )
        assertEquals(1, res.totalTrades)
        val t = res.trades.single()
        assertEquals("GAP_SL", t.exitReason)
        assertEquals(50.0, t.exitPrice, 1e-9) // bukan harga SL (lebih jujur saat gap)
    }

    @Test
    fun gapUp_exitAtOpen() {
        val res = BacktestEngine.runBacktest(
            series(exitBar(29 * 60_000L, 200.0, 201.0, 150.0, 200.0)),
            feeConfig = zeroFee, slippagePct = 0.0
        )
        assertEquals(1, res.totalTrades)
        val t = res.trades.single()
        assertEquals("GAP_TP", t.exitReason)
        assertEquals(200.0, t.exitPrice, 1e-9)
    }

    @Test
    fun netExit_singleSlippage_exactValue() {
        // Default taker fee (0.21/0.42) + slip 0.08, dihitung SEKALI.
        val net = BacktestEngine.netExitPct(
            entry = 100.0, exitPrice = 110.0,
            fees = TradingFeeConfig(), slippagePct = 0.08
        )
        val expected = ((110.0 / 100.0 * (1.0 - 0.50 / 100.0) / (1.0 + 0.29 / 100.0)) - 1.0) * 100.0
        assertEquals(expected, net, 1e-9)
    }

    @Test
    fun flatSeries_noTrades_zeroMetrics() {
        val flat = (0 until 60).map {
            CandleBar(it * 60_000L, 100.0, 100.0, 100.0, 100.0, 1000.0)
        }
        val res = BacktestEngine.runBacktest(flat, feeConfig = zeroFee, slippagePct = 0.0)
        assertEquals(0, res.totalTrades)
        assertEquals(0.0, res.expectancyPct, 1e-9)
        assertEquals(0.0, res.profitFactor, 1e-9)
    }

    @Test
    fun formatProfitFactor_infinity() {
        assertEquals("∞", BacktestEngine.formatProfitFactor(Double.POSITIVE_INFINITY))
        assertEquals("1.50", BacktestEngine.formatProfitFactor(1.5))
    }
}
