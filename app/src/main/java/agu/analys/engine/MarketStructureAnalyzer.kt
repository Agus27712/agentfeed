package agu.analys.engine

import agu.analys.model.CandleBar
import agu.analys.model.StructureBias
import agu.analys.model.StructureSnapshot
import kotlin.math.abs

/**
 * Market structure dari candle bursa (Tokocrypto).
 * Tidak mengarang level; exchange-agnostic (IDR & USDT).
 */
data class MarketStructureSnapshot(
    val trend: String,
    val trendExplanation: String,
    val lastSwingHigh: Double?,
    val lastSwingLow: Double?,
    val support: Double?,
    val resistance: Double?,
    val supportDistancePct: Double?,
    val resistanceDistancePct: Double?,
    val structureExplanation: String,
    val dataEnough: Boolean,
    val hasHigherHighsHigherLows: Boolean = false,
    val hasLowerHighsLowerLows: Boolean = false,
    val isSidewaysRanging: Boolean = false,
    val supportTouchesCount: Int = 1,
    val resistanceTouchesCount: Int = 1,
    val breakoutAndRetestStatus: String = "Belum Ada Breakout",
    val isBreakoutAndRetestValid: Boolean = false,
    val liquiditySweepDetected: Boolean = false,
    val ema13Value: Double = 0.0,
    val ema21Value: Double = 0.0,
    val isEmaBounceValid: Boolean = false,
    val akademiCryptoScalpingGrade: String = "Neutral",
    /** P0.4 — Change of Character */
    val hasChoCH: Boolean = false,
    val chochDirection: String = "", // bullish | bearish | ""
    /** P0.4 — 0–100 structure quality */
    val structureStrength: Int = 0,
    val hasBullishBOS: Boolean = false,
    val hasBearishBOS: Boolean = false
)

data class MicroStructureSnapshot(
    val trend: String,
    val lastSwingHigh: Double?,
    val lastSwingLow: Double?,
    val hasBullishBOS: Boolean,
    val hasBearishBOS: Boolean,
    val hasBullishSweep: Boolean,
    val hasBearishSweep: Boolean,
    val explanation: String
)

object MarketStructureAnalyzer {
    fun analyzeMicro(candles: List<CandleBar>): MicroStructureSnapshot {
        if (candles.size < 5) {
            return MicroStructureSnapshot(
                trend = "Wait", lastSwingHigh = null, lastSwingLow = null,
                hasBullishBOS = false, hasBearishBOS = false,
                hasBullishSweep = false, hasBearishSweep = false,
                explanation = "Data kurang untuk micro-structure"
            )
        }

        val recent = candles.takeLast(40)
        val swingHighs = mutableListOf<Double>()
        val swingLows = mutableListOf<Double>()

        for (i in 1 until recent.lastIndex) {
            val c = recent[i]
            val prev = recent[i - 1]
            val next = recent[i + 1]

            if (c.high > prev.high && c.high > next.high) swingHighs += c.high
            if (c.low < prev.low && c.low < next.low) swingLows += c.low
        }

        val lastSwingHigh = swingHighs.lastOrNull()
        val lastSwingLow = swingLows.lastOrNull()

        var hasBullishBOS = false
        var hasBearishBOS = false
        var hasBullishSweep = false
        var hasBearishSweep = false

        val checkWindow = recent.takeLast(3)

        if (lastSwingHigh != null) {
            hasBullishBOS = checkWindow.any { it.close > lastSwingHigh }
            if (!hasBullishBOS) {
                hasBearishSweep = checkWindow.any { it.high > lastSwingHigh && it.close <= lastSwingHigh }
            }
        }

        if (lastSwingLow != null) {
            hasBearishBOS = checkWindow.any { it.close < lastSwingLow }
            if (!hasBearishBOS) {
                hasBullishSweep = checkWindow.any { it.low < lastSwingLow && it.close >= lastSwingLow }
            }
        }

        val trend = when {
            hasBullishBOS -> "Bullish Micro"
            hasBearishBOS -> "Bearish Micro"
            hasBullishSweep -> "Sweep Low (Reversal Up)"
            hasBearishSweep -> "Sweep High (Reversal Down)"
            else -> "Ranging Micro"
        }

        val explanation = when (trend) {
            "Bullish Micro" -> "Harga close menembus micro resistance (BOS). Tren naik jangka pendek."
            "Bearish Micro" -> "Harga close menembus micro support (BOS). Tren turun jangka pendek."
            "Sweep Low (Reversal Up)" -> "Jebakan ekor di support (Liquidity Sweep). Potensi pantulan naik."
            "Sweep High (Reversal Down)" -> "Jebakan ekor di resistance (Liquidity Sweep). Potensi pantulan turun."
            else -> "Konsolidasi di dalam micro-swing."
        }

        return MicroStructureSnapshot(
            trend = trend,
            lastSwingHigh = lastSwingHigh,
            lastSwingLow = lastSwingLow,
            hasBullishBOS = hasBullishBOS,
            hasBearishBOS = hasBearishBOS,
            hasBullishSweep = hasBullishSweep,
            hasBearishSweep = hasBearishSweep,
            explanation = explanation
        )
    }

