package agu.analys.engine.scalping

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.backtest.BacktestEngine
import agu.analys.engine.backtest.BacktestResult
import agu.analys.model.CandleBar

/**
 * P3 — adapter backtest scalping.
 *
 * Memakai [BacktestEngine] existing + threshold [ScalpingConfig].
 * **Bukan** Historical Edge palsu: hasil hanya metrik backtest pada sample yang diberikan.
 * Untuk edge produksi, butuh data historis nyata + journal (Phase 5 spek).
 */
object ScalpingBacktestAdapter {

    data class Report(
        val result: BacktestResult,
        val minNetRrUsed: Double,
        val slippagePctUsed: Double,
        val note: String
    )

    /**
     * Jalankan backtest sederhana pada candle historis.
     * Trigger internal BacktestEngine masih sederhana (EMA breakout) —
     * ini baseline deterministik, bukan klaim edge setup KriptoYoi penuh.
     */
    fun run(
        candles: List<CandleBar>,
        fees: TradingFeeConfig = TradingFeeConfig(),
        slippagePct: Double = ScalpingConfig.DEFAULT_SLIPPAGE_PCT,
        minNetRr: Double = ScalpingConfig.MIN_NET_RR
    ): Report {
        val result = BacktestEngine.runBacktest(
            candles = candles,
            feeConfig = fees,
            slippagePct = slippagePct,
            minNetRr = minNetRr
        )
        val note = when {
            candles.size < 30 ->
                "Sample terlalu kecil (<30 bar). Metrik tidak representatif."
            result.totalTrades == 0 ->
                "Tidak ada trade yang lolos filter Net R:R ≥ $minNetRr setelah fee+slippage."
            else ->
                "Backtest baseline (EMA trigger). Win rate ${fmt(result.winRatePct)}%, " +
                    "expectancy ${fmt(result.expectancyPct)}%/trade, PF ${fmt(result.profitFactor)}. " +
                    "Bukan Historical Edge setup-spesifik — validasi dengan data exchange nyata."
        }
        return Report(
            result = result,
            minNetRrUsed = minNetRr,
            slippagePctUsed = slippagePct,
            note = note
        )
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
