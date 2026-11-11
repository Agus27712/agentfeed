package agu.analys.engine.scalping

import agu.analys.engine.indicators.IndicatorMath
import agu.analys.model.CandleBar
import agu.analys.model.MarketRegime
import agu.analys.model.RegimeSnapshot
import agu.analys.model.ScalpSetupType
import agu.analys.model.ScoreBreakdown
import agu.analys.model.StructureBias
import agu.analys.model.StructureSnapshot
import kotlin.math.roundToInt

/**
 * Signal Scoring Engine scalping KriptoYoi (P1.2).
 *
 * Bobot (total 100):
 * Structure 25 | MTF 15 | Price Action 20 | Volume 15 | Momentum 10 | Order Flow 10 | Volatility 5
 *
 * Exchange-agnostic: semua threshold pakai persen / ratio (IDR & USDT).
 * Tidak crash jika data kosong — komponen terkait bernilai 0 + alasan.
 * Kategori (NO_TRADE..VERY_STRONG) dihitung oleh [ScoreBreakdown.category].
 */
object SignalScoringEngine {

    const val MAX_STRUCTURE = 25
    const val MAX_MTF = 15
    const val MAX_PRICE_ACTION = 20
    const val MAX_VOLUME = 15
    const val MAX_MOMENTUM = 10
    const val MAX_ORDER_FLOW = 10
    const val MAX_VOLATILITY = 5

    data class Input(
        val price: Double,
        val structure: StructureSnapshot,
        val regime: RegimeSnapshot,
        val setup: ScalpSetupType = ScalpSetupType.NONE,
        val rvol: Double = 1.0,
        val buyPressure: Double = 1.0,
        /** Order imbalance bid vs ask, range -1.0 .. +1.0. */
        val orderImbalance: Double = 0.0,
        /** false jika orderbook kosong / tidak tersedia. */
        val hasOrderBook: Boolean = true,
        /** Jumlah timeframe yang searah dari total yang dicek. */
        val mtfAligned: Int = 0,
        val mtfTotal: Int = 0,
        val m1Candles: List<CandleBar> = emptyList()
    )

    fun score(input: Input): ScoreBreakdown {
        if (input.price <= 0.0) {
            return ScoreBreakdown(reasons = listOf("Harga tidak valid — skor 0."))
        }

        val reasons = mutableListOf<String>()

        val structure = scoreStructure(input, reasons)
        val mtf = scoreMtf(input, reasons)
        val priceAction = scorePriceAction(input, reasons)
        val volume = scoreVolume(input, reasons)
        val momentum = scoreMomentum(input, reasons)
        val orderFlow = scoreOrderFlow(input, reasons)
        val volatility = scoreVolatility(input, reasons)

        return ScoreBreakdown(
            structure = structure,
            mtf = mtf,
            priceAction = priceAction,
            volume = volume,
            momentum = momentum,
            orderFlow = orderFlow,
            volatility = volatility,
            reasons = reasons.toList()
        )
    }

    // --------------------------------------------------------------------------
    // Structure (max 25)
    // -------------------------------------------------------------------------
    private fun scoreStructure(input: Input, reasons: MutableList<String>): Int {
        val s = input.structure
        var pts = s.strength.coerceIn(0, 100) * 15 / 100.0

        pts += when (s.bias) {
            StructureBias.BULLISH -> 5.0
            StructureBias.NEUTRAL -> 2.0
            StructureBias.BEARISH -> 0.0
        }

        if (s.choch && s.bias == StructureBias.BULLISH) {
            pts += 3.0
        } else if (s.bos && s.bias != StructureBias.BEARISH) {
            pts += 3.0
        }
        if (s.liquiditySweepDetected) pts += 2.0

        // Bias turun tanpa CHoCH: struktur tidak mendukung long.
        if (s.bias == StructureBias.BEARISH && !s.choch) pts *= 0.3

        val result = pts.roundToInt().coerceIn(0, MAX_STRUCTURE)
        reasons += when {
            s.bias == StructureBias.BEARISH && !s.choch ->
                "Struktur bearish (kekuatan ${s.strength}) — tidak mendukung long. Skor struktur $result/$MAX_STRUCTURE."
            s.choch -> "Struktur: CHoCH terdeteksi, kekuatan ${s.strength}. Skor $result/$MAX_STRUCTURE."
            s.bos -> "Struktur: BOS terdeteksi, kekuatan ${s.strength}. Skor $result/$MAX_STRUCTURE."
            else -> "Struktur ${biasLabel(s.bias)}, kekuatan ${s.strength}. Skor $result/$MAX_STRUCTURE."
        }
        return result
    }

