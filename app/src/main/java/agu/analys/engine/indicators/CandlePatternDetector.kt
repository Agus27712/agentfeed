package agu.analys.engine.indicators

import agu.analys.model.CandleBar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Candlestick pattern recognition — pure function, no state. */
object CandlePatternDetector {

    fun detect(history: List<CandleBar>): String? {
        if (history.size < 2) return null
        val previous = history[history.lastIndex - 1]
        val current = history.last()
        val prevBull = previous.close > previous.open
        val prevBear = previous.close < previous.open
        val currBull = current.close > current.open
        val currBear = current.close < current.open

        // 3-candle pattern: Morning Star
        if (history.size >= 3) {
            val c1 = history[history.lastIndex - 2]
            val c2 = previous
            val c3 = current
            val c1Bear = c1.close < c1.open
            val c2Doji = abs(c2.close - c2.open) <= (c2.high - c2.low) * 0.35
            val c3Bull = c3.close > c3.open && c3.close >= (c1.open + c1.close) / 2.0
            if (c1Bear && c2Doji && c3Bull) {
                return "Morning Star"
            }
        }

        if (prevBear && currBull && current.open <= previous.close && current.close >= previous.open) {
            return "Bullish Engulfing"
        }
        if (prevBull && currBear && current.open >= previous.close && current.close <= previous.open) {
            return "Bearish Engulfing"
        }

        val body = abs(current.close - current.open)
        val lowerWick = min(current.open, current.close) - current.low
        val upperWick = current.high - max(current.open, current.close)
        val totalRange = (current.high - current.low).coerceAtLeast(1e-9)

        if (body > 0 && lowerWick >= body * 2 && upperWick < body) return "Hammer"
        if (lowerWick >= totalRange * 0.60 && upperWick <= totalRange * 0.15) return "Bullish Pin Bar"
        if (body > 0 && upperWick >= body * 2 && lowerWick < body) return "Shooting Star"
        return null
    }
}
