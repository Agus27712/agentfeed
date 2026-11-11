package agu.analys.model

enum class SellLifecycleState(val displayName: String) {
    NOT_HOLDING("TIDAK PUNYA POSISI"),
    MONITORING("MEMANTAU"),
    APPROACHING_TARGET("MENDEKATI TARGET"),
    READY_TO_SELL("SIAP JUAL"),
    STOP_LOSS_HIT("STOP LOSS TERSENTUH"),
    RAPID_DROP_EXIT("RAPID DROP TERDETEKSI"),
    TRAILING_TRIGGERED("TRAILING STOP TERPICU")
}

data class RapidDropConfig(
    val drop1mExitPct: Double = 3.0,
    val drop5mExitPct: Double = 5.0,
    val drawdownFromPeakPct: Double = 4.0,
    val velocityExitPctPerMinute: Double = 2.0,
    val minimumSellPressureRatio: Double = 0.70
)

data class SellRiskSnapshot(
    val currentPrice: Double,
    val price1mAgo: Double? = null,
    val price5mAgo: Double? = null,
    val peakPrice: Double? = null,
    val sellPressureRatio: Double? = null,
    val priceVelocityPctPerMinute: Double? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class SellSignalState(
    val state: SellLifecycleState = SellLifecycleState.NOT_HOLDING,
    val reason: String = "",
    val netProfitPct: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis()
)
