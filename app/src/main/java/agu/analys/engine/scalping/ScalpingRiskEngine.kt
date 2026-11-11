package agu.analys.engine.scalping

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.model.EntryZone
import agu.analys.model.RegimeSnapshot
import agu.analys.model.RiskLevels
import agu.analys.model.ScalpSetupType
import agu.analys.model.StructureSnapshot
import java.util.Locale

/**
 * Risk engine scalping KriptoYoi (P1.3) — sisi LONG.
 *
 * Threshold default di [ScalpingConfig] (P2).
 * Net R:R lewat [FeeCalculator] (fee exchange + slippage).
 * Exchange-agnostic: jarak dari ATR% / persen.
 */
object ScalpingRiskEngine {

    const val DEFAULT_MIN_NET_RR = ScalpingConfig.MIN_NET_RR
    const val DEFAULT_TARGET_NET_RR = ScalpingConfig.TARGET_NET_RR
    const val DEFAULT_MIN_RISK_PCT = ScalpingConfig.MIN_RISK_PCT
    const val DEFAULT_MAX_TP2_R = ScalpingConfig.MAX_TP2_R
    const val DEFAULT_SLIPPAGE_PCT = ScalpingConfig.DEFAULT_SLIPPAGE_PCT
    const val DEFAULT_MIN_FRICTION_MULTIPLE = ScalpingConfig.MIN_FRICTION_MULTIPLE

    private const val FALLBACK_ATR_PCT = 0.5
    private const val MIN_RISK_ATR = 0.8
    private const val DEFAULT_SL_ATR = 1.0
    private const val SL_BUFFER_ATR = 0.2
    private const val MIN_TP2_R = 1.5
    private const val TP1_SHARE = 0.55
    private const val MIN_TP1_R = 1.0

    data class Input(
        val price: Double,
        val setup: ScalpSetupType,
        val structure: StructureSnapshot,
        val regime: RegimeSnapshot,
        val fees: TradingFeeConfig = TradingFeeConfig(),
        val useMaker: Boolean = false,
        val config: ScalpingRuntimeConfig = ScalpingRuntimeConfig.current,
        val slippagePct: Double = config.slippagePct,
        val minNetRr: Double = config.minNetRr,
        val targetNetRr: Double = config.targetNetRr,
        val minRiskPct: Double = config.minRiskPct,
        /** S3 — batas keras jarak SL sebagai kelipatan ATR. Lihat [ScalpingConfig.MAX_RISK_ATR]. */
        val maxRiskAtr: Double = config.maxRiskAtr,
        val maxTp2R: Double = config.maxTp2R,
        /** Kelipatan minimum jarak SL terhadap friksi round-trip (0 = matikan lantai friksi). */
        val minFrictionMultiple: Double = config.minFrictionMultiple
    )

    data class Result(
        val levels: RiskLevels?,
        val valid: Boolean,
        val reasons: List<String>
    )