    // --------------------------------------------------------------------------
    // MTF (max 15)
    // -------------------------------------------------------------------------
    private fun scoreMtf(input: Input, reasons: MutableList<String>): Int {
        if (input.mtfTotal <= 0) {
            reasons += "MTF tidak tersedia — skor MTF 0/$MAX_MTF."
            return 0
        }
        val aligned = input.mtfAligned.coerceIn(0, input.mtfTotal)
        val result = (aligned * MAX_MTF.toDouble() / input.mtfTotal).roundToInt().coerceIn(0, MAX_MTF)
        reasons += "MTF searah $aligned/${input.mtfTotal} timeframe. Skor $result/$MAX_MTF."
        return result
    }

    // --------------------------------------------------------------------------
    // Price Action (max 20): kualitas setup (maks 14) + konfirmasi candle (maks 6)
    // -------------------------------------------------------------------------
    private fun scorePriceAction(input: Input, reasons: MutableList<String>): Int {
        var pts = when (input.setup) {
            ScalpSetupType.BREAKOUT_RETEST -> 14
            ScalpSetupType.LIQUIDITY_SWEEP -> 13
            ScalpSetupType.BREAKOUT -> 12
            ScalpSetupType.TREND_PULLBACK -> 11
            ScalpSetupType.NONE -> 0
        }

        val candles = input.m1Candles
        var candleNote = "candle kurang"
        if (candles.size >= 6) {
            val last = candles.last()
            val range = (last.high - last.low).coerceAtLeast(1e-12)
            var bonus = 0
            if (last.close > last.open) bonus += 2
            if ((last.close - last.low) / range >= 0.6) bonus += 2

            val recentLow = candles.takeLast(3).minOf { it.low }
            val priorLow = candles.dropLast(3).takeLast(5).minOf { it.low }
            if (recentLow >= priorLow) bonus += 2

            pts += bonus
            candleNote = "konfirmasi candle +$bonus"
        }

        val result = pts.coerceIn(0, MAX_PRICE_ACTION)
        reasons += "Price action: setup ${setupLabel(input.setup)}, $candleNote. Skor $result/$MAX_PRICE_ACTION."
        return result
    }

    // --------------------------------------------------------------------------
    // Volume (max 15): RVOL
    // -------------------------------------------------------------------------
    private fun scoreVolume(input: Input, reasons: MutableList<String>): Int {
        val r = input.rvol
        val result = when {
            r.isNaN() || r < 0.8 -> 0
            r < 1.0 -> 3
            r < 1.2 -> 6
            r < 1.5 -> 9
            r < 2.0 -> 12
            else -> 15
        }
        reasons += "Volume: RVOL ${fmt(r)}x. Skor $result/$MAX_VOLUME."
        return result
    }

