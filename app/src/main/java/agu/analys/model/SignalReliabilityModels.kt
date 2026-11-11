package agu.analys.model

import agu.analys.database.SignalLogEntity

/**
 * Filter options for the Signal Log interface
 */
enum class SignalLogFilter(val label: String) {
    ALL("Semua"),
    WIN_HIT_TP("Menang (TP)"),
    LOSS_HIT_SL("Kalah (SL)"),
    TRACKING("Sedang Dipantau"),
    BUY_ONLY("Sinyal Beli"),
    SELL_ONLY("Sinyal Jual")
}

/**
 * Statistik akurasi per kelompok (setup scalping atau kategori skor engine).
 * Hanya sinyal yang sudah selesai (bukan TRACKING) dihitung untuk win rate.
 */
data class SetupStats(
    val key: String,            // mis. "BREAKOUT" atau "STRONG"
    val totalSignals: Int,
    val trackingCount: Int,
    val resolvedCount: Int,
    val winCount: Int,
    val lossCount: Int,
    val winRatePct: Double,
    val avgReturnPct: Double,   // rata-rata realized PnL % (sebelum fee)
    val profitFactor: Double
) {
    /** Sampel terlalu kecil untuk disimpulkan. */
    val isSmallSample: Boolean get() = resolvedCount < MIN_RELIABLE_SAMPLE

    companion object {
        const val MIN_RELIABLE_SAMPLE = 10
    }
}

/**
 * Comprehensive summary of signal reliability and statistical outcomes
 */
data class SignalReliabilitySummary(
    val totalLogs: Int = 0,
    val completedLogs: Int = 0,
    val winCount: Int = 0,
    val lossCount: Int = 0,
    val trackingCount: Int = 0,
    val overallWinRatePct: Double = 0.0,
    val avgProfitPct: Double = 0.0,
    val avgLossPct: Double = 0.0,
    val profitFactor: Double = 0.0,
    val maxSingleWinPct: Double = 0.0,
    val maxSingleLossPct: Double = 0.0,
    val bestPerformingSymbol: String = "-",
    val bestStrategyMode: String = "-",
    // Pipeline scalping: akurasi per setup & per kategori skor
    val setupStats: List<SetupStats> = emptyList(),
    val scoreCategoryStats: List<SetupStats> = emptyList(),
    /** Sinyal lama (sebelum update engine) tanpa data setup/skor. */
    val legacySignalCount: Int = 0
)
