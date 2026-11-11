package agu.analys.engine.scalping

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Runtime configuration for scalping engine sensitivity (P1).
 *
 * Seeded from [ScalpingConfig] defaults.
 * Allows interactive adjustments via UI sliders, presets (Strict / Default / Loose),
 * and JSON import/export compatible with `AUDIT-2026-11-08.md` and `AUDIT-2026-11-08.md`.
 */
data class ScalpingRuntimeConfig(
    val minScoreLong: Int = ScalpingConfig.MIN_SCORE_LONG,
    val minScoreStrong: Int = ScalpingConfig.MIN_SCORE_STRONG,
    val minScoreBreakoutRetest: Int = ScalpingConfig.MIN_SCORE_BREAKOUT_RETEST,
    val minNetRr: Double = ScalpingConfig.MIN_NET_RR,
    val targetNetRr: Double = ScalpingConfig.TARGET_NET_RR,
    val minRiskPct: Double = ScalpingConfig.MIN_RISK_PCT,
    /**
     * S3 — batas keras jarak SL sebagai kelipatan ATR. Lihat [ScalpingConfig.MAX_RISK_ATR].
     * Tidak pernah mempersempit SL di bawah lantai friksi.
     */
    val maxRiskAtr: Double = ScalpingConfig.MAX_RISK_ATR,
    val maxTp2R: Double = ScalpingConfig.MAX_TP2_R,
    /**
     * Kelipatan minimum jarak SL terhadap friksi round-trip (fee all-in + 2× slippage).
     * 0 = matikan lantai friksi (perilaku lama). Lihat [ScalpingConfig.MIN_FRICTION_MULTIPLE].
     */
    val minFrictionMultiple: Double = ScalpingConfig.MIN_FRICTION_MULTIPLE,
    val slippagePct: Double = ScalpingConfig.DEFAULT_SLIPPAGE_PCT,
    val rvolBreakoutMin: Double = ScalpingConfig.RVOL_BREAKOUT_MIN,
    /**
     * S9 — gerbang extended move: tahan sinyal BUY bila koin sudah naik ≥ ini (%) dalam 24 jam.
     * `0` = nonaktif. Lihat [ScalpingConfig.MAX_CHANGE_24H_PCT].
     */
    val maxChange24hPct: Double = ScalpingConfig.MAX_CHANGE_24H_PCT,
    val rvolSetupMin: Double = ScalpingConfig.RVOL_SETUP_MIN,
    val rvolRetestMin: Double = ScalpingConfig.RVOL_RETEST_MIN,
    val buyPressureBreakout: Double = ScalpingConfig.BUY_PRESSURE_BREAKOUT,
    val buyPressureRetest: Double = ScalpingConfig.BUY_PRESSURE_RETEST,
    val chopHardRanging: Double = ScalpingConfig.CHOP_HARD_RANGING,
    val retestMaxDistPct: Double = ScalpingConfig.RETEST_MAX_DIST_PCT,
    val breakoutAboveFactor: Double = ScalpingConfig.BREAKOUT_ABOVE_FACTOR,
    val retestAboveFactor: Double = ScalpingConfig.RETEST_ABOVE_FACTOR,
    val retestMinStructureStrength: Int = ScalpingConfig.RETEST_MIN_STRUCTURE_STRENGTH,
    val pullbackSupportMaxDistPct: Double = ScalpingConfig.PULLBACK_SUPPORT_MAX_DIST_PCT,
    // --- P0 KriptoYoi (semua angka pengukuran dibaca dari sini, bukan literal di engine/replay) ---
    /** Umur sinyal scalping dalam candle M1. `<= 0` diperlakukan sebagai 1 candle. */
    val ttlBarsM1: Int = ScalpingConfig.TTL_BARS_M1,
    /** Jumlah TF bullish minimum dari 4 TF (H1/M15/M5/M1). `0` = gerbang MTF nonaktif. */
    val mtfMinBullish: Int = ScalpingConfig.MTF_MIN_BULLISH,
    /** Bila true, H1 atau M15 bearish memblokir LONG walau hitungan bullish cukup. */
    val mtfBlockHighTfBearish: Boolean = ScalpingConfig.MTF_BLOCK_HIGH_TF_BEARISH,
    /** BTC gatekeeper p0. Bila false, hasil analisis BTC tidak memveto sinyal. */
    val btcVetoEnabled: Boolean = ScalpingConfig.BTC_VETO_ENABLED,
    /** Return BTC 5M terburuk dari dua candle terakhir (%) yang memicu veto. */
    val btcReturnVetoPct: Double = ScalpingConfig.BTC_RETURN_VETO_PCT,
    /** ATR ratio BTC yang memicu veto bila arah bearish impulsif. */
    val btcAtrRatioVeto: Double = ScalpingConfig.BTC_ATR_RATIO_VETO,
    val btcEmaPeriod: Int = ScalpingConfig.BTC_EMA_PERIOD,
    /** Ambang ADX regime trending (p0: 25). */
    val regimeAdxTrending: Double = ScalpingConfig.ADX_TRENDING,
    /** Ambang ADX regime lemah/ranging (p0: 20). */
    val regimeAdxWeak: Double = ScalpingConfig.ADX_WEAK,
    /** Ambang CHOP ranging (p0: 61.8). */
    val regimeChopRanging: Double = ScalpingConfig.CHOP_RANGING,
    /** Ambang CHOP maksimum agar regime masih boleh disebut trending (p0: 55). */
    val regimeChopTrendMax: Double = ScalpingConfig.CHOP_TREND_MAX,
    /** Parameter exit paper trade (P2) — mirror blok `exit` di `scalp_config_exit_*.json`. */
    val exit: ScalpExitParams = ScalpExitParams.DEFAULT
) {

    fun minScoreForSetup(setupName: String): Int = when (setupName) {
        "BREAKOUT_RETEST" -> minScoreBreakoutRetest
        else -> minScoreLong
    }

    /**
     * Serializes to JSON matching the schema of `AUDIT-2026-11-08.md`.
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("_comment", "Exported ScalpingRuntimeConfig from agentfeed Android")
        json.put("min_score_long", minScoreLong)
        json.put("min_score_strong", minScoreStrong)
        json.put("min_score_breakout_retest", minScoreBreakoutRetest)
        json.put("min_net_rr", minNetRr)
        json.put("target_net_rr", targetNetRr)
        json.put("min_risk_pct", minRiskPct)
        json.put("max_risk_atr", maxRiskAtr)
        json.put("max_tp2_r", maxTp2R)
        json.put("min_friction_multiple", minFrictionMultiple)
        json.put("slippage_pct", slippagePct)
        json.put("rvol_breakout_min", rvolBreakoutMin)
        json.put("max_change_24h_pct", maxChange24hPct)
        json.put("rvol_setup_min", rvolSetupMin)
        json.put("rvol_retest_min", rvolRetestMin)
        json.put("buy_pressure_breakout", buyPressureBreakout)
        json.put("buy_pressure_retest", buyPressureRetest)
        json.put("chop_hard_ranging", chopHardRanging)
        json.put("retest_max_dist_pct", retestMaxDistPct)
        json.put("breakout_above_factor", breakoutAboveFactor)
        json.put("retest_above_factor", retestAboveFactor)
        json.put("retest_min_structure_strength", retestMinStructureStrength)
        json.put("pullback_support_max_dist_pct", pullbackSupportMaxDistPct)
        // P0 — dibaca `AUDIT-2026-11-08.md`; jangan menulis angka ini sebagai literal di tool.
        json.put("ttl_bars_m1", ttlBarsM1)
        json.put("mtf_min_bullish", mtfMinBullish)
        json.put("mtf_block_high_tf_bearish", mtfBlockHighTfBearish)
        json.put("btc_veto_enabled", btcVetoEnabled)
        json.put("btc_return_veto_pct", btcReturnVetoPct)
        json.put("btc_atr_ratio_veto", btcAtrRatioVeto)
        json.put("btc_ema_period", btcEmaPeriod)
        json.put("regime_adx_trending", regimeAdxTrending)
        json.put("regime_adx_weak", regimeAdxWeak)
        json.put("regime_chop_ranging", regimeChopRanging)
        json.put("regime_chop_trend_max", regimeChopTrendMax)

        // Blok exit (P2) — dibaca `AUDIT-2026-11-08.md` sebagai cfg["exit"]
        json.put("exit", exit.toJson())

        // Include Fee Truth snapshot block for offline replay parity.
        // Angka diambil dari FeeSchedule supaya ekspor tidak pernah menjadi definisi tarif kedua.
        val feeObj = JSONObject()
        feeObj.put("_comment", "Tokocrypto ALL-IN regular: USDT 0.4044% per sisi. IDR: buy taker 0.2222 / sell taker 0.4322")
        feeObj.put("quote", "USDT")
        feeObj.put("buy_taker_pct", agu.analys.config.FeeSchedule.TOKOCRYPTO_USDT_BUY_TAKER * 100.0)
        feeObj.put("sell_taker_pct", agu.analys.config.FeeSchedule.TOKOCRYPTO_USDT_SELL_TAKER * 100.0)
        feeObj.put("buy_maker_pct", agu.analys.config.FeeSchedule.TOKOCRYPTO_USDT_BUY_TAKER * 100.0)
        feeObj.put("sell_maker_pct", agu.analys.config.FeeSchedule.TOKOCRYPTO_USDT_SELL_TAKER * 100.0)
        feeObj.put("idr_reference", JSONObject().apply {
            put("buy_taker_pct", agu.analys.config.FeeSchedule.TOKOCRYPTO_IDR_BUY_TAKER * 100.0)
            put("sell_taker_pct", agu.analys.config.FeeSchedule.TOKOCRYPTO_IDR_SELL_TAKER * 100.0)
            put("buy_maker_pct", agu.analys.config.FeeSchedule.TOKOCRYPTO_IDR_BUY_MAKER * 100.0)
            put("sell_maker_pct", agu.analys.config.FeeSchedule.TOKOCRYPTO_IDR_SELL_MAKER * 100.0)
        })
        json.put("fee", feeObj)

        val replayObj = JSONObject()
        replayObj.put("lookahead_bars", 120)
        replayObj.put("m1_window", 250)
        replayObj.put("m15_min_bars", 20)
        replayObj.put("h1_min_bars", 20)
        replayObj.put("htf_source", "synthesize")
        json.put("replay", replayObj)

        return json
    }

    fun toJsonString(indent: Int = 2): String = toJson().toString(indent)

    /** Short human-readable summary of primary gates */
    fun toSummaryString(): String {
        return "MinScore: $minScoreLong (Retest: $minScoreBreakoutRetest, Strong: $minScoreStrong) | " +
            "MinNetRR: ${minNetRr}x | TargetRR: ${targetNetRr}x | Lantai friksi: ${minFrictionMultiple}x | " +
            "RVOL Breakout: ${rvolBreakoutMin}x | CHOP: $chopHardRanging | " +
            "TTL: ${ttlBarsM1} candle M1 | MTF: min $mtfMinBullish/4 bullish | " +
            "BTC veto: ${if (btcVetoEnabled) "${btcReturnVetoPct}% / ATR ${btcAtrRatioVeto}x" else "nonaktif"} | " +
            "Exit: ${exit.mode.label}"
    }

    /** Salinan dengan parameter exit paper trade diganti. */
    fun withExit(params: ScalpExitParams): ScalpingRuntimeConfig = copy(exit = params)

    companion object {
        val DEFAULT = ScalpingRuntimeConfig()

        val STRICT = ScalpingRuntimeConfig(
            minScoreLong = 70,
            minScoreStrong = 80,
            minScoreBreakoutRetest = 80,
            minNetRr = 1.30,
            targetNetRr = 2.50,
            minRiskPct = 0.5,
            maxRiskAtr = 1.5,
            maxTp2R = 4.0,
            minFrictionMultiple = 8.0,
            slippagePct = 0.08,
            rvolBreakoutMin = 1.8,
            rvolSetupMin = 1.4,
            rvolRetestMin = 1.8,
            maxChange24hPct = 0.0, // S9 default nonaktif — lihat ScalpingConfig.MAX_CHANGE_24H_PCT
            buyPressureBreakout = 1.25,
            buyPressureRetest = 1.25,
            chopHardRanging = 60.0,
            retestMaxDistPct = 1.0,
            breakoutAboveFactor = 0.999,
            retestAboveFactor = 0.997,
            retestMinStructureStrength = 70,
            pullbackSupportMaxDistPct = 1.0
        )

        val LOOSE = ScalpingRuntimeConfig(
            minScoreLong = 50,
            minScoreStrong = 65,
            minScoreBreakoutRetest = 65,
            minNetRr = 1.00,
            targetNetRr = 1.75,
            minRiskPct = 0.4,
            maxRiskAtr = 2.5,
            maxTp2R = 6.0,
            minFrictionMultiple = 4.0,
            slippagePct = 0.08,
            rvolBreakoutMin = 1.2,
            rvolSetupMin = 1.0,
            rvolRetestMin = 1.2,
            maxChange24hPct = 0.0, // S9 default nonaktif — lihat ScalpingConfig.MAX_CHANGE_24H_PCT
            buyPressureBreakout = 1.05,
            buyPressureRetest = 1.05,
            chopHardRanging = 70.0,
            retestMaxDistPct = 1.5,
            breakoutAboveFactor = 0.995,
            retestAboveFactor = 0.990,
            retestMinStructureStrength = 50,
            pullbackSupportMaxDistPct = 1.5
        )

        fun fromJson(json: JSONObject): ScalpingRuntimeConfig {
            return ScalpingRuntimeConfig(
                minScoreLong = json.optInt("min_score_long", ScalpingConfig.MIN_SCORE_LONG),
                minScoreStrong = json.optInt("min_score_strong", ScalpingConfig.MIN_SCORE_STRONG),
                minScoreBreakoutRetest = json.optInt("min_score_breakout_retest", ScalpingConfig.MIN_SCORE_BREAKOUT_RETEST),
                minNetRr = json.optDouble("min_net_rr", ScalpingConfig.MIN_NET_RR),
                targetNetRr = json.optDouble("target_net_rr", ScalpingConfig.TARGET_NET_RR),
                minRiskPct = json.optDouble("min_risk_pct", ScalpingConfig.MIN_RISK_PCT),
                maxRiskAtr = json.optDouble("max_risk_atr", ScalpingConfig.MAX_RISK_ATR),
                maxTp2R = json.optDouble("max_tp2_r", ScalpingConfig.MAX_TP2_R),
                minFrictionMultiple = json.optDouble("min_friction_multiple", ScalpingConfig.MIN_FRICTION_MULTIPLE),
                slippagePct = json.optDouble("slippage_pct", ScalpingConfig.DEFAULT_SLIPPAGE_PCT),
                rvolBreakoutMin = json.optDouble("rvol_breakout_min", ScalpingConfig.RVOL_BREAKOUT_MIN),
                maxChange24hPct = json.optDouble("max_change_24h_pct", ScalpingConfig.MAX_CHANGE_24H_PCT),
                rvolSetupMin = json.optDouble("rvol_setup_min", ScalpingConfig.RVOL_SETUP_MIN),
                rvolRetestMin = json.optDouble("rvol_retest_min", ScalpingConfig.RVOL_RETEST_MIN),
                buyPressureBreakout = json.optDouble("buy_pressure_breakout", ScalpingConfig.BUY_PRESSURE_BREAKOUT),
                buyPressureRetest = json.optDouble("buy_pressure_retest", ScalpingConfig.BUY_PRESSURE_RETEST),
                chopHardRanging = json.optDouble("chop_hard_ranging", ScalpingConfig.CHOP_HARD_RANGING),
                retestMaxDistPct = json.optDouble("retest_max_dist_pct", ScalpingConfig.RETEST_MAX_DIST_PCT),
                breakoutAboveFactor = json.optDouble("breakout_above_factor", ScalpingConfig.BREAKOUT_ABOVE_FACTOR),
                retestAboveFactor = json.optDouble("retest_above_factor", ScalpingConfig.RETEST_ABOVE_FACTOR),
                retestMinStructureStrength = json.optInt("retest_min_structure_strength", ScalpingConfig.RETEST_MIN_STRUCTURE_STRENGTH),
                pullbackSupportMaxDistPct = json.optDouble("pullback_support_max_dist_pct", ScalpingConfig.PULLBACK_SUPPORT_MAX_DIST_PCT),
                ttlBarsM1 = json.optInt("ttl_bars_m1", ScalpingConfig.TTL_BARS_M1),
                mtfMinBullish = json.optInt("mtf_min_bullish", ScalpingConfig.MTF_MIN_BULLISH),
                mtfBlockHighTfBearish = json.optBoolean("mtf_block_high_tf_bearish", ScalpingConfig.MTF_BLOCK_HIGH_TF_BEARISH),
                btcVetoEnabled = json.optBoolean("btc_veto_enabled", ScalpingConfig.BTC_VETO_ENABLED),
                btcReturnVetoPct = json.optDouble("btc_return_veto_pct", ScalpingConfig.BTC_RETURN_VETO_PCT),
                btcAtrRatioVeto = json.optDouble("btc_atr_ratio_veto", ScalpingConfig.BTC_ATR_RATIO_VETO),
                btcEmaPeriod = json.optInt("btc_ema_period", ScalpingConfig.BTC_EMA_PERIOD),
                regimeAdxTrending = json.optDouble("regime_adx_trending", ScalpingConfig.ADX_TRENDING),
                regimeAdxWeak = json.optDouble("regime_adx_weak", ScalpingConfig.ADX_WEAK),
                regimeChopRanging = json.optDouble("regime_chop_ranging", ScalpingConfig.CHOP_RANGING),
                regimeChopTrendMax = json.optDouble("regime_chop_trend_max", ScalpingConfig.CHOP_TREND_MAX),
                exit = ScalpExitParams.fromJson(json.optJSONObject("exit"))
            )
        }

        fun fromJsonString(jsonStr: String): ScalpingRuntimeConfig? {
            return try {
                fromJson(JSONObject(jsonStr))
            } catch (_: Exception) {
                null
            }
        }

        private val _currentFlow = MutableStateFlow(DEFAULT)
        val currentFlow: StateFlow<ScalpingRuntimeConfig> = _currentFlow.asStateFlow()

        var current: ScalpingRuntimeConfig
            get() = _currentFlow.value
            set(value) {
                _currentFlow.value = value
            }
    }
}

enum class SensitivityPreset(val label: String, val description: String) {
    DEFAULT("Standar", "Threshold default ScalpingConfig (Balanced)"),
    STRICT("Ketat", "Threshold selektif — frekuensi rendah, sinyal terkuat"),
    LOOSE("Longgar", "Threshold longgar — frekuensi tinggi untuk eksperimen"),
    CUSTOM("Kustom", "Parameter disesuaikan lewat slider manual")
}
