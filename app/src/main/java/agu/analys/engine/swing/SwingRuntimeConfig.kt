package agu.analys.engine.swing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Holder runtime untuk konfigurasi mode **SWING** (`StrategyMode.SWING`).
 *
 * Pola persis `IntradayRuntimeConfig` (paket G, `AUDIT-2026-11-08.md` §86):
 * - Holder ini **menyimpan instance [SwingConfig]**, bukan data class kedua berisi 18 field yang
 *   sama — definisi ganda adalah pola bug yang berulang di repo ini.
 * - Nilai mengalir lewat **satu pintu**: `ModeSignalInput.swingConfig` →
 *   `ModeSignalEvaluator.evaluateSwing` → `SwingEvaluator.evaluate(config = …)`. Engine foreground
 *   dan `CandidateScanWorker` sama-sama lewat pintu itu; jangan menambah pembaca holder langsung
 *   di evaluator/worker/UI detail lain.
 * - Bawaan **selalu** [SwingConfig.DEFAULT] (= literal `SwingEvaluator` sebelum slider ada),
 *   sehingga perilaku engine tidak berubah saat pengguna tidak menyentuh slider.
 * - Preset adalah **preset frekuensi**, bukan klaim profit: sinyal SWING belum pernah diukur
 *   positif setelah biaya pada konfigurasi mana pun, jadi labelnya menjelaskan frekuensi, bukan edge.
 *
 * @property SELEKTIF lebih ketat: ambang naik, frekuensi lebih rendah.
 * @property STANDAR persis [SwingConfig.DEFAULT] (bawaan).
 * @property LONGGAR lebih longgar: frekuensi lebih tinggi untuk eksperimen.
 */
object SwingRuntimeConfig {

    /** Bawaan = standar. */
    val DEFAULT: SwingConfig = SwingConfig.DEFAULT

    /** Preset ketat: semua nilai lain dari [SwingConfig.DEFAULT], hanya knob yang berubah ditulis. */
    val STRICT: SwingConfig = SwingConfig.DEFAULT.copy(
        minNetRr = 2.5,
        minEntryScore = 50.0,
        minConfluenceCheckpoints = 5,
        minBuySellRatio = 1.30,
        overboughtRsi = 68.0,
        maxAtrExtension = 1.4,
        maxDistToHighPct = 0.8,
        strongVolumeRatio = 1.40,
        nearLevelPct = 0.8,
        rejectionBodyRatioMin = 0.55,
        breakoutBodyRatioMin = 0.50,
        slBufferAtrRejection = 0.25,
        slAtrMaxRejection = 1.6,
        slMaxDistancePct = 6.0,
        slMinDistancePct = 2.0,
        tp1AtrMult = 2.4,
        tp2AtrMult = 3.8
    )

    /** Preset longgar: frekuensi lebih tinggi untuk eksperimen. */
    val LOOSE: SwingConfig = SwingConfig.DEFAULT.copy(
        minNetRr = 1.5,
        minEntryScore = 36.0,
        minConfluenceCheckpoints = 3,
        minBuySellRatio = 1.05,
        overboughtRsi = 78.0,
        maxAtrExtension = 2.5,
        maxDistToHighPct = 2.0,
        strongVolumeRatio = 1.10,
        nearLevelPct = 2.0,
        rejectionBodyRatioMin = 0.35,
        breakoutBodyRatioMin = 0.30,
        slBufferAtrRejection = 0.15,
        slAtrMaxRejection = 1.2,
        slMaxDistancePct = 8.0,
        slMinDistancePct = 1.2,
        tp1AtrMult = 1.6,
        tp2AtrMult = 3.0
    )

    private val _currentFlow = MutableStateFlow(DEFAULT)
    val currentFlow: StateFlow<SwingConfig> = _currentFlow.asStateFlow()

    var current: SwingConfig
        get() = _currentFlow.value
        set(value) {
            _currentFlow.value = value
        }

