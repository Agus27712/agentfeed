package agu.analys.model

import androidx.compose.ui.graphics.Color

data class ReadySellCoinSummary(
    val pair: TradingPair,
    val quantity: Double,
    val entryPrice: Double,
    val currentPrice: Double,
    val profitPct: Double,
    val profitIdr: Double,
    val cashOutValueIdr: Double,
    val badgeLabel: String,
    val badgeColor: Color = Color.Unspecified,
    val isReal: Boolean = false
)

data class BatchSellItemResult(
    val symbol: String,
    val baseAsset: String,
    val quantity: Double,
    val price: Double,
    val success: Boolean,
    val message: String,
    val profitIdr: Double
)

data class BatchResultSummary(
    val isRealMode: Boolean,
    val totalItems: Int,
    val successCount: Int,
    val failedCount: Int,
    val totalEstimatedProfitIdr: Double,
    val totalCashOutIdr: Double,
    val itemResults: List<BatchSellItemResult>
)

sealed class BatchExecutionState {
    object Idle : BatchExecutionState()
    
    data class InProgress(
        val totalItems: Int,
        val currentIndex: Int,
        val currentSymbol: String,
        val successCount: Int,
        val failedCount: Int,
        val message: String
    ) : BatchExecutionState()

    data class Completed(
        val summary: BatchResultSummary
    ) : BatchExecutionState()

    data class Error(val message: String) : BatchExecutionState()
}
