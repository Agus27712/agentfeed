package agu.analys.engine.backtest

import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar

data class WalkForwardReport(
    val inSampleWinRatePct: Double,
    val outOfSampleWinRatePct: Double,
    val inSampleProfitFactor: Double,
    val outOfSampleProfitFactor: Double,
    val walkForwardEfficiencyPct: Double, // Rasio Out-Of-Sample vs In-Sample
    val overallScore: Int, // 0 - 100 confidence score; -1 bila data kurang (skor tidak dihitung)
    val isOverfitted: Boolean,
    val summaryMessage: String,
    /** false bila sampel kurang dari minimum trade — skor tidak boleh dipakai untuk keputusan. */
    val isDataSufficient: Boolean = true
)

object WalkForwardEvaluator {

    /** Minimum trade per sampel agar skor walk-forward bermakna (bukan 1–2 trade). */
    const val MIN_TRADES_PER_SAMPLE = 30

    /**
     * Lakukan Walk-Forward Validation dengan membagi dataset historis menjadi:
     * - In-Sample (60% data awal)
     * - Out-Of-Sample (40% data akhir)
     * Ini mendeteksi apakah sinyal berpotensi Overfitting di market sideways / volatile.
     */
    fun validate(
        candles: List<CandleBar>,
        feeConfig: TradingFeeConfig = TradingFeeConfig()
    ): WalkForwardReport {
        if (candles.size < 60) {
            return WalkForwardReport(
                inSampleWinRatePct = 0.0,
                outOfSampleWinRatePct = 0.0,
                inSampleProfitFactor = 0.0,
                outOfSampleProfitFactor = 0.0,
                walkForwardEfficiencyPct = 0.0,
                overallScore = -1,
                isOverfitted = false,
                summaryMessage = "Data historis terbatas (${candles.size} candle) — butuh minimal 60 bar untuk walk-forward.",
                isDataSufficient = false
            )
        }

        val splitIndex = (candles.size * 0.60).toInt()
        val inSampleCandles = candles.subList(0, splitIndex)
        val outOfSampleCandles = candles.subList(splitIndex, candles.size)

        val inSampleRes = BacktestEngine.runBacktest(inSampleCandles, feeConfig)
        val outOfSampleRes = BacktestEngine.runBacktest(outOfSampleCandles, feeConfig)

        // Sampel tipis = data kurang, bukan skor. Tanpa ini 1–2 trade bisa terlihat "kuat".
        if (inSampleRes.totalTrades < MIN_TRADES_PER_SAMPLE ||
            outOfSampleRes.totalTrades < MIN_TRADES_PER_SAMPLE
        ) {
            return WalkForwardReport(
                inSampleWinRatePct = inSampleRes.winRatePct,
                outOfSampleWinRatePct = outOfSampleRes.winRatePct,
                inSampleProfitFactor = inSampleRes.profitFactor,
                outOfSampleProfitFactor = outOfSampleRes.profitFactor,
                walkForwardEfficiencyPct = 0.0,
                overallScore = -1,
                isOverfitted = false,
                summaryMessage = "Data kurang (IS ${inSampleRes.totalTrades} / OOS ${outOfSampleRes.totalTrades} trade, " +
                    "butuh ≥$MIN_TRADES_PER_SAMPLE per sampel) — skor tidak dihitung.",
                isDataSufficient = false
            )
        }

        // PF 0 = tidak ada informasi (bukan 1.0). PF tak terhingga dipertahankan untuk rasio.
        val isPf = inSampleRes.profitFactor.takeIf { it > 0.0 }
        val oosPf = outOfSampleRes.profitFactor.takeIf { it > 0.0 }
        if (isPf == null || oosPf == null) {
            return WalkForwardReport(
                inSampleWinRatePct = inSampleRes.winRatePct,
                outOfSampleWinRatePct = outOfSampleRes.winRatePct,
                inSampleProfitFactor = inSampleRes.profitFactor,
                outOfSampleProfitFactor = outOfSampleRes.profitFactor,
                walkForwardEfficiencyPct = 0.0,
                overallScore = -1,
                isOverfitted = false,
                summaryMessage = "Profit factor tidak terdefinisi pada salah satu sampel — skor tidak dihitung.",
                isDataSufficient = false
            )
        }

        val efficiency = (oosPf / isPf * 100.0).coerceIn(0.0, 200.0)
        val isOverfitted = efficiency < 50.0 || (inSampleRes.winRatePct - outOfSampleRes.winRatePct) > 25.0

        // Hitung overall confidence score (drawdown hanya dinilai bila OOS punya trade — sudah dijamin ≥30 di atas)
        var score = 60
        if (outOfSampleRes.winRatePct >= 50.0) score += 15
        if (outOfSampleRes.profitFactor >= 1.3) score += 15
        if (outOfSampleRes.maxDrawdownPct <= 10.0) score += 10
        if (isOverfitted) score -= 25

        score = score.coerceIn(10, 100)

        val message = when {
            isOverfitted -> "Peringatan Overfit: Performa Out-of-Sample turun >50% dibanding In-Sample. " +
                "Sinyal TIDAK diubah otomatis — pengetatan dilakukan manual."
            score >= 75 -> "Validasi Walk-Forward Sangat Kuat: Performa stabil di data In-Sample & Out-of-Sample."
            score >= 50 -> "Validasi Walk-Forward Cukup: Konsistensi sinyal moderat di histori terbaru."
            else -> "Validasi Walk-Forward Lemah: Volatilitas tinggi / market sideways mengurangi keandalan."
        }

        return WalkForwardReport(
            inSampleWinRatePct = inSampleRes.winRatePct,
            outOfSampleWinRatePct = outOfSampleRes.winRatePct,
            inSampleProfitFactor = inSampleRes.profitFactor,
            outOfSampleProfitFactor = outOfSampleRes.profitFactor,
            walkForwardEfficiencyPct = efficiency,
            overallScore = score,
            isOverfitted = isOverfitted,
            summaryMessage = message
        )
    }
}
