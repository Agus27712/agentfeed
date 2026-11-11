package agu.analys.engine.scalping

import kotlin.math.roundToInt

/**
 * Hasil evaluasi gerbang edge untuk satu pair.
 *
 * @param pair nama pair (mis. `ZROUSDT`)
 * @param medianMfe7hPct median pergerakan maksimum (MFE) dalam 7 jam, dari data riil; `null` bila belum ada data
 * @param frictionPct friksi round-trip (fee beli + fee jual + 2× slippage) dalam persen
 * @param ratio `medianMfe7hPct / frictionPct`; `null` bila salah satu tidak tersedia
 * @param eligible `true` layak, `false` tidak layak, `null` belum bisa dinilai (data kurang)
 * @param reason penjelasan singkat berbahasa awam
 */
data class PairEdge(
    val pair: String,
    val medianMfe7hPct: Double?,
    val frictionPct: Double,
    val ratio: Double?,
    val eligible: Boolean?,
    val reason: String
) {
    val ratioText: String get() = ratio?.let { String.format(java.util.Locale.US, "%.2f×", it) } ?: "—"
}

/**
 * **Gerbang kelayakan pair berbasis edge (P4.3).**
 *
 * Latar belakang — lihat `AUDIT-2026-11-08.md` §9.5b dan `FEE_TRUTH.md` §3.
 *
 * Friksi round-trip Tokocrypto USDT adalah **0,9688%**. Bila median pergerakan harga
 * (MFE) dalam horizon trading hanya sekitar 0,5% (BTC, XAUT, PAXG), maka tidak ada
 * strategi apa pun yang bisa profit di pair itu: biaya transaksi lebih besar daripada
 * pergerakan median harga. Sebaliknya ZRO/NEAR/PUMP dengan MFE ≈ 2,7–3,2% punya ruang.
 *
 * Aturan gerbang:
 * ```
 * layak  ⟺  median MFE(horizon) ≥ MIN_MFE_FRICTION_RATIO × friksi_round_trip
 * ```
 *
 * Dua garis berbeda, jangan dicampur:
 * - **rasio < 1,0** → pergerakan median lebih kecil daripada biaya. Ini **mustahil** profit,
 *   apa pun strateginya (contoh: BTC 0,52×, XAUT 0,20×, USDC 0,01×).
 * - **1,0 ≤ rasio < [MIN_MFE_FRICTION_RATIO]** → secara teori mungkin, tetapi marginnya
 *   terlalu tipis untuk diandalkan (contoh: ADA 1,64×). Gerbang menolaknya sebagai *buffer*.
 *
 * Ini syarat **perlu, bukan cukup**: pair yang lolos masih harus dikonfirmasi lewat uji
 * out-of-sample (lihat `tools/split_validate.py`). Tujuannya menyaring pair yang
 * *secara matematis* mustahil profit, supaya tenaga tidak habis di sana.
 *
 * **Sumber data MFE:** `AUDIT-2026-11-08.md` — 40 pair Tokocrypto USDT,
 * 30 hari data M1 riil, MFE diukur pada horizon 420 bar M1 (7 jam), nilai median.
 * Tabel ini dapat diregenerasi dengan `AUDIT-2026-11-08.md`.
 */
object PairEdgeGate {

    /** Kelipatan minimum median MFE terhadap friksi round-trip agar pair dianggap layak. */
    const val MIN_MFE_FRICTION_RATIO = 2.0

    /** Horizon pengukuran MFE dalam bar M1 (7 jam) — samakan dengan harness riset. */
    const val MFE_HORIZON_BARS_M1 = 420

    /**
     * Median MFE 7 jam (%) per pair, Tokocrypto **USDT**, 30 hari M1 riil.
     * Regenerasi: `python3 AUDIT-2026-11-08.md`.
     */
    val MFE_7H_USDT: Map<String, Double> = mapOf(
        "ZROUSDT" to 3.2242,
        "NEARUSDT" to 2.8948,
        "MUBARAKUSDT" to 2.7257,
        "PUMPUSDT" to 2.7227,
        "WUSDT" to 2.6396,
        "STRKUSDT" to 2.6087,
        "ENAUSDT" to 2.2496,
        "QNTUSDT" to 2.2353,
        "PLUMEUSDT" to 2.2246,
        "APTUSDT" to 2.1407,
        "SUSDT" to 2.1284,
        "FETUSDT" to 2.0468,
        "WLDUSDT" to 2.0143,
        "FILUSDT" to 1.9915,
        "ONDOUSDT" to 1.8883,
        "ZECUSDT" to 1.8132,
        "UNIUSDT" to 1.799,
        "SUIUSDT" to 1.7408,
        "TAOUSDT" to 1.7313,
        "ICPUSDT" to 1.6801,
        "RSRUSDT" to 1.611,
        "ADAUSDT" to 1.5867,
        "AVAXUSDT" to 1.5555,
        "CFXUSDT" to 1.3819,
        "LINKUSDT" to 1.0981,
        "DOGEUSDT" to 1.0012,
        "XRPUSDT" to 0.9643,
        "SOLUSDT" to 0.8773,
        "HYPEUSDT" to 0.8624,
        "BNBUSDT" to 0.6348,
        "ETHUSDT" to 0.6273,
        "BTCUSDT" to 0.5016,
        "TRXUSDT" to 0.2347,
        "PAXGUSDT" to 0.208,
        "XAUTUSDT" to 0.1978,
        "FDUSDUSDT" to 0.02,
        "RLUSDUSDT" to 0.01,
        "USD1USDT" to 0.01,
        "USDCUSDT" to 0.01,
        "UUSDT" to 0.01
    )

