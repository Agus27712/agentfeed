package agu.analys.engine.scalping

import org.json.JSONObject

/**
 * Mode exit paper trade scalping (P2).
 *
 * Kunci `key` **wajib** sama dengan `cfg["exit"]["mode"]` di `AUDIT-2026-11-08.md`
 * agar hasil paper di app bisa dibandingkan langsung dengan replay offline.
 * Lihat `AGENT_BRIDGE.md`.
 */
enum class ScalpExitMode(
    val key: String,
    val label: String,
    val description: String
) {
    CLASSIC(
        key = "classic",
        label = "Classic",
        description = "SL / TP2 saja (baseline lama)"
    ),
    TP1_BE(
        key = "tp1_be",
        label = "TP1 + BE",
        description = "TP1 partial 50%, lalu SL digeser ke breakeven net"
    ),
    EARLY(
        key = "early",
        label = "Early Profit",
        description = "Jual lebih awal bila profit net cukup dan momentum melemah"
    ),
    TRAIL(
        key = "trail",
        label = "Trailing",
        description = "Trailing % dari peak setelah trigger breakeven"
    ),
    FULL(
        key = "full",
        label = "Full (gabungan)",
        description = "tp1_be + trail + early (prioritas: SL > early > trail > TP2)"
    );

    /** Mode ini memakai partial TP1 50% + geser SL ke BE. */
    val usesTp1Partial: Boolean get() = this == TP1_BE || this == FULL

    /** Mode ini menggeser SL ke BE saat net profit menyentuh trigger. */
    val usesBreakeven: Boolean get() = this == TRAIL || this == EARLY || this == FULL

    /** Mode ini mengaktifkan trailing dari peak. */
    val usesTrailing: Boolean get() = this == TRAIL || this == FULL

    /** Mode ini mengaktifkan early profit exit. */
    val usesEarlyProfit: Boolean get() = this == EARLY || this == FULL

    companion object {
        fun fromKey(key: String?): ScalpExitMode =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: CLASSIC
    }
}

/**
 * Parameter exit paper trade — mirror blok `exit` di `AUDIT-2026-11-08.md`.
 *
 * `maxHoldBarsM1 = 0` berarti hanya dibatasi lookahead (tanpa time-stop).
 */
