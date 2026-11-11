package agu.analys.engine.global

import agu.analys.engine.indicators.IndicatorMath
import agu.analys.engine.scalping.ScalpingRuntimeConfig
import agu.analys.model.CandleBar

/**
 * P0 — BTC Market Gatekeeper (addendum KriptoYoi §5).
 *
 * Veto LONG altcoin bila salah satu:
 * 1. close BTC 5M < EMA20 **dan** return 5M terburuk dari dua candle terakhir < batas, atau
 * 2. ATR ratio BTC > batas **dan** minimal 2 dari 3 candle terakhir bearish (close < open).
 *
 * Semua ambang berasal dari [ScalpingRuntimeConfig] (`btc_*`) supaya replay dan app tidak
 * memakai dua definisi "BTC dump risk". Fungsi ini murni: tidak menyentuh jam sistem,
 * jaringan, atau state global.
 */
object BtcPulseAnalyzer {

    data class Config(
        val enabled: Boolean = true,
        val returnVetoPct: Double = -0.6,
        val atrRatioVeto: Double = 2.5,
        val emaPeriod: Int = 20
    ) {
        companion object {
            fun from(config: ScalpingRuntimeConfig): Config = Config(
                enabled = config.btcVetoEnabled,
                returnVetoPct = config.btcReturnVetoPct,
                atrRatioVeto = config.btcAtrRatioVeto,
                emaPeriod = config.btcEmaPeriod.coerceAtLeast(2)
            )
        }
    }

    data class Result(
        val vetoActive: Boolean,
        val status: String,
        val reason: String?,
        val btcPrice: Double,
        val return5mPct: Double,
        val ema20: Double,
        val atrRatio: Double,
        val bearishBars: Int,
        val confirmation1m: String? = null,
        val sufficientData: Boolean = true
    )

    fun evaluate(
        btc5mCandles: List<CandleBar>,
        btc1mCandles: List<CandleBar> = emptyList(),
        config: Config = Config()
    ): Result {
        val minBars = maxOf(22, config.emaPeriod + 2)
        if (btc5mCandles.size < minBars) {
            return Result(
                vetoActive = false,
                status = "INSUFFICIENT_DATA",
                reason = null,
                btcPrice = 0.0,
                return5mPct = 0.0,
                ema20 = 0.0,
                atrRatio = 0.0,
                bearishBars = 0,
                sufficientData = false
            )
        }

        val closes = btc5mCandles.map { it.close }
        val price = closes.last()
        val ema = IndicatorMath.ema(closes, config.emaPeriod)
        val previous = closes[closes.size - 2]
        val previous2 = closes[closes.size - 3]
        val returnC1 = if (previous > 0.0) (price - previous) / previous * 100.0 else 0.0
        val returnC2 = if (previous2 > 0.0) (previous - previous2) / previous2 * 100.0 else 0.0
        // p0 memakai return paling negatif dari dua candle terakhir.
        val worstReturnPct = minOf(returnC1, returnC2)

        val atrRatio = atrRatio(btc5mCandles, 14)
        val bearishBars = btc5mCandles.takeLast(3).count { it.close < it.open }
        val bearishImpulsive = bearishBars >= 2

        val belowEma = ema > 0.0 && price < ema
        val condition1 = belowEma && worstReturnPct < config.returnVetoPct
        val condition2 = atrRatio > config.atrRatioVeto && bearishImpulsive

        val reasons = mutableListOf<String>()
        if (condition1) {
            reasons.add(
                "BTC 5M close $price < EMA${config.emaPeriod} $ema dan return " +
                    "${fmt(worstReturnPct)}% < ${fmt(config.returnVetoPct)}%"
            )
        }
        if (condition2) {
            reasons.add(
                "BTC ATR ratio ${fmt(atrRatio)}x > ${fmt(config.atrRatioVeto)}x " +
                    "dengan $bearishBars/3 candle bearish impulsif"
            )
        }

        val confirmation1m = confirmation1m(btc1mCandles, config.emaPeriod)
        if (condition1 || condition2) {
            confirmation1m?.let { reasons.add(it) }
        }

        val veto = config.enabled && (condition1 || condition2)
        val status = when {
            veto -> "DUMP_RISK"
            belowEma && !condition1 -> "CAUTION"
            worstReturnPct < -0.3 -> "CAUTION"
            else -> "SAFE"
        }

        return Result(
            vetoActive = veto,
            status = status,
            reason = reasons.takeIf { it.isNotEmpty() }?.joinToString("; "),
            btcPrice = price,
            return5mPct = worstReturnPct,
            ema20 = ema,
            atrRatio = atrRatio,
            bearishBars = bearishBars,
            confirmation1m = confirmation1m
        )
    }

    /** Pair BTC tidak boleh mem-veto dirinya sendiri (p0: `is_btc_pair` bypass). */
    fun isBtcPair(symbol: String): Boolean {
        val normalized = symbol.uppercase().replace("/", "").replace("_", "").replace("-", "")
        return normalized.startsWith("BTC")
    }

    /**
     * ATR ratio p0: ATR% terakhir dibagi rata-rata ATR% historis (bukan ATR absolut).
     * Implementasi mengikuti `calculate_atr` p0: TR sederhana, RMA Wilder, baseline per bar.
     */
    internal fun atrRatio(candles: List<CandleBar>, period: Int = 14): Double {
        if (candles.size < period + 2 || period <= 0) return 1.0
        val tr = ArrayList<Double>(candles.size - 1)
        for (i in 1 until candles.size) {
            val high = candles[i].high
            val low = candles[i].low
            val prevClose = candles[i - 1].close
            tr.add(maxOf(high - low, kotlin.math.abs(high - prevClose), kotlin.math.abs(low - prevClose)))
        }
        if (tr.size < period) return 1.0

        val atrValues = ArrayList<Double>(tr.size - period + 1)
        atrValues.add(tr.take(period).average())
        for (i in period until tr.size) {
            atrValues.add((atrValues.last() * (period - 1) + tr[i]) / period)
        }

        val currentPrice = candles.last().close
        if (currentPrice <= 0.0) return 1.0
        val currentAtrPct = atrValues.last() / currentPrice * 100.0
        val baseline = ArrayList<Double>(atrValues.size)
        for (i in atrValues.indices) {
            val close = candles[i + period].close
            if (close > 0.0) baseline.add(atrValues[i] / close * 100.0)
        }
        val meanPct = if (baseline.isNotEmpty()) baseline.average() else currentAtrPct
        return if (meanPct > 0.0) currentAtrPct / meanPct else 1.0
    }

    private fun confirmation1m(candles: List<CandleBar>, period: Int): String? {
        if (candles.size < maxOf(22, period + 2)) return null
        val closes = candles.map { it.close }
        val ema = IndicatorMath.ema(closes, period)
        val close = closes.last()
        val prev = closes[closes.size - 2]
        val ret = if (prev > 0.0) (close - prev) / prev * 100.0 else 0.0
        return if (ema > 0.0 && close < ema && ret < -0.3) {
            "Konfirmasi 1M: close di bawah EMA$period, return ${fmt(ret)}%"
        } else {
            null
        }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
