package agu.analys.engine.scalping

import agu.analys.engine.MarketStructureAnalyzer
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.model.CandleBar
import agu.analys.model.StructureBias

/**
 * Multi-timeframe confluence matrix (P2) — spek KriptoYoi.
 *
 * Timeframe tinggi = konteks; rendah = entry.
 * Default keys: 1D, 4H, 1H, 15M, 5M, 1M ([ScalpingConfig.MTF_KEYS_DEFAULT]).
 *
 * Bias per TF: struktur HH/HL atau EMA9>EMA21 → BULLISH; sebaliknya BEARISH; else NEUTRAL.
 * Jika TF bertentangan kuat → alignment rendah (evaluator boleh WAIT).
 */
object MtfConfluenceMatrix {

    enum class TfBias {
        BULLISH,
        BEARISH,
        NEUTRAL,
        UNKNOWN
    }

    data class TfLeg(
        val key: String,
        val bias: TfBias,
        val structureLabel: String = "",
        val detail: String = ""
    )

    data class Matrix(
        val legs: List<TfLeg>,
        val bullishCount: Int,
        val bearishCount: Int,
        val neutralCount: Int,
        val knownCount: Int,
        /** Contoh: "4/6 bullish" */
        val alignmentLabel: String,
        /** Bias mayoritas di antara leg yang diketahui. */
        val majorityBias: TfBias
    ) {
        val totalSlots: Int get() = legs.size

        fun biasOf(key: String): TfBias =
            legs.firstOrNull { it.key.equals(key, ignoreCase = true) }?.bias ?: TfBias.UNKNOWN
    }

    data class CandleBundle(
        val d1: List<CandleBar> = emptyList(),
        val h4: List<CandleBar> = emptyList(),
        val h1: List<CandleBar> = emptyList(),
        val m15: List<CandleBar> = emptyList(),
        val m5: List<CandleBar> = emptyList(),
        val m1: List<CandleBar> = emptyList()
    )

    /**
     * Bangun matrix dari candle multi-TF yang tersedia.
     * TF tanpa data cukup → UNKNOWN (tidak dihitung ke majority).
     */
    fun build(bundle: CandleBundle): Matrix {
        val legs = listOf(
            evaluateLeg("1D", bundle.d1),
            evaluateLeg("4H", bundle.h4),
            evaluateLeg("1H", bundle.h1),
            evaluateLeg("15M", bundle.m15),
            evaluateLeg("5M", bundle.m5),
            evaluateLeg("1M", bundle.m1)
        )
        return fromLegs(legs)
    }

    /**
     * Overload praktis untuk pipeline scalping: H1, M15, M5, M1.
     * p0 memakai empat TF ini; memasukkan 1D/4H ke matriks scalping akan menambah derau
     * yang tidak dipakai keputusan entry.
     */
    fun buildPartial(
        h1: List<CandleBar> = emptyList(),
        m15: List<CandleBar> = emptyList(),
        m1: List<CandleBar> = emptyList(),
        m5: List<CandleBar> = emptyList(),
        h4: List<CandleBar> = emptyList(),
        d1: List<CandleBar> = emptyList()
    ): Matrix = build(CandleBundle(d1 = d1, h4 = h4, h1 = h1, m15 = m15, m5 = m5, m1 = m1))

    fun fromLegs(legs: List<TfLeg>): Matrix {
        val known = legs.filter { it.bias != TfBias.UNKNOWN }
        val bull = known.count { it.bias == TfBias.BULLISH }
        val bear = known.count { it.bias == TfBias.BEARISH }
        val neut = known.count { it.bias == TfBias.NEUTRAL }
        val knownCount = known.size

        val majority = when {
            knownCount == 0 -> TfBias.UNKNOWN
            bull > bear && bull >= neut -> TfBias.BULLISH
            bear > bull && bear >= neut -> TfBias.BEARISH
            else -> TfBias.NEUTRAL
        }

        val label = when {
            knownCount == 0 -> "0/0"
            majority == TfBias.BULLISH -> "$bull/$knownCount bullish"
            majority == TfBias.BEARISH -> "$bear/$knownCount bearish"
            else -> "$neut/$knownCount netral (campur)"
        }

        return Matrix(
            legs = legs,
            bullishCount = bull,
            bearishCount = bear,
            neutralCount = neut,
            knownCount = knownCount,
            alignmentLabel = label,
            majorityBias = majority
        )
    }

    private fun evaluateLeg(key: String, candles: List<CandleBar>): TfLeg {
        if (candles.size < 12) {
            return TfLeg(key, TfBias.UNKNOWN, detail = "Data $key kurang")
        }

        val struct = MarketStructureAnalyzer.analyze(candles)
        val closes = candles.map { it.close }
        val ema9 = IndicatorMath.ema(closes, minOf(9, closes.size))
        val ema21 = IndicatorMath.ema(closes, minOf(21, closes.size))
        val emaBull = ema9 > ema21 * 0.998
        val emaBear = ema9 < ema21 * 1.002

        val bias = when {
            struct.hasHigherHighsHigherLows || (emaBull && !struct.hasLowerHighsLowerLows) -> TfBias.BULLISH
            struct.hasLowerHighsLowerLows || (emaBear && !struct.hasHigherHighsHigherLows) -> TfBias.BEARISH
            else -> TfBias.NEUTRAL
        }

        val structureLabel = when {
            struct.hasHigherHighsHigherLows -> "HH/HL"
            struct.hasLowerHighsLowerLows -> "LH/LL"
            else -> "range"
        }

        return TfLeg(
            key = key,
            bias = bias,
            structureLabel = structureLabel,
            detail = "$key $structureLabel · EMA ${if (emaBull) "↑" else if (emaBear) "↓" else "~"}"
        )
    }

    fun toStructureBias(tf: TfBias): StructureBias = when (tf) {
        TfBias.BULLISH -> StructureBias.BULLISH
        TfBias.BEARISH -> StructureBias.BEARISH
        else -> StructureBias.NEUTRAL
    }
}