    fun evaluate(input: Input): Result {
        if (input.price <= 0.0) {
            return Result(null, false, listOf("Harga tidak valid — risk plan tidak dibuat."))
        }
        if (input.setup == ScalpSetupType.NONE) {
            return Result(null, false, listOf("Tidak ada setup — risk plan tidak dibuat."))
        }

        val reasons = mutableListOf<String>()
        val price = input.price
        val s = input.structure

        val atrPct = if (input.regime.atrPct > 0.0) input.regime.atrPct else FALLBACK_ATR_PCT
        if (input.regime.atrPct <= 0.0) {
            reasons += "ATR belum tersedia — pakai ATR cadangan ${fmt(FALLBACK_ATR_PCT)}%."
        }
        val atr = price * atrPct / 100.0

        val (lowK, highK) = when (input.setup) {
            ScalpSetupType.BREAKOUT -> 0.1 to 0.2
            else -> 0.3 to 0.1
        }
        val zoneLow = price - lowK * atr
        val zoneHigh = price + highK * atr
        val entry = zoneHigh

        val structural = when (input.setup) {
            ScalpSetupType.LIQUIDITY_SWEEP -> s.lastSwingLow ?: s.support
            else -> s.support ?: s.lastSwingLow
        }
        val structuralSl = structural
            ?.takeIf { it > 0.0 && it < zoneLow }
            ?.let { it - SL_BUFFER_ATR * atr }

        val buyFee = if (input.useMaker) input.fees.buyMakerPct else input.fees.buyTakerPct
        val sellFee = if (input.useMaker) input.fees.sellMakerPct else input.fees.sellTakerPct
        val costPct = buyFee + sellFee + 2.0 * input.slippagePct

        // --- Lantai risiko berbasis friksi (2026-10-12) -------------------------------
        // Friksi round-trip adalah kerugian yang tak bisa dihindari tiap trade. Bila jarak SL
        // lebih kecil dari beberapa kali friksi, satu kali SL menelan banyak "R" sekaligus dan
        // matematikanya mustahil. Lantai ini memaksa risk ≥ minFrictionMultiple × friksi.
        val frictionFloorPct = input.minFrictionMultiple * costPct
        val atrCapPct = input.maxRiskAtr * atr / entry * 100.0
        if (input.minFrictionMultiple > 0.0 && frictionFloorPct > atrCapPct) {
            reasons += "SL dipaksa ${fmt(frictionFloorPct)}% (${fmt(input.minFrictionMultiple)}x friksi) — " +
                "di atas ${fmt(input.maxRiskAtr)}x ATR (${fmt(atrCapPct)}%). Ini bukan scalp cepat; " +
                "horizon realistis 1–8 jam."
        }
        val minRiskAbs = maxOf(
            MIN_RISK_ATR * atr,
            input.minRiskPct / 100.0 * entry,
            frictionFloorPct / 100.0 * entry
        )
        val maxRiskAbs = maxOf(input.maxRiskAtr * atr, minRiskAbs)

        var sl = structuralSl ?: (entry - DEFAULT_SL_ATR * atr)
        val rawRisk = entry - sl
        when {
            rawRisk > maxRiskAbs -> {
                sl = entry - maxRiskAbs
                reasons += "SL struktur terlalu jauh — dibatasi ${fmt(input.maxRiskAtr)}x ATR."
            }
            rawRisk < minRiskAbs -> {
                sl = entry - minRiskAbs
                if (frictionFloorPct > input.minRiskPct && frictionFloorPct > MIN_RISK_ATR * atr / entry * 100.0) {
                    reasons += "SL terlalu rapat untuk friksi ${fmt(costPct)}% — dilebarkan ke " +
                        "${fmt(input.minFrictionMultiple)}x friksi (${fmt(frictionFloorPct)}%)."
                } else {
                    reasons += "SL terlalu rapat — dilebarkan ke batas minimum (${fmt(input.minRiskPct)}% / ${fmt(MIN_RISK_ATR)}x ATR)."
                }
            }
            structuralSl != null -> reasons += "SL di bawah struktur (support/swing low) + buffer ATR."
            else -> reasons += "SL berbasis ${fmt(DEFAULT_SL_ATR)}x ATR (struktur tidak tersedia)."
        }

        val risk = entry - sl
        val riskPctForTp = risk / entry * 100.0
        val requiredGrossPct = input.targetNetRr * (riskPctForTp + costPct) + costPct
        val tp2Pct = maxOf(requiredGrossPct, MIN_TP2_R * riskPctForTp)

        var tp1 = entry * (1.0 + tp2Pct * TP1_SHARE / 100.0)
        var resistanceTooClose = false
        val resistance = s.resistance?.takeIf { it > entry }
        if (resistance != null && resistance * 0.9995 < tp1) {
            tp1 = resistance * 0.9995
            reasons += "Resistance dekat — TP1 dibatasi tepat di bawah resistance."
            if ((tp1 - entry) < MIN_TP1_R * risk) resistanceTooClose = true
        }
        val tp2 = maxOf(entry * (1.0 + tp2Pct / 100.0), tp1 + 0.5 * risk)
        val tp2R = (tp2 - entry) / risk

        val fee = FeeCalculator.roundTrip(
            entry = entry,
            stopLoss = sl,
            takeProfit = tp2,
            fees = input.fees,
            useMaker = input.useMaker,
            slippagePct = input.slippagePct
        )

        val riskPct = (entry - sl) / entry * 100.0
        val rewardPct = (tp2 - entry) / entry * 100.0

        val levelsOk = sl > 0.0 && sl < zoneLow && tp1 > entry && !resistanceTooClose
        val targetOk = tp2R <= input.maxTp2R
        val rrOk = fee.netRr >= input.minNetRr
        val valid = levelsOk && targetOk && rrOk

        reasons += when {
            resistanceTooClose -> "Resistance terlalu dekat (TP1 < ${fmt(MIN_TP1_R)}R) — WAIT."
            !levelsOk -> "Level SL/TP tidak masuk akal — WAIT."
            !targetOk -> "Target TP2 ${fmt(tp2R)}R terlalu jauh (maks ${fmt(input.maxTp2R)}R) — fee/slippage terlalu besar, WAIT."
            rrOk -> "Net R:R ${fmt(fee.netRr)} ≥ ${fmt(input.minNetRr)} di TP2 (setelah fee & slippage)."
            else -> "Net R:R ${fmt(fee.netRr)} < ${fmt(input.minNetRr)} — WAIT."
        }

        val levels = RiskLevels(
            entryZone = EntryZone(
                low = zoneLow,
                high = zoneHigh,
                confirmationTrigger = triggerText(input.setup),
                invalidation = sl
            ),
            stopLoss = sl,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            riskPct = riskPct,
            rewardPct = rewardPct,
            netRr = fee.netRr,
            feeSlippageNote = "Fee ${fmt(fee.feePct)}% + slippage ${fmt(fee.slippagePct * 2)}% " +
                "(round-trip ${fmt(fee.totalCostPct)}%). Net reward ${fmt(fee.netRewardPct)}%, " +
                "net risk ${fmt(fee.netRiskPct)}%."
        )
        return Result(levels, valid, reasons.toList())
    }

    private fun triggerText(setup: ScalpSetupType): String = when (setup) {
        ScalpSetupType.BREAKOUT -> "Candle M1 close di atas level breakout dengan volume naik."
        ScalpSetupType.BREAKOUT_RETEST -> "Retest level breakout bertahan, lalu candle M1 close hijau."
        ScalpSetupType.LIQUIDITY_SWEEP -> "Harga kembali di atas level sweep, lalu candle M1 close hijau."
        ScalpSetupType.TREND_PULLBACK -> "Pullback tertahan di EMA/support, lalu candle M1 close hijau."
        ScalpSetupType.NONE -> ""
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