    fun analyze(candles: List<CandleBar>): MarketStructureSnapshot {
        if (candles.size < 12) {
            return MarketStructureSnapshot(
                trend = "Belum cukup data",
                trendExplanation = "Minimal 12 candle diperlukan untuk latihan membaca struktur pasar.",
                lastSwingHigh = null,
                lastSwingLow = null,
                support = null,
                resistance = null,
                supportDistancePct = null,
                resistanceDistancePct = null,
                structureExplanation = "Belum ada level yang ditampilkan agar aplikasi tidak mengarang level pasar.",
                dataEnough = false
            )
        }

        val recent = candles.takeLast(60)
        val swingHighs = mutableListOf<Double>()
        val swingLows = mutableListOf<Double>()
        for (i in 2 until recent.lastIndex - 1) {
            val c = recent[i]
            if (c.high >= recent[i - 1].high && c.high >= recent[i - 2].high &&
                c.high >= recent[i + 1].high && c.high >= recent[i + 2].high) swingHighs += c.high
            if (c.low <= recent[i - 1].low && c.low <= recent[i - 2].low &&
                c.low <= recent[i + 1].low && c.low <= recent[i + 2].low) swingLows += c.low
        }

        val last = recent.last().close
        val highs = swingHighs.takeLast(2)
        val lows = swingLows.takeLast(2)

        val hasHHHL = highs.size >= 2 && lows.size >= 2 && highs[1] > highs[0] && lows[1] > lows[0]
        val hasLHLL = highs.size >= 2 && lows.size >= 2 && highs[1] < highs[0] && lows[1] < lows[0]
        val isSideways = !hasHHHL && !hasLHLL

        val trend = when {
            hasHHHL -> "Uptrend (HH + HL)"
            hasLHLL -> "Downtrend (LH + LL)"
            else -> "Sideways / Ranging"
        }

        val support = swingLows.filter { it <= last }.maxOrNull()
            ?: swingLows.minOrNull()
            ?: recent.dropLast(1).minOfOrNull { it.low }
        val resistance = swingHighs.filter { it >= last }.minOrNull()
            ?: swingHighs.maxOrNull()
            ?: recent.dropLast(1).maxOfOrNull { it.high }

        val supportDistance = support?.let { abs(last - it) / last * 100.0 }
        val resistanceDistance = resistance?.let { abs(it - last) / last * 100.0 }

        val supportTouches = support?.let { sup ->
            recent.count { abs(it.low - sup) / sup <= 0.008 }
        } ?: 1

        val resistanceTouches = resistance?.let { res ->
            recent.count { abs(it.high - res) / res <= 0.008 }
        } ?: 1

        val closes = DoubleArray(candles.size) { candles[it].close }
        val ema13 = agu.analys.engine.indicators.IndicatorMath.ema(closes, minOf(13, closes.size))
        val ema21 = agu.analys.engine.indicators.IndicatorMath.ema(closes, minOf(21, closes.size))
        val isEmaBounce = last >= ema13 * 0.995 && ema13 >= ema21 * 0.998

        val checkWindow = recent.takeLast(4)
        val liquiditySweep = support?.let { sup ->
            checkWindow.any { it.low < sup * 0.998 && it.close >= sup }
        } ?: false

        val hadPreviousBreakout = resistance?.let { res ->
            recent.dropLast(3).any { it.high > res }
        } ?: false
        val isRetestingNow = resistance?.let { res ->
            abs(last - res) / res <= 0.015 && last >= res * 0.993
        } ?: false
        val isBreakoutRetestValid = hadPreviousBreakout && isRetestingNow && isEmaBounce

        // BOS on last swing
        val lastSh = swingHighs.lastOrNull()
        val lastSl = swingLows.lastOrNull()
        val hasBullishBOS = lastSh != null && checkWindow.any { it.close > lastSh }
        val hasBearishBOS = lastSl != null && checkWindow.any { it.close < lastSl }

        // CHoCH: after opposing structure, price breaks the other way
        // Bullish CHoCH: was making LH/LL then breaks last swing high
        // Bearish CHoCH: was making HH/HL then breaks last swing low
        val hasBullishChoCH = hasLHLL && hasBullishBOS
        val hasBearishChoCH = hasHHHL && hasBearishBOS
        val hasChoCH = hasBullishChoCH || hasBearishChoCH
        val chochDirection = when {
            hasBullishChoCH -> "bullish"
            hasBearishChoCH -> "bearish"
            else -> ""
        }

        val structureStrength = computeStrength(
            hasHHHL = hasHHHL,
            hasLHLL = hasLHLL,
            swingHighCount = swingHighs.size,
            swingLowCount = swingLows.size,
            supportTouches = supportTouches,
            resistanceTouches = resistanceTouches,
            isEmaBounce = isEmaBounce,
            hasChoCH = hasChoCH,
            hasBOS = hasBullishBOS || hasBearishBOS,
            liquiditySweep = liquiditySweep,
            isBreakoutRetest = isBreakoutRetestValid
        )

        val breakoutStatus = when {
            isBreakoutRetestValid -> "Breakout & Retest Valid (S/R Flip + EMA Bounce)"
            hadPreviousBreakout && isRetestingNow -> "Sedang Retest Resistance (Tunggu Konfirmasi EMA)"
            isSideways -> "Sideways Range (Hati-hati False Breakout)"
            else -> "Belum Ada Breakout"
        }

        val scalpingGrade = when {
            isBreakoutRetestValid && liquiditySweep -> "SANGAT PRESIFIK (Sweep + Retest)"
            isBreakoutRetestValid -> "TINNGI (Breakout & Retest EMA)"
            hasHHHL && isEmaBounce -> "SEDANG (Uptrend EMA Bounce)"
            isSideways -> "WASPADA (Sideways / False Breakout)"
            hasLHLL -> "BAHAYA (Downtrend Structure)"
            else -> "NETRAL"
        }

        val trendExplanation = when {
            hasBullishChoCH -> "CHoCH Bullish: struktur turun putus; potensi ganti karakter ke naik."
            hasBearishChoCH -> "CHoCH Bearish: struktur naik putus; potensi ganti karakter ke turun."
            hasHHHL -> "Uptrend (Higher High + Higher Low): Pembeli memegang kendali. Cari entry saat Breakout & Retest atau Pantulan EMA 13/21."
            hasLHLL -> "Downtrend (Lower High + Lower Low): Penjual memegang kendali. Hindari posisi BUY spot kecuali terjadi Liquidity Sweep kuat."
            else -> "Sideways / Ranging: Harga bergerak datar. Area rawan False Breakout. Tunggu Breakout & Retest valid."
        }

        val usedFallback = swingLows.isEmpty() || swingHighs.isEmpty()
        val structureExplanation = if (usedFallback) {
            "Swing belum lengkap; S/R menggunakan harga ekstrem candle terbaru."
        } else {
            "Support (Disentuh ${supportTouches}x) & Resistance (Disentuh ${resistanceTouches}x). Strength $structureStrength/100."
        }

        return MarketStructureSnapshot(
            trend = trend,
            trendExplanation = trendExplanation,
            lastSwingHigh = swingHighs.lastOrNull(),
            lastSwingLow = swingLows.lastOrNull(),
            support = support,
            resistance = resistance,
            supportDistancePct = supportDistance,
            resistanceDistancePct = resistanceDistance,
            structureExplanation = structureExplanation,
            dataEnough = true,
            hasHigherHighsHigherLows = hasHHHL,
            hasLowerHighsLowerLows = hasLHLL,
            isSidewaysRanging = isSideways,
            supportTouchesCount = supportTouches,
            resistanceTouchesCount = resistanceTouches,
            breakoutAndRetestStatus = breakoutStatus,
            isBreakoutAndRetestValid = isBreakoutRetestValid,
            liquiditySweepDetected = liquiditySweep,
            ema13Value = ema13,
            ema21Value = ema21,
            isEmaBounceValid = isEmaBounce,
            akademiCryptoScalpingGrade = scalpingGrade,
            hasChoCH = hasChoCH,
            chochDirection = chochDirection,
            structureStrength = structureStrength,
            hasBullishBOS = hasBullishBOS,
            hasBearishBOS = hasBearishBOS
        )
    }

