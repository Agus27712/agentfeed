package agu.analys.engine.regime

import kotlin.math.abs

/** Detects market regime from indicator snapshot. */
object MarketRegimeDetector {

    fun detect(
        price: Double,
        emaFast: Double,
        emaSlow: Double,
        macdHist: Double,
        rsi: Double,
        atr: Double,
        bbLower: Double,
        bbUpper: Double
    ): String {
        val emaGapPct = if (emaSlow != 0.0) abs(emaFast - emaSlow) / emaSlow * 100.0 else 0.0
        val bbWidthPct = if (price != 0.0) (bbUpper - bbLower) / price * 100.0 else 0.0
        val atrPct = if (price != 0.0) atr / price * 100.0 else 0.0
        return when {
            emaGapPct < 0.25 && abs(macdHist) < atr * 0.03 && rsi in 45.0..55.0 -> "SIDEWAYS / NO TRADE"
            atrPct > 4.0 || bbWidthPct > 12.0 -> "HIGH VOLATILITY"
            price > emaFast && emaFast > emaSlow && macdHist >= 0.0 -> "TRENDING UP"
            price < emaFast && emaFast < emaSlow && macdHist <= 0.0 -> "TRENDING DOWN"
            else -> "TRANSITION / MIXED"
        }
    }
}
