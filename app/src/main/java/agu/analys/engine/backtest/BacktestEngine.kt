package agu.analys.engine.backtest

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction
import kotlin.math.max

data class BacktestTrade(
    val entryIndex: Int,
    val entryPrice: Double,
    val stopLoss: Double,
    val targetProfit: Double,
    var exitIndex: Int = -1,
    var exitPrice: Double = 0.0,
    var pnlPct: Double = 0.0,
    var isWin: Boolean = false,
    var exitReason: String = ""
)

data class BacktestResult(
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRatePct: Double,
    val profitFactor: Double,
    val netProfitPct: Double,
    val maxDrawdownPct: Double,
    val averageRr: Double,
    val expectancyPct: Double,
    val sampleSizeBars: Int,
    val trades: List<BacktestTrade>
)

object BacktestEngine {

    /**
     * Jalankan backtest pada deretan candle historis.
     * Trigger baseline: cross di atas rata-rata 20 candle (SMA, bukan EMA) + volume > 0.
     * BUKAN engine scalping live (tanpa setup/MTF/orderbook/VWAP) — hanya baseline pembanding.
     * Biaya (fee + slippage) dihitung SATU kali via [FeeCalculator.roundTrip].
     */
    fun runBacktest(
        candles: List<CandleBar>,
        feeConfig: TradingFeeConfig = TradingFeeConfig(),
        slippagePct: Double = 0.08,
        minNetRr: Double = 1.2
    ): BacktestResult {
        if (candles.size < 30) {
            return BacktestResult(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, candles.size, emptyList())
        }

        val trades = mutableListOf<BacktestTrade>()
        var activeTrade: BacktestTrade? = null

        var peakEquity = 100.0
        var currentEquity = 100.0
        var maxDrawdown = 0.0
        var grossGains = 0.0
        var grossLosses = 0.0

        for (i in 20 until candles.size) {
            val currentBar = candles[i]

            // 1. Jika ada posisi aktif, periksa eksekusi SL atau TP (gap-aware)
            if (activeTrade != null) {
                val trade = activeTrade
                // Gap semalam: open sudah melewati level sebelum bar berjalan.
                // Stop-market terisi di open (lebih buruk dari SL); limit-TP terisi di open (lebih baik dari TP).
                val gappedBelowSl = currentBar.open <= trade.stopLoss
                val gappedAboveTp = currentBar.open >= trade.targetProfit
                val hitSl = gappedBelowSl || currentBar.low <= trade.stopLoss
                val hitTp = gappedAboveTp || currentBar.high >= trade.targetProfit

                if (hitSl || hitTp) {
                    val (exitPrice, exitReason) = when {
                        gappedBelowSl -> currentBar.open to "GAP_SL"
                        gappedAboveTp && !hitSl -> currentBar.open to "GAP_TP"
                        hitSl -> trade.stopLoss to "STOP_LOSS"
                        else -> trade.targetProfit to "TAKE_PROFIT"
                    }
                    // Same-bar SL+TP tanpa gap: SL didahulukan (konservatif).
                    val netPnlPct = netExitPct(trade.entryPrice, exitPrice, feeConfig, slippagePct)

                    trade.exitIndex = i
                    trade.exitPrice = exitPrice
                    trade.pnlPct = netPnlPct
                    trade.isWin = netPnlPct > 0.0
                    trade.exitReason = exitReason

                    trades.add(trade)
                    activeTrade = null

                    // Calculation equity
                    currentEquity *= (1.0 + netPnlPct / 100.0)
                    if (currentEquity > peakEquity) {
                        peakEquity = currentEquity
                    } else {
                        val dd = (peakEquity - currentEquity) / peakEquity * 100.0
                        if (dd > maxDrawdown) maxDrawdown = dd
                    }

                    if (netPnlPct > 0.0) grossGains += netPnlPct else grossLosses += kotlin.math.abs(netPnlPct)
                }
                continue
            }

            // 2. Jika tidak ada posisi aktif, simulasikan kondisi sinyal entry
            val prevBar = candles[i - 1]
            val recent20 = candles.subList(i - 20, i)
            // Rata-rata biasa (SMA), bukan EMA. ATR dari 10 bar terakhir window.
            val sma20 = recent20.map { it.close }.average()
            val atr = recent20.takeLast(10).map { max(it.high - it.low, 0.0001) }.average()

            // Trigger baseline: close cross ke atas SMA20 + ada volume.
            // Bukan breakout-high, bukan setup scalping — hanya pembanding deterministik.
            val isBullishTrigger = currentBar.close > sma20 && prevBar.close <= sma20 && currentBar.volume > 0
            if (isBullishTrigger && currentBar.close > 0.0) {
                // Entry di close mentah; slippage hanya dihitung sekali via FeeCalculator.roundTrip.
                val entryPrice = currentBar.close
                val stopLoss = max(entryPrice - (1.5 * atr), entryPrice * 0.985)
                val targetProfit = entryPrice + (2.2 * atr)

                val feeRes = FeeCalculator.roundTrip(entryPrice, stopLoss, targetProfit, feeConfig, false, slippagePct)
                if (feeRes.netRr >= minNetRr) {
                    activeTrade = BacktestTrade(
                        entryIndex = i,
                        entryPrice = entryPrice,
                        stopLoss = stopLoss,
                        targetProfit = targetProfit
                    )
                }
            }
        }

        val totalTrades = trades.size
        val winningTrades = trades.count { it.isWin }
        val losingTrades = totalTrades - winningTrades
        val winRate = if (totalTrades > 0) (winningTrades.toDouble() / totalTrades) * 100.0 else 0.0
        val profitFactor = if (grossLosses > 0.0) grossGains / grossLosses
            else if (grossGains > 0.0) Double.POSITIVE_INFINITY else 0.0
        val netProfitPct = currentEquity - 100.0
        // Guard bagi-nol: lewati trade dengan risk 0 agar metrik tidak jadi Infinity/NaN.
        val rrValues = trades.mapNotNull {
            val riskDenom = it.entryPrice - it.stopLoss
            if (riskDenom > 0.0) kotlin.math.abs((it.targetProfit - it.entryPrice) / riskDenom) else null
        }
        val avgRr = if (rrValues.isNotEmpty()) rrValues.average() else 0.0
        val expectancy = if (totalTrades > 0) trades.map { it.pnlPct }.average() else 0.0

        return BacktestResult(
            totalTrades = totalTrades,
            winningTrades = winningTrades,
            losingTrades = losingTrades,
            winRatePct = winRate,
            profitFactor = profitFactor,
            netProfitPct = netProfitPct,
            maxDrawdownPct = maxDrawdown,
            averageRr = avgRr,
            expectancyPct = expectancy,
            sampleSizeBars = candles.size,
            trades = trades
        )
    }

    /**
     * PnL bersih % untuk harga exit aktual, dengan faktor biaya yang sama
     * seperti [FeeCalculator.roundTrip] (fee + slippage dihitung SATU kali).
     */
    fun netExitPct(
        entry: Double,
        exitPrice: Double,
        fees: TradingFeeConfig,
        slippagePct: Double,
        useMaker: Boolean = false
    ): Double {
        if (entry <= 0.0 || exitPrice <= 0.0) return 0.0
        val buyFee = if (useMaker) fees.buyMakerPct else fees.buyTakerPct
        val sellFee = if (useMaker) fees.sellMakerPct else fees.sellTakerPct
        val buyCostFactor = 1.0 + (buyFee + slippagePct) / 100.0
        val sellNetFactor = (1.0 - (sellFee + slippagePct) / 100.0).coerceAtLeast(0.0)
        return ((exitPrice / entry * sellNetFactor / buyCostFactor) - 1.0) * 100.0
    }

    /** Format profit factor: tak terhingga ditampilkan "∞", bukan angka sentinel. */
    fun formatProfitFactor(pf: Double): String =
        if (pf.isInfinite()) "∞" else String.format(java.util.Locale.US, "%.2f", pf)
}
