package agu.analys.engine.intraday

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Holder runtime untuk konfigurasi mode **INTRADAY** (`StrategyMode.OFFICE_DAILY`).
 *
 * Berbeda dengan `ScalpingRuntimeConfig`, berkas ini **tidak** membuat data class kedua berisi
 * 19 field yang sama dengan [IntradayConfig]: [IntradayConfig] tetap satu-satunya definisi knob
 * intraday, dan holder ini cukup menyimpan instance-nya. Menyalin 19 field ke data class baru
 * adalah definisi ganda yang cepat melenceng (pola yang berulang kali menjadi bug di repo ini).
 *
 * Aturan yang dijaga (lihat `app/AGENTS.md` §79.1):
 * - Holder ini dan UI slider-nya dibangun dalam **satu paket** — parameter tanpa pembaca dilarang.
 * - Nilai mengalir lewat **satu pintu**: `ModeSignalInput.intradayConfig` →
 *   `IntradayEvaluator.evaluate`. Engine foreground dan `CandidateScanWorker` membaca holder ini
 *   lewat pintu yang sama, sehingga slider tidak pernah membuat dua jalur dengan angka berbeda
 *   (aturan N6/N17: satu mode, satu mesin, satu angka).
 * - Bawaan **selalu** [IntradayConfig.DEFAULT], sehingga angka replay paket A–F (§79–§85
 *   `AUDIT-2026-11-08.md`) tetap reproducible persis saat pengguna tidak menyentuh slider.
 * - `slippagePct` di sini hanya milik perhitungan sinyal intraday; ia **tidak** disalurkan ke
 *   basis P&L simulasi (`simCoordinator.setSlippagePct` hanya dijalankan dari config scalping).
 *
 * @property SELEKTIF lebih ketat: frekuensi lebih rendah (band volatilitas lebih sempit, SL lebih
 *   lebar, TP lebih ambisius). Bukan janji profit — sinyal intraday terukur negatif di semua
 *   konfigurasi yang diuji (§79.3).
 * @property STANDAR persis [IntradayConfig.DEFAULT] (bawaan).
 * @property LONGGAR lebih longgar: frekuensi lebih tinggi untuk eksperimen. `maxTp1Pct` = 6,0%
 *   adalah nilai **terukur** (§83: 17 BUY, avg net −0,810% pada 11 pair v7) — masih negatif.
 */
object IntradayRuntimeConfig {

    /** Bawaan = standar. */
    val DEFAULT: IntradayConfig = IntradayConfig.DEFAULT

    /** Preset ketat: semua nilai lain dari [IntradayConfig.DEFAULT], hanya knob yang berubah ditulis. */
    val STRICT: IntradayConfig = IntradayConfig.DEFAULT.copy(
        minNetRrTp1 = 2.0,
        maxTp1Pct = 4.5,
        atrFloorPct = 2.0,
        atrCapPct = 3.5,
        slAtrMult = 0.4,
        minSlPct = 2.0,
        maxSlPct = 3.2,
        macdHistMinPct = 0.02,
        flashDumpTraumaCandles = 16
    )

    /** Preset longgar: `maxTp1Pct` 6,0% adalah nilai terukur §83 (masih negatif), bukan klaim edge. */
    val LOOSE: IntradayConfig = IntradayConfig.DEFAULT.copy(
        maxTp1Pct = 6.0,
        atrFloorPct = 1.2,
        atrCapPct = 4.5,
        slAtrMult = 0.25,
        minSlPct = 1.2,
        macdHistMinPct = 0.0
    )

    private val _currentFlow = MutableStateFlow(DEFAULT)
    val currentFlow: StateFlow<IntradayConfig> = _currentFlow.asStateFlow()

    var current: IntradayConfig
        get() = _currentFlow.value
        set(value) {
            _currentFlow.value = value
        }

