package agu.analys.engine.regime

import agu.analys.engine.indicators.IndicatorMath
import agu.analys.engine.scalping.ScalpingConfig
import agu.analys.model.CandleBar
import agu.analys.model.MarketRegime
import agu.analys.model.RegimeSnapshot
import agu.analys.model.StructureBias
import kotlin.math.abs

/**
 * Formal market regime engine (KriptoYoi P0).
 * Tidak mengganti [MarketRegimeDetector] lama (string legacy) — API baru bertipe [RegimeSnapshot].
 * Exchange-agnostic: threshold berbasis % / ratio (IDR & USDT).
 */
object MarketRegimeEngine {

    /**
     * Ambang klasifikasi regime. Default = [ScalpingConfig]; pipeline scalping mengisinya dari
     * [agu.analys.engine.scalping.ScalpingRuntimeConfig] agar replay dan app memakai angka yang sama.
     */
    data class Thresholds(
        val adxTrending: Double = ScalpingConfig.ADX_TRENDING,
        val adxWeak: Double = ScalpingConfig.ADX_WEAK,
        val chopRanging: Double = ScalpingConfig.CHOP_RANGING,
        val chopTrendMax: Double = ScalpingConfig.CHOP_TREND_MAX,
        val atrPctHigh: Double = ScalpingConfig.ATR_PCT_HIGH,
        val atrPctLow: Double = ScalpingConfig.ATR_PCT_LOW
    ) {
        companion object {
            fun from(config: agu.analys.engine.scalping.ScalpingRuntimeConfig): Thresholds = Thresholds(
                adxTrending = config.regimeAdxTrending,
                adxWeak = config.regimeAdxWeak,
                chopRanging = config.regimeChopRanging,
                chopTrendMax = config.regimeChopTrendMax
            )
        }
    }

    data class Input(
        val candles: List<CandleBar>,
        val price: Double = 0.0,
        val structureBias: StructureBias = StructureBias.NEUTRAL,
        val rvol: Double = 1.0,
        val resistanceBroken: Boolean = false,
        val supportBroken: Boolean = false,
        val thresholds: Thresholds = Thresholds()
    )

    fun detect(input: Input): RegimeSnapshot {
        val candles = input.candles
        if (candles.size < 20) {
            return RegimeSnapshot(
                regime = MarketRegime.RANGING,
                explanation = "Data candle kurang untuk regime (min 20)."
            )
        }

        val price = if (input.price > 0) input.price else candles.last().close
        val atr = IndicatorMath.atr(candles, 14)
        val atrPct = if (price > 0) (atr / price) * 100.0 else 0.0
        val adx = IndicatorMath.adx(candles, 14)
        val chop = IndicatorMath.choppiness(candles, 14)

        val closes = candles.map { it.close }
        val ema9 = IndicatorMath.ema(closes, minOf(9, closes.size))
        val ema21 = IndicatorMath.ema(closes, minOf(21, closes.size))
        val ema50 = IndicatorMath.ema(closes, minOf(50, closes.size))

        val emaAlignment = when {
            ema9 > ema21 && ema21 > ema50 -> "bullish"
            ema9 < ema21 && ema21 < ema50 -> "bearish"
            else -> "mixed"
        }

        val regime = classify(
            adx = adx,
            chop = chop,
            atrPct = atrPct,
            emaAlignment = emaAlignment,
            structureBias = input.structureBias,
            rvol = input.rvol,
            resistanceBroken = input.resistanceBroken,
            supportBroken = input.supportBroken,
            thresholds = input.thresholds
        )

        val explanation = buildExplanation(regime, adx, chop, atrPct, emaAlignment)

        return RegimeSnapshot(
            regime = regime,
            adx = adx,
            chop = chop,
            atrPct = atrPct,
            emaAlignment = emaAlignment,
            explanation = explanation
        )
    }

    /** Convenience overload. */
    fun detect(
        candles: List<CandleBar>,
        price: Double = 0.0,
        structureBias: StructureBias = StructureBias.NEUTRAL,
        rvol: Double = 1.0,
        resistanceBroken: Boolean = false,
        supportBroken: Boolean = false,
        thresholds: Thresholds = Thresholds()
    ): RegimeSnapshot = detect(
        Input(
            candles = candles,
            price = price,
            structureBias = structureBias,
            rvol = rvol,
            resistanceBroken = resistanceBroken,
            supportBroken = supportBroken,
            thresholds = thresholds
        )
    )