    /**
     * Mapping ke [StructureSnapshot] untuk pipeline scalping KriptoYoi.
     */
    fun toStructureSnapshot(snap: MarketStructureSnapshot): StructureSnapshot {
        if (!snap.dataEnough) {
            return StructureSnapshot(explanation = "Data struktur belum cukup.")
        }
        val bias = when {
            snap.hasHigherHighsHigherLows || snap.chochDirection == "bullish" -> StructureBias.BULLISH
            snap.hasLowerHighsLowerLows || snap.chochDirection == "bearish" -> StructureBias.BEARISH
            else -> StructureBias.NEUTRAL
        }
        val pattern = when {
            snap.hasChoCH -> "CHOCH"
            snap.hasBullishBOS || snap.hasBearishBOS -> "BOS"
            snap.hasHigherHighsHigherLows -> "HH_HL"
            snap.hasLowerHighsLowerLows -> "LH_LL"
            else -> "RANGING"
        }
        return StructureSnapshot(
            bias = bias,
            pattern = pattern,
            bos = snap.hasBullishBOS || snap.hasBearishBOS,
            choch = snap.hasChoCH,
            strength = snap.structureStrength,
            lastSwingHigh = snap.lastSwingHigh,
            lastSwingLow = snap.lastSwingLow,
            support = snap.support,
            resistance = snap.resistance,
            liquiditySweepDetected = snap.liquiditySweepDetected,
            isBreakoutRetestValid = snap.isBreakoutAndRetestValid,
            explanation = snap.trendExplanation
        )
    }

    private fun computeStrength(
        hasHHHL: Boolean,
        hasLHLL: Boolean,
        swingHighCount: Int,
        swingLowCount: Int,
        supportTouches: Int,
        resistanceTouches: Int,
        isEmaBounce: Boolean,
        hasChoCH: Boolean,
        hasBOS: Boolean,
        liquiditySweep: Boolean,
        isBreakoutRetest: Boolean
    ): Int {
        var s = 35
        if (hasHHHL || hasLHLL) s += 18
        if (swingHighCount >= 3 && swingLowCount >= 3) s += 10
        if (supportTouches >= 2 || resistanceTouches >= 2) s += 8
        if (isEmaBounce) s += 8
        if (hasBOS) s += 10
        if (hasChoCH) s += 12
        if (liquiditySweep) s += 6
        if (isBreakoutRetest) s += 10
        return s.coerceIn(0, 100)
    }
}