data class ScalpExitParams(
    val mode: ScalpExitMode = ScalpExitMode.CLASSIC,
    /** Profit net % minimum untuk memicu early SELL. */
    val minEarlyProfitNetPct: Double = DEFAULT_MIN_EARLY_PROFIT_NET_PCT,
    /** Net profit % yang meng-arm breakeven / trailing. */
    val breakevenTriggerNetPct: Double = DEFAULT_BREAKEVEN_TRIGGER_NET_PCT,
    /** Jarak trailing dari peak, dalam persen. */
    val trailPct: Double = DEFAULT_TRAIL_PCT,
    /** Time-stop dalam bar M1; 0 = nonaktif. */
    val maxHoldBarsM1: Int = DEFAULT_MAX_HOLD_BARS_M1,
    /**
     * **S2 — lantai profit.** Exit profit sukarela (early profit) dan peng-arm-an BE/trailing
     * hanya boleh terjadi setelah profit **kotor** ≥ kelipatan ini × friksi round-trip
     * (fee beli + fee jual + 2× slippage).
     *
     * Alasan (kasus AMP/USDT): posisi ditutup di **+1,50% kotor** sehingga **54% gerakan
     * dimakan bursa** — yang dibawa pulang hanya +0,69%. Di bawah ~2× friksi, sebagian besar
     * kerja trade pergi ke exchange, dan win-rate minimum agar impas melonjak.
     *
     * `0` = nonaktif (perilaku lama). Lihat `AGENT_BRIDGE.md`.
     */
    val minProfitFrictionMultiple: Double = DEFAULT_MIN_PROFIT_FRICTION_MULTIPLE,
    /**
     * **S8 — trigger breakeven proporsional ATR** (dalam **net %**).
     *
     * Nilai efektif = `max(breakevenTriggerNetPct, beTriggerAtrFraction × ATR%)`.
     * Kasus GNS: `breakeven_trigger_net_pct = 0,35%` berada **di bawah noise satu candle**
     * (ATR 3,31%), sehingga SL bergeser ke impas lalu tersapu wick normal — engine keluar
     * +0,86% padahal harga lanjut ke +7,09%. `0` = pakai [breakevenTriggerNetPct] apa adanya.
     */
    val beTriggerAtrFraction: Double = DEFAULT_BE_TRIGGER_ATR_FRACTION,
    /**
     * **S8 — jarak trailing proporsional ATR** (dalam **% kotor dari peak**).
     *
     * Nilai efektif = `trailAtrFraction × ATR%` bila ATR tersedia, jika tidak jatuh ke [trailPct].
     * `trailPct = 0,15%` ≈ 1/22 ATR untuk GNS — cukup untuk tersapu satu wick.
     * `0` = pakai [trailPct].
     */
    val trailAtrFraction: Double = DEFAULT_TRAIL_ATR_FRACTION
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.key)
        put("min_early_profit_net_pct", minEarlyProfitNetPct)
        put("breakeven_trigger_net_pct", breakevenTriggerNetPct)
        put("trail_pct", trailPct)
        put("max_hold_bars_m1", maxHoldBarsM1)
        put("min_profit_friction_multiple", minProfitFrictionMultiple)
        put("be_trigger_atr_fraction", beTriggerAtrFraction)
        put("trail_atr_fraction", trailAtrFraction)
    }

    /** S2 — lantai profit kotor (%) sebagai kelipatan friksi round-trip. 0 = tanpa lantai. */
    fun minProfitFloorGrossPct(buyFeePct: Double, sellFeePct: Double, slippagePct: Double): Double =
        if (minProfitFrictionMultiple <= 0.0) 0.0
        else minProfitFrictionMultiple * (buyFeePct + sellFeePct + 2.0 * slippagePct)

    /** S8 — trigger BE efektif (net %). Sama dengan [breakevenTriggerNetPct] bila ATR tak tersedia. */
    fun effectiveBreakevenTriggerNetPct(atrPct: Double): Double =
        if (beTriggerAtrFraction > 0.0 && atrPct > 0.0) {
            maxOf(breakevenTriggerNetPct, beTriggerAtrFraction * atrPct)
        } else {
            breakevenTriggerNetPct
        }

    /** S8 — jarak trailing efektif (% kotor dari peak). Sama dengan [trailPct] bila ATR tak tersedia. */
    fun effectiveTrailDistancePct(atrPct: Double): Double =
        if (trailAtrFraction > 0.0 && atrPct > 0.0) trailAtrFraction * atrPct else trailPct

    companion object {
        const val DEFAULT_MIN_EARLY_PROFIT_NET_PCT = 0.45
        const val DEFAULT_BREAKEVEN_TRIGGER_NET_PCT = 0.40
        const val DEFAULT_TRAIL_PCT = 0.35

        /** S2 — default 2× friksi ≈ 1,94% kotor untuk Tokocrypto USDT taker. */
        const val DEFAULT_MIN_PROFIT_FRICTION_MULTIPLE = 2.0

        /** S8 — trigger BE ≈ 0,5× ATR (audit GNS: BE 0,35% jauh di bawah noise). */
        const val DEFAULT_BE_TRIGGER_ATR_FRACTION = 0.5

        /** S8 — jarak trailing ≈ 0,6× ATR (audit GNS: 0,15% ≈ 1/22 ATR, tersapu wick). */
        const val DEFAULT_TRAIL_ATR_FRACTION = 0.6

        /**
         * Time-stop default (bar M1) — **disamakan dengan blok `exit.max_hold_bars_m1`
         * di `AUDIT-2026-11-08.md` dan replay offline** (`AUDIT-2026-11-08.md`),
         * yaitu 90 bar. Sebelumnya 0 (tanpa time-stop), yang membuat hasil paper trade
         * berbeda dari replay untuk deret bar yang sama.
         */
        const val DEFAULT_MAX_HOLD_BARS_M1 = 90

        val DEFAULT = ScalpExitParams()

        fun fromJson(json: JSONObject?): ScalpExitParams {
            if (json == null) return DEFAULT
            return ScalpExitParams(
                mode = ScalpExitMode.fromKey(json.optString("mode", ScalpExitMode.CLASSIC.key)),
                minEarlyProfitNetPct = json.optDouble(
                    "min_early_profit_net_pct", DEFAULT_MIN_EARLY_PROFIT_NET_PCT
                ),
                breakevenTriggerNetPct = json.optDouble(
                    "breakeven_trigger_net_pct", DEFAULT_BREAKEVEN_TRIGGER_NET_PCT
                ),
                trailPct = json.optDouble("trail_pct", DEFAULT_TRAIL_PCT),
                maxHoldBarsM1 = json.optInt("max_hold_bars_m1", DEFAULT_MAX_HOLD_BARS_M1),
                minProfitFrictionMultiple = json.optDouble(
                    "min_profit_friction_multiple", DEFAULT_MIN_PROFIT_FRICTION_MULTIPLE
                ),
                beTriggerAtrFraction = json.optDouble(
                    "be_trigger_atr_fraction", DEFAULT_BE_TRIGGER_ATR_FRACTION
                ),
                trailAtrFraction = json.optDouble(
                    "trail_atr_fraction", DEFAULT_TRAIL_ATR_FRACTION
                )
            )
        }
    }
}
