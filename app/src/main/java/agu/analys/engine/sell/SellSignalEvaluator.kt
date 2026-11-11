package agu.analys.engine.sell

import agu.analys.config.FeeSchedule
import agu.analys.config.TradingFeeConfig
import agu.analys.model.MarketTick
import agu.analys.model.PositionContext
import agu.analys.model.RapidDropConfig
import agu.analys.model.SellLifecycleState
import agu.analys.model.SellRiskSnapshot
import agu.analys.model.SellSignalState
import agu.analys.model.TechnicalIndicators
import agu.analys.trading.SpotPosition
import java.util.Locale

object SellSignalEvaluator {
    private fun percentageDrop(oldPrice: Double, currentPrice: Double): Double {
        if (oldPrice <= 0.0) return 0.0
        return ((oldPrice - currentPrice) / oldPrice) * 100.0
    }

    fun evaluate(
        context: PositionContext,
        indicators: TechnicalIndicators? = null,
        tradingFees: TradingFeeConfig = TradingFeeConfig(),
        high24h: Double = 0.0,
        riskSnapshot: SellRiskSnapshot? = null,
        rapidDropConfig: RapidDropConfig = RapidDropConfig(),
        /**
         * Asumsi slippage per sisi (%). Dibuat parameter, bukan konstanta tersembunyi, supaya
         * basis biaya yang dipakai radar ini terlihat jelas di pemanggil dan bisa diuji tanpa
         * harus menebak — lihat `NetPnlParityTest`.
         */
        slippagePct: Double = FeeSchedule.DEFAULT_SLIPPAGE_PCT
    ): SellSignalState {
        // 1. Tidak memiliki posisi
        if (!context.hasPosition || (context.quantity ?: 0.0) <= 0.00000001) {
            return SellSignalState(state = SellLifecycleState.NOT_HOLDING)
        }

        // 2. Harga tidak valid
        val currentPrice = context.currentPrice ?: 0.0
        if (currentPrice <= 0.0) return SellSignalState(state = SellLifecycleState.MONITORING)

        val quantity = context.quantity ?: 0.0
        val entryPrice = context.entryPrice
        val hasCostBasis = entryPrice != null && entryPrice > 0.0

        // Basis biaya tunggal (2026-10-15): **taker + slippage dua sisi**, sama persis dengan FSM
        // paper trade. Sebelumnya radar ini hanya memotong fee jual maker dan mengabaikan fee beli
        // serta slippage, sehingga melaporkan profit yang lebih besar daripada yang diakui mesin
        // exit untuk posisi yang sama. Karena ambang di bawah memakai `netProfitPct`, perubahan ini
        // membuatnya sedikit lebih ketat — lihat catatan ambang di `AUDIT-2026-11-08.md` §24.
        val costBasis = if (hasCostBasis) {
            FeeSchedule.costBasisNet(
                quantity = quantity,
                entry = entryPrice!!,
                buyFeePct = tradingFees.buyTakerPct,
                slippagePct = slippagePct
            )
        } else {
            0.0
        }

        val netSell = FeeSchedule.netSellValue(
            quantity = quantity,
            price = currentPrice,
            sellFeePct = tradingFees.sellTakerPct,
            slippagePct = slippagePct
        )

        val netProfitPct = if (hasCostBasis && costBasis > 0.0) {
            val netProfitIdr = netSell - costBasis
            (netProfitIdr / costBasis) * 100.0
        } else {
            0.0
        }

        val tp1 = context.tp1 ?: 0.0
        val tp2 = context.tp2 ?: 0.0
        val sl = context.stopLoss ?: if (hasCostBasis && entryPrice != null && entryPrice > 0.0) entryPrice * 0.99 else 0.0
        val rsi = indicators?.rsi14

        // 3. Stop Loss Berbasis Perhitungan Aplikasi (Evaluasi Darurat #1)
        if (sl > 0.0 && currentPrice <= sl) {
            return SellSignalState(
                state = SellLifecycleState.STOP_LOSS_HIT,
                reason = "Stop loss aplikasi terpicu",
                netProfitPct = netProfitPct
            )
        }

        // 4. Rapid Drop Exit (Evaluasi Darurat #2: Deteksi Penurunan Cepat / Flash Dump)
        val snapshot = riskSnapshot ?: context.riskSnapshot
        if (snapshot != null && snapshot.currentPrice > 0.0) {
            val cur = snapshot.currentPrice
            val drop1m = snapshot.price1mAgo?.let { percentageDrop(it, cur) } ?: 0.0
            val drop5m = snapshot.price5mAgo?.let { percentageDrop(it, cur) } ?: 0.0
            val effectivePeak = snapshot.peakPrice ?: context.peakPrice
            val drawdownFromPeak = effectivePeak?.let { percentageDrop(it, cur) } ?: 0.0
            val velocity = snapshot.priceVelocityPctPerMinute ?: (if (drop1m > 0.0) drop1m else 0.0)
            val sellPressure = snapshot.sellPressureRatio ?: 0.0

            val isDrop1mHit = drop1m >= rapidDropConfig.drop1mExitPct
            val isDrop5mHit = drop5m >= rapidDropConfig.drop5mExitPct
            val isDrawdownHit = drawdownFromPeak >= rapidDropConfig.drawdownFromPeakPct && (effectivePeak ?: 0.0) > (entryPrice ?: 0.0)
            val isVelocityHit = velocity >= rapidDropConfig.velocityExitPctPerMinute && drop1m >= 1.0
            val isSellPressureHigh = sellPressure >= rapidDropConfig.minimumSellPressureRatio && drop1m >= 1.5

            if (isDrop1mHit || isDrop5mHit || isDrawdownHit || isVelocityHit || isSellPressureHigh) {
                val detailReason = when {
                    isDrop1mHit -> "Drop 1m (-${String.format(Locale.US, "%.1f", drop1m)}%)"
                    isDrawdownHit -> "Drawdown Peak (-${String.format(Locale.US, "%.1f", drawdownFromPeak)}%)"
                    isDrop5mHit -> "Drop 5m (-${String.format(Locale.US, "%.1f", drop5m)}%)"
                    isVelocityHit -> "Kecepatan drop (-${String.format(Locale.US, "%.1f", velocity)}%/m)"
                    else -> "Tekanan jual orderbook (${String.format(Locale.US, "%.0f", sellPressure * 100)}%)"
                }
                return SellSignalState(
                    state = SellLifecycleState.RAPID_DROP_EXIT,
                    reason = "Rapid drop terdeteksi: $detailReason",
                    netProfitPct = netProfitPct
                )
            }
        }

        // 5. Trailing Stop Triggered
        if (context.isTrailingTriggered) {
            return SellSignalState(
                state = SellLifecycleState.TRAILING_TRIGGERED,
                reason = "Trailing stop terpicu",
                netProfitPct = netProfitPct
            )
        }

        // 6. Take Profit 2 Target Reached
        if (tp2 > 0.0 && currentPrice >= tp2 && (!hasCostBasis || netProfitPct > 0.0)) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Target TP2 tercapai",
                netProfitPct = netProfitPct
            )
        }

        // 7. Take Profit 1 Target Reached
        if (tp1 > 0.0 && currentPrice >= tp1 && (!hasCostBasis || netProfitPct > 0.0)) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Target TP1 tercapai",
                netProfitPct = netProfitPct
            )
        }

        // 8. Approaching TP1 / TP2 Target
        if (tp1 > 0.0 && currentPrice >= tp1 * 0.98 && currentPrice < tp1) {
            return SellSignalState(
                state = SellLifecycleState.APPROACHING_TARGET,
                reason = "Mendekati target TP1",
                netProfitPct = netProfitPct
            )
        } else if (tp2 > 0.0 && tp1 <= 0.0 && currentPrice >= tp2 * 0.98 && currentPrice < tp2) {
            return SellSignalState(
                state = SellLifecycleState.APPROACHING_TARGET,
                reason = "Mendekati target TP2",
                netProfitPct = netProfitPct
            )
        }

        // 9. RSI Overbought
        if (rsi != null && !rsi.isNaN() && rsi.isFinite() && rsi >= 70.0 && (!hasCostBasis || netProfitPct > 0.0)) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "RSI Overbought",
                netProfitPct = netProfitPct
            )
        }

        // 10. Near 24h High with positive profit
        if (high24h > 0.0 && currentPrice >= high24h * 0.98 && hasCostBasis && netProfitPct >= 1.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Dekat High 24j",
                netProfitPct = netProfitPct
            )
        }

        // 11. Target Net Profit jika TP1 belum diset
        if (hasCostBasis && netProfitPct >= 5.0) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Profit +5%",
                netProfitPct = netProfitPct
            )
        }

        if (tp1 <= 0.0 && hasCostBasis && netProfitPct >= 3.5) {
            return SellSignalState(
                state = SellLifecycleState.READY_TO_SELL,
                reason = "Target Swing +3.5% Tercapai",
                netProfitPct = netProfitPct
            )
        }

        // Jika profit positif tapi belum menyentuh target exit (TP1 / Swing Target):
        // Tetap dalam status MONITORING (bukan READY_TO_SELL prematur)
        val monitoringReason = if (hasCostBasis && netProfitPct > 0.0) {
            "Floating Profit +${String.format(Locale.US, "%.2f", netProfitPct)}% (Memantau)"
        } else if (hasCostBasis && netProfitPct < 0.0) {
            "Drawdown ${String.format(Locale.US, "%.2f", netProfitPct)}% (Dalam Batas)"
        } else {
            "Memantau..."
        }

        // 12. Monitoring
        return SellSignalState(
            state = SellLifecycleState.MONITORING,
            reason = monitoringReason,
            netProfitPct = netProfitPct
        )
    }

    /**
     * Overload convenience yang mendelegasikan secara langsung ke Single Source of Truth [evaluate]
     */
    fun evaluate(
        position: SpotPosition,
        tick: MarketTick?,
        indicators: TechnicalIndicators?,
        tradingFees: TradingFeeConfig,
        riskSnapshot: SellRiskSnapshot? = null,
        rapidDropConfig: RapidDropConfig = RapidDropConfig(),
        slippagePct: Double = FeeSchedule.DEFAULT_SLIPPAGE_PCT
    ): SellSignalState {
        val context = PositionContext.create(
            symbol = tick?.symbol ?: "",
            spotPosition = position,
            holdingStatus = null,
            currentPrice = tick?.price ?: 0.0,
            fees = tradingFees
        )
        return evaluate(
            context = context,
            indicators = indicators,
            tradingFees = tradingFees,
            high24h = tick?.high24h ?: 0.0,
            riskSnapshot = riskSnapshot,
            rapidDropConfig = rapidDropConfig,
            slippagePct = slippagePct
        )
    }
}
