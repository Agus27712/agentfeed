package agu.analys.engine.global

object RiskBasedPositionSizer {
    /**
     * Menghitung nilai rupiah posisi berbasis risiko akun & jarak stop loss
     * Secara universal mencegah capital drain dengan membatasi kerugian maksimal ke ~2% dari modal per trade.
     * 
     * riskAmount = accountBalance * maxRiskPerTrade
     * rawSize = riskAmount / stopDistancePct
     * positionSize = min(rawSize, accountBalance * maxSingleAssetAllocationPct)
     */
    fun calculateRecommendedSize(
        accountBalance: Double,
        entryPrice: Double,
        stopLossPrice: Double,
        maxRiskPerTradePct: Double = 0.02, // 2% resiko modal default
        maxSingleAssetAllocationPct: Double = 0.25, // Maks 25% modal dalam 1 koin
        confidenceMultiplier: Double = 1.0, // 0.0 -> 1.0 untuk scaling
        minTradeAmount: Double = 10_000.0 // Batas minimum notional order spot
    ): Double {
        if (accountBalance <= 0 || entryPrice <= 0 || stopLossPrice >= entryPrice || stopLossPrice <= 0) {
            return 0.0
        }

        val stopDistancePct = (entryPrice - stopLossPrice) / entryPrice
        if (stopDistancePct <= 0.001) return 0.0 // Terlalu ketat

        val riskBudgetAmount = accountBalance * maxRiskPerTradePct.coerceIn(0.005, 0.05)
        val rawPositionSize = riskBudgetAmount / stopDistancePct
        val allocationFactor = confidenceMultiplier.coerceIn(0.0, 1.0)

        val maxSingleAssetAllocation = accountBalance * maxSingleAssetAllocationPct
        val finalPositionSize = (rawPositionSize * allocationFactor).coerceAtMost(maxSingleAssetAllocation)

        // Validasi ketersediaan saldo dan minimum notional exchange
        val boundedSize = finalPositionSize.coerceAtMost(accountBalance).coerceAtLeast(0.0)
        return if (boundedSize > 0.0 && boundedSize < minTradeAmount) {
            if (accountBalance >= minTradeAmount) minTradeAmount else 0.0
        } else {
            boundedSize
        }
    }
}