    /**
     * Serializes to JSON matching the schema of `AUDIT-2026-11-08.md`.
     */
    fun toJson(config: IntradayConfig): JSONObject {
        val json = JSONObject()
        json.put("_comment", "Exported IntradayRuntimeConfig from agentfeed Android")
        json.put("slippage_pct", config.slippagePct)
        json.put("use_maker_fee", config.useMakerFee)
        json.put("min_net_rr_tp1", config.minNetRrTp1)
        json.put("flash_dump_scan_candles", config.flashDumpScanCandles)
        json.put("flash_dump_trauma_candles", config.flashDumpTraumaCandles)
        json.put("atr_floor_pct", config.atrFloorPct)
        json.put("atr_cap_pct", config.atrCapPct)
        json.put("sl_atr_mult", config.slAtrMult)
        json.put("no_support_atr_mult", config.noSupportAtrMult)
        json.put("max_sl_pct", config.maxSlPct)
        json.put("min_sl_pct", config.minSlPct)
        json.put("min_sl_friction_multiple", config.minSlFrictionMultiple)
        json.put("tp1_atr_mult", config.tp1AtrMult)
        json.put("max_tp1_pct", config.maxTp1Pct)
        json.put("tp2_atr_mult", config.tp2AtrMult)
        json.put("min_tp2_pct", config.minTp2Pct)
        json.put("max_tp2_pct", config.maxTp2Pct)
        json.put("macd_hist_min_pct", config.macdHistMinPct)
        return json
    }

    fun toJsonString(config: IntradayConfig, indent: Int = 2): String =
        toJson(config).toString(indent)

    fun fromJson(json: JSONObject): IntradayConfig {
        val d = IntradayConfig.DEFAULT
        return IntradayConfig(
            slippagePct = json.optDouble("slippage_pct", d.slippagePct),
            useMakerFee = json.optBoolean("use_maker_fee", d.useMakerFee),
            minNetRrTp1 = json.optDouble("min_net_rr_tp1", d.minNetRrTp1),
            flashDumpScanCandles = json.optInt("flash_dump_scan_candles", d.flashDumpScanCandles),
            flashDumpTraumaCandles = json.optInt("flash_dump_trauma_candles", d.flashDumpTraumaCandles),
            atrFloorPct = json.optDouble("atr_floor_pct", d.atrFloorPct),
            atrCapPct = json.optDouble("atr_cap_pct", d.atrCapPct),
            slAtrMult = json.optDouble("sl_atr_mult", d.slAtrMult),
            noSupportAtrMult = json.optDouble("no_support_atr_mult", d.noSupportAtrMult),
            maxSlPct = json.optDouble("max_sl_pct", d.maxSlPct),
            minSlPct = json.optDouble("min_sl_pct", d.minSlPct),
            minSlFrictionMultiple = json.optDouble("min_sl_friction_multiple", d.minSlFrictionMultiple),
            tp1AtrMult = json.optDouble("tp1_atr_mult", d.tp1AtrMult),
            maxTp1Pct = json.optDouble("max_tp1_pct", d.maxTp1Pct),
            tp2AtrMult = json.optDouble("tp2_atr_mult", d.tp2AtrMult),
            minTp2Pct = json.optDouble("min_tp2_pct", d.minTp2Pct),
            maxTp2Pct = json.optDouble("max_tp2_pct", d.maxTp2Pct),
            macdHistMinPct = json.optDouble("macd_hist_min_pct", d.macdHistMinPct)
        )
    }

    fun fromJsonString(jsonStr: String): IntradayConfig? {
        return try {
            fromJson(JSONObject(jsonStr))
        } catch (_: Exception) {
            null
        }
    }

    /** Ringkasan satu baris untuk log servis (pola `ScalpingRuntimeConfig.toSummaryString`). */
    fun toSummaryString(config: IntradayConfig): String {
        return "NetRR TP1 min: ${config.minNetRrTp1}x | Cap TP1: ${config.maxTp1Pct}% | " +
            "SL ${config.minSlPct}–${config.maxSlPct}% (${config.slAtrMult}x ATR) | " +
            "ATR band ${config.atrFloorPct}–${config.atrCapPct}% | " +
            "MACD hist min ${config.macdHistMinPct}%"
    }
}

enum class IntradaySensitivityPreset(val label: String, val description: String) {
    STANDARD("Standar", "Bawaan IntradayConfig — angka yang diukur di §79–§85 AUDIT-2026-11-08.md"),
    STRICT("Selektif", "Ambang lebih ketat — frekuensi sinyal lebih rendah"),
    LOOSE("Longgar", "Frekuensi lebih tinggi untuk eksperimen (cap TP1 6% = nilai terukur §83, masih negatif)"),
    CUSTOM("Kustom", "Parameter disesuaikan lewat slider manual")
}