    private fun classify(
        adx: Double,
        chop: Double,
        atrPct: Double,
        emaAlignment: String,
        structureBias: StructureBias,
        rvol: Double,
        resistanceBroken: Boolean,
        supportBroken: Boolean,
        thresholds: Thresholds
    ): MarketRegime {
        // Volatility extremes first.
        // S4 — ambang ini dulu literal (3.5 / 0.8) yang kebetulan sama dengan
        // ScalpingConfig.ATR_PCT_HIGH / ATR_PCT_LOW. Memakai konstanta membuat satu sumber
        // kebenaran: mengubah band volatilitas tidak lagi menuntut sunting dua tempat.
        if (atrPct >= thresholds.atrPctHigh) return MarketRegime.HIGH_VOLATILITY
        // p0: LOW_VOL = ATR rendah DAN CHOP > 55. Versi lama memakai ADX < 18, yang membuat
        // pasar sepi tetapi sedang tren pendek tetap dilabeli LOW_VOLATILITY.
        if (atrPct > 0.0 && atrPct < thresholds.atrPctLow && chop > thresholds.chopTrendMax) {
            return MarketRegime.LOW_VOLATILITY
        }

        // Breakout: level broken + participation.
        // Ini ekstensi app yang dipertahankan; p0 belum mengembalikan BREAKOUT dari
        // `analyze_market_regime`, tetapi spec menyebutnya sebagai regime tersendiri.
        if ((resistanceBroken || supportBroken) && rvol >= 1.5) {
            return MarketRegime.BREAKOUT
        }

        // p0 — trending diperiksa LEBIH DULU dan mensyaratkan CHOP di bawah batas atas.
        // p0: is_trending = adx >= 25 AND chop < 55.
        val isTrending = adx >= thresholds.adxTrending && chop < thresholds.chopTrendMax
        if (isTrending) {
            when {
                structureBias == StructureBias.BULLISH && emaAlignment == "bullish" ->
                    return MarketRegime.TRENDING_UP
                structureBias == StructureBias.BEARISH && emaAlignment == "bearish" ->
                    return MarketRegime.TRENDING_DOWN
                emaAlignment == "bullish" && structureBias != StructureBias.BEARISH ->
                    return MarketRegime.TRENDING_UP
                emaAlignment == "bearish" && structureBias != StructureBias.BULLISH ->
                    return MarketRegime.TRENDING_DOWN
            }
        }

        // p0 — choppy memakai OR, bukan AND: CHOP tinggi ATAU ADX lemah.
        val isChoppy = chop >= thresholds.chopRanging || adx < thresholds.adxWeak
        if (isChoppy) {
            return MarketRegime.RANGING
        }

        // Mild trend without strong ADX — hanya untuk fase transisi.
        if (adx >= thresholds.adxWeak && chop < 50.0) {
            if (emaAlignment == "bullish" || structureBias == StructureBias.BULLISH) {
                return MarketRegime.TRENDING_UP
            }
            if (emaAlignment == "bearish" || structureBias == StructureBias.BEARISH) {
                return MarketRegime.TRENDING_DOWN
            }
        }

        return MarketRegime.RANGING
    }

    private fun buildExplanation(
        regime: MarketRegime,
        adx: Double,
        chop: Double,
        atrPct: Double,
        emaAlignment: String
    ): String {
        val base = "ADX ${fmt(adx)} | CHOP ${fmt(chop)} | ATR ${fmt(atrPct)}% | EMA $emaAlignment"
        return when (regime) {
            MarketRegime.TRENDING_UP -> "TRENDING UP — momentum naik. $base"
            MarketRegime.TRENDING_DOWN -> "TRENDING DOWN — momentum turun. $base"
            MarketRegime.RANGING -> "RANGING / CHOPPY — hindari trend-following murni. $base"
            MarketRegime.BREAKOUT -> "BREAKOUT — level tembus + volume. $base"
            MarketRegime.HIGH_VOLATILITY -> "HIGH VOLATILITY — risiko noise tinggi. $base"
            MarketRegime.LOW_VOLATILITY -> "LOW VOLATILITY — range sempit. $base"
        }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
