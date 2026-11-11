package agu.analys.engine.regime

import agu.analys.model.CandleBar
import kotlin.math.max

data class MacroAnomalyResult(
    val isParabolicUnwind: Boolean,
    val pumpMultiple: Double,       // longTermHigh / typicalBaseline
    val drawdownFromPeakPct: Double,
    val daysSincePeak: Int,
    val confidencePenalty: Int      // 0 = none, up to -60
)

object MacroAnomalyDetector {
    fun evaluate(longTermCandles: List<CandleBar>, currentPrice: Double): MacroAnomalyResult {
        if (longTermCandles.isEmpty()) {
            return MacroAnomalyResult(false, 1.0, 0.0, 0, 0)
        }

        // typicalBaseline: use a robust measure (e.g. 25th percentile of closes,
        // or median of the lower half) so one spike doesn't distort its own baseline
        val closes = longTermCandles.map { it.close }.sorted()
        val typicalBaseline = closes.take(max(1, closes.size / 4)).average()
        val peakCandle = longTermCandles.maxByOrNull { it.high }
        val longTermHigh = peakCandle?.high ?: currentPrice
        
        val pumpMultiple = if (typicalBaseline > 0) longTermHigh / typicalBaseline else 1.0
        val drawdownFromPeakPct = if (longTermHigh > 0) (longTermHigh - currentPrice) / longTermHigh * 100.0 else 0.0
        
        val latestCandleTime = longTermCandles.last().timestamp
        val peakCandleTime = peakCandle?.timestamp ?: latestCandleTime
        // In days
        val daysSincePeak = max(0, ((latestCandleTime - peakCandleTime) / (1000 * 60 * 60 * 24)).toInt())

        // Still "in the unwind" if pump was extreme, drop is large, and it happened recently
        val isParabolicUnwind = pumpMultiple >= 3.0 &&
            drawdownFromPeakPct >= 40.0 &&
            daysSincePeak in 0..90

        val penalty = when {
            !isParabolicUnwind -> 0
            pumpMultiple >= 6.0 -> -60
            pumpMultiple >= 4.0 -> -45
            else -> -30
        }

        return MacroAnomalyResult(isParabolicUnwind, pumpMultiple, drawdownFromPeakPct, daysSincePeak, penalty)
    }
}