    // --------------------------------------------------------------------------
    // Momentum (max 10): EMA9/21 (4) + RSI (4) + MACD (2)
    // -------------------------------------------------------------------------
    private fun scoreMomentum(input: Input, reasons: MutableList<String>): Int {
        val candles = input.m1Candles
        if (candles.size < 15) {
            reasons += "Data momentum kurang (butuh min 15 candle) — skor momentum 0/$MAX_MOMENTUM."
            return 0
        }

        val closes = candles.map { it.close }
        var pts = 0
        val notes = mutableListOf<String>()

        val ema9 = IndicatorMath.ema(closes, minOf(9, closes.size))
        val ema21 = IndicatorMath.ema(closes, minOf(21, closes.size))
        if (ema9 > ema21) {
            pts += 4
            notes += "EMA9 di atas EMA21"
        }

        val rsi = IndicatorMath.rsi(candles, 14)
        when {
            rsi in 50.0..68.0 -> {
                pts += 4
                notes += "RSI ${fmt(rsi)} sehat"
            }
            rsi in 42.0..50.0 || rsi in 68.0..75.0 -> {
                pts += 2
                notes += "RSI ${fmt(rsi)} netral"
            }
            rsi > 75.0 -> notes += "RSI ${fmt(rsi)} overbought"
            else -> notes += "RSI ${fmt(rsi)} lemah"
        }

        if (closes.size >= 35) {
            val macd = IndicatorMath.macdSeries(closes, 12, 26, 9)
            if (macd.isNotEmpty() && macd.lastMacd > macd.lastSignal) {
                pts += 2
                notes += "MACD di atas signal"
            }
        }

        val result = pts.coerceIn(0, MAX_MOMENTUM)
        reasons += "Momentum: ${notes.joinToString(", ")}. Skor $result/$MAX_MOMENTUM."
        return result
    }

    // --------------------------------------------------------------------------
    // Order Flow (max 10): buy pressure (maks 6) + order imbalance (maks 4)
    // -------------------------------------------------------------------------
    private fun scoreOrderFlow(input: Input, reasons: MutableList<String>): Int {
        if (!input.hasOrderBook) {
            reasons += "Order book kosong — skor order flow 0/$MAX_ORDER_FLOW."
            return 0
        }

        val bp = input.buyPressure
        val bpPts = when {
            bp.isNaN() -> 0
            bp >= 1.5 -> 6
            bp >= 1.2 -> 5
            bp >= 1.05 -> 3
            bp >= 0.95 -> 1
            else -> 0
        }

        val imb = input.orderImbalance
        val imbPts = when {
            imb.isNaN() -> 0
            imb >= 0.3 -> 4
            imb >= 0.1 -> 2
            imb >= -0.1 -> 1
            else -> 0
        }

        val result = (bpPts + imbPts).coerceIn(0, MAX_ORDER_FLOW)
        reasons += "Order flow: buy pressure ${fmt(bp)}x, imbalance ${fmt(imb)}. Skor $result/$MAX_ORDER_FLOW."
        return result
    }

    // --------------------------------------------------------------------------
    // Volatility (max 5): ATR% sweet spot untuk scalping
    // -------------------------------------------------------------------------
    private fun scoreVolatility(input: Input, reasons: MutableList<String>): Int {
        val atrPct = input.regime.atrPct
        var pts = when {
            atrPct <= 0.0 -> 2 // data ATR tidak ada → netral
            atrPct in 0.3..1.5 -> 5
            atrPct in 0.15..0.3 || atrPct in 1.5..2.5 -> 3
            else -> 1
        }
        if (input.regime.regime == MarketRegime.HIGH_VOLATILITY) pts = minOf(pts, 2)
        if (input.regime.chop >= 65.0) pts -= 2

        val result = pts.coerceIn(0, MAX_VOLATILITY)
        reasons += "Volatilitas: ATR ${fmt(atrPct)}%, regime ${input.regime.regime}. Skor $result/$MAX_VOLATILITY."
        return result
    }

    // --------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private fun biasLabel(b: StructureBias): String = when (b) {
        StructureBias.BULLISH -> "bullish"
        StructureBias.BEARISH -> "bearish"
        StructureBias.NEUTRAL -> "netral"
    }

    private fun setupLabel(t: ScalpSetupType): String = when (t) {
        ScalpSetupType.BREAKOUT_RETEST -> "breakout retest"
        ScalpSetupType.LIQUIDITY_SWEEP -> "liquidity sweep"
        ScalpSetupType.BREAKOUT -> "breakout"
        ScalpSetupType.TREND_PULLBACK -> "trend pullback"
        ScalpSetupType.NONE -> "tidak ada"
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