    /** Friksi round-trip (%) = fee beli + fee jual + 2× slippage. */
    fun frictionRoundTripPct(buyFeePct: Double, sellFeePct: Double, slippagePct: Double): Double =
        buyFeePct + sellFeePct + 2.0 * slippagePct

    /** Peta MFE sesuai kuota (quote) bursa. `IDR` belum punya cukup data → kosong. */
    fun mfeTable(quote: String): Map<String, Double> =
        if (quote.equals("USDT", ignoreCase = true)) MFE_7H_USDT else emptyMap()

    /**
     * Evaluasi kelayakan satu pair.
     *
     * @param pair nama pair
     * @param quote kuota pair (`USDT` / `IDR`)
     * @param frictionPct friksi round-trip (%); pakai [frictionRoundTripPct] untuk menghitung
     */
    fun evaluate(pair: String, quote: String, frictionPct: Double): PairEdge {
        val key = pair.uppercase()
        val mfe = mfeTable(quote)[key]
        if (mfe == null) {
            return PairEdge(
                pair = key, medianMfe7hPct = null, frictionPct = frictionPct,
                ratio = null, eligible = null,
                reason = "Belum ada data MFE untuk pair ini — gerbang edge tidak dapat dinilai."
            )
        }
        if (frictionPct <= 0.0) {
            return PairEdge(
                pair = key, medianMfe7hPct = mfe, frictionPct = frictionPct,
                ratio = null, eligible = null,
                reason = "Friksi tidak diketahui — gerbang edge tidak dapat dinilai."
            )
        }
        val ratio = mfe / frictionPct
        val ok = ratio >= MIN_MFE_FRICTION_RATIO
        val reason = if (ok) {
            "Median pergerakan 7 jam ${fmt(mfe)}% = ${fmt(ratio)}× friksi. Ada ruang untuk menutup biaya."
        } else if (ratio < 1.0) {
            "Median pergerakan 7 jam ${fmt(mfe)}% hanya ${fmt(ratio)}× friksi. Pergerakan median " +
                "LEBIH KECIL daripada biaya transaksi — pair ini mustahil profit di bursa ini."
        } else {
            "Median pergerakan 7 jam ${fmt(mfe)}% = ${fmt(ratio)}× friksi (butuh ≥ " +
                "${fmt(MIN_MFE_FRICTION_RATIO)}×). Secara teori mungkin, tetapi marginnya terlalu " +
                "tipis untuk diandalkan."
        }
        return PairEdge(
            pair = key, medianMfe7hPct = mfe, frictionPct = frictionPct,
            ratio = ratio, eligible = ok, reason = reason
        )
    }

    /** Ringkas: berapa pair yang layak / tidak layak / belum diketahui. */
    fun summarize(quote: String, frictionPct: Double): Triple<Int, Int, Int> {
        var ok = 0
        var no = 0
        var unknown = 0
        mfeTable(quote).keys.forEach { p ->
            when (evaluate(p, quote, frictionPct).eligible) {
                true -> ok++
                false -> no++
                null -> unknown++
            }
        }
        return Triple(ok, no, unknown)
    }

    /** Persentase MFE yang **wajib** ditangkap agar sekadar menutup biaya. */
    fun captureRequiredPct(medianMfePct: Double, frictionPct: Double): Double? =
        if (medianMfePct <= 0.0) null else 100.0 * frictionPct / medianMfePct

    private fun fmt(v: Double): String =
        String.format(java.util.Locale.US, if (v < 10.0) "%.2f" else "%.0f", v)

    /** Daftar pair yang tidak layak, diurutkan dari yang paling parah. */
    fun ineligiblePairs(quote: String, frictionPct: Double): List<PairEdge> =
        mfeTable(quote).keys
            .map { evaluate(it, quote, frictionPct) }
            .filter { it.eligible == false }
            .sortedBy { it.ratio ?: Double.MAX_VALUE }

    /** Pembulatan rasio untuk ditampilkan (1 desimal). */
    fun ratioRounded(edge: PairEdge): Double? = edge.ratio?.let { (it * 10.0).roundToInt() / 10.0 }
}