    /**
     * Serializes to JSON matching the schema of `swing_config.json` (kunci snake_case).
     */
    fun toJson(config: SwingConfig): JSONObject {
        val json = JSONObject()
        json.put("_comment", "Exported SwingRuntimeConfig from agentfeed Android")
        json.put("min_net_rr", config.minNetRr)
        json.put("min_entry_score", config.minEntryScore)
        json.put("min_confluence_checkpoints", config.minConfluenceCheckpoints)
        json.put("min_buy_sell_ratio", config.minBuySellRatio)
        json.put("overbought_rsi", config.overboughtRsi)
        json.put("max_atr_extension", config.maxAtrExtension)
        json.put("strong_volume_ratio", config.strongVolumeRatio)
        json.put("near_level_pct", config.nearLevelPct)
        json.put("rejection_body_ratio_min", config.rejectionBodyRatioMin)
        json.put("breakout_body_ratio_min", config.breakoutBodyRatioMin)
        json.put("max_dist_to_high_pct", config.maxDistToHighPct)
        json.put("sl_buffer_atr_rejection", config.slBufferAtrRejection)
        json.put("sl_atr_max_rejection", config.slAtrMaxRejection)
        json.put("sl_max_distance_pct", config.slMaxDistancePct)
        json.put("sl_min_distance_pct", config.slMinDistancePct)
        json.put("tp1_atr_mult", config.tp1AtrMult)
        json.put("tp2_atr_mult", config.tp2AtrMult)
        json.put("slippage_pct", config.slippagePct)
        return json
    }

    fun toJsonString(config: SwingConfig, indent: Int = 2): String =
        toJson(config).toString(indent)

    fun fromJson(json: JSONObject): SwingConfig {
        val d = SwingConfig.DEFAULT
        return SwingConfig(
            minNetRr = json.optDouble("min_net_rr", d.minNetRr),
            minEntryScore = json.optDouble("min_entry_score", d.minEntryScore),
            minConfluenceCheckpoints = json.optInt("min_confluence_checkpoints", d.minConfluenceCheckpoints),
            minBuySellRatio = json.optDouble("min_buy_sell_ratio", d.minBuySellRatio),
            overboughtRsi = json.optDouble("overbought_rsi", d.overboughtRsi),
            maxAtrExtension = json.optDouble("max_atr_extension", d.maxAtrExtension),
            strongVolumeRatio = json.optDouble("strong_volume_ratio", d.strongVolumeRatio),
            nearLevelPct = json.optDouble("near_level_pct", d.nearLevelPct),
            rejectionBodyRatioMin = json.optDouble("rejection_body_ratio_min", d.rejectionBodyRatioMin),
            breakoutBodyRatioMin = json.optDouble("breakout_body_ratio_min", d.breakoutBodyRatioMin),
            maxDistToHighPct = json.optDouble("max_dist_to_high_pct", d.maxDistToHighPct),
            slBufferAtrRejection = json.optDouble("sl_buffer_atr_rejection", d.slBufferAtrRejection),
            slAtrMaxRejection = json.optDouble("sl_atr_max_rejection", d.slAtrMaxRejection),
            slMaxDistancePct = json.optDouble("sl_max_distance_pct", d.slMaxDistancePct),
            slMinDistancePct = json.optDouble("sl_min_distance_pct", d.slMinDistancePct),
            tp1AtrMult = json.optDouble("tp1_atr_mult", d.tp1AtrMult),
            tp2AtrMult = json.optDouble("tp2_atr_mult", d.tp2AtrMult),
            slippagePct = json.optDouble("slippage_pct", d.slippagePct)
        )
    }

    fun fromJsonString(jsonStr: String): SwingConfig? {
        return try {
            fromJson(JSONObject(jsonStr))
        } catch (_: Exception) {
            null
        }
    }

    /** Ringkasan satu baris untuk log servis (pola `IntradayRuntimeConfig.toSummaryString`). */
    fun toSummaryString(config: SwingConfig): String {
        return "NetR:R min: ${config.minNetRr} | Skor entry: ${config.minEntryScore} | " +
            "Konfluensi: ${config.minConfluenceCheckpoints}/6 | " +
            "SL ${config.slBufferAtrRejection}x ATR (bawah ${config.slMaxDistancePct}%, atas ${config.slMinDistancePct}%) | " +
            "TP1 ${config.tp1AtrMult}x ATR · TP2 ${config.tp2AtrMult}x ATR | " +
            "Vol kuat: ${config.strongVolumeRatio}x | Jarak level: ${config.nearLevelPct}%"
    }
}

enum class SwingSensitivityPreset(val label: String, val description: String) {
    STANDARD("Standar", "Bawaan SwingConfig — literal engine SWING sebelum slider ada"),
    STRICT("Selektif", "Ambang lebih ketat — frekuensi sinyal lebih rendah"),
    LOOSE("Longgar", "Frekuensi lebih tinggi untuk eksperimen"),
    CUSTOM("Kustom", "Parameter disesuaikan lewat slider manual")
}
