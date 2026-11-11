package agu.analys.engine.scalping

import agu.analys.model.CandleBar
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Alasan exit paper trade — **wajib** sama dengan `exit_reason` di `AUDIT-2026-11-08.md`
 * supaya statistik app dan replay bisa dibandingkan.
 */
enum class PaperExitReason(val key: String, val label: String) {
    SL("SL", "Stop Loss"),
    BE_SL("BE_SL", "Breakeven Stop"),
    TRAIL_SL("TRAIL_SL", "Trailing Stop"),
    TP2("TP2", "Target Profit 2"),
    EARLY_PROFIT("EARLY_PROFIT", "Early Profit"),
    TIMEOUT("TIMEOUT", "Timeout / lookahead habis"),
    BAD_RISK("bad_risk", "Risiko tidak valid");

    companion object {
        /** Hanya alasan ini yang dihitung sebagai trade selesai. */
        val RESOLVED: Set<PaperExitReason> = setOf(SL, BE_SL, TRAIL_SL, TP2, EARLY_PROFIT)

        /** Parser kebal untuk jurnal — nilai tak dikenal jatuh ke [TIMEOUT]. */
        fun fromKey(key: String?): PaperExitReason =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: TIMEOUT
    }
}

enum class PaperExitResult(val key: String) {
    WIN("WIN"),
    LOSS("LOSS"),
    UNRESOLVED("UNRESOLVED");

    companion object {
        /** Parser kebal untuk jurnal — nilai tak dikenal jatuh ke [UNRESOLVED]. */
        fun fromKey(key: String?): PaperExitResult =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: UNRESOLVED
    }
}

/** Fase state machine exit — dipakai untuk indikator FSM di UI paper trade. */
enum class PaperExitPhase(val label: String) {
    HOLDING("Posisi terbuka"),
    TP1_PARTIAL("TP1 partial — SL siap ke BE"),
    BREAKEVEN("SL di breakeven net"),
    TRAILING("Trailing dari peak"),
    CLOSED("Posisi tertutup")
}

data class PaperExitDecision(
    val result: PaperExitResult,
    val r: Double,
    val exitIndex: Int?,
    val ambiguous: Boolean,
    val reason: PaperExitReason,
    /** Harga acuan saat posisi ditutup (level SL aktif / TP2 / close bar). 0.0 bila tak berlaku. */
    val exitPrice: Double = 0.0
) {
    val isResolved: Boolean get() = reason in PaperExitReason.RESOLVED
}

/**
 * Mesin exit paper trade (P2) — **paritas** dengan `resolve_trade()` di `AUDIT-2026-11-08.md`.
 *
 * Aturan satuan R seragam untuk SEMUA mode exit:
 * ```
 * R = net_profit_pct(harga_exit) / risk_pct(entry -> sl)
 * ```
 * `net_profit_pct` sudah memperhitungkan buy fee + sell fee + 2x slippage, jadi SL kena
 * **-1R ditambah biaya**, bukan -1R datar.
 *
 * Objek ini murni (tanpa state global) agar bisa diuji dan dipakai ulang oleh
 * [PaperExitStateMachine] maupun replay.
 */
object PaperExitResolver {

    /**
     * Profit net % setelah buy fee + sell fee + 2x slippage.
     *
     * Sejak 2026-10-15 rumusnya tinggal satu di [agu.analys.config.FeeSchedule.netProfitPct] —
     * dipakai bersama halaman portofolio, radar jual, dan kartu posisi detail, supaya angka yang
     * sama tidak lagi tampil berbeda di tempat berbeda. Signature dipertahankan agar uji paritas
     * dan pemanggil lama tetap berlaku.
     */
    fun netProfitPct(
        entry: Double,
        price: Double,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double
    ): Double = agu.analys.config.FeeSchedule.netProfitPct(
        entry = entry,
        price = price,
        buyFeePct = buyFeePct,
        sellFeePct = sellFeePct,
        slippagePct = slippagePct
    )

    /** Harga impas net (profit net = 0) — kebalikan dari [netProfitPct]. */
    fun breakevenPrice(
        entry: Double,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double
    ): Double = agu.analys.config.FeeSchedule.breakevenPrice(
        entry = entry,
        buyFeePct = buyFeePct,
        sellFeePct = sellFeePct,
        slippagePct = slippagePct
    )

    /**
     * Momentum lemah: EMA9 < EMA21 × 0,998, atau RSI di luar 40–78 disertai candle merah,
     * atau candle merah dengan EMA9 < EMA21.
     */
    fun isMomentumWeak(window: List<CandleBar>): Boolean {
        if (window.size < 10) return true
        val closes = window.map { it.close }
        val e9 = emaLast(closes, 9)
        val e21 = emaLast(closes, 21)
        val r = rsiWilder(closes, minOf(14, closes.size - 1))
        val last = window.last()
        val bearCandle = last.close < last.open
        val emaWeak = e9 < e21 * 0.998
        val rsiWeak = r < 40.0 || r > 78.0
        return emaWeak || (rsiWeak && bearCandle) || (bearCandle && e9 < e21)
    }

    /** EMA rekursif dari nilai pertama (identik dengan `ema_last` di Python). */
    fun emaLast(values: List<Double>, period: Int): Double {
        if (period <= 0 || values.isEmpty()) return 0.0
        val k = 2.0 / (period + 1.0)
        var e = values[0]
        for (i in 1 until values.size) {
            e = (values[i] - e) * k + e
        }
        return e
    }

    /** Wilder RSI (identik dengan `rsi_wilder` di Python). */
    fun rsiWilder(closes: List<Double>, period: Int): Double {
        val n = closes.size
        if (period <= 0 || n <= period) return 50.0
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val ch = closes[i] - closes[i - 1]
            if (ch >= 0.0) gain += ch else loss -= ch
        }
        var ag = gain / period
        var al = loss / period
        for (i in period + 1 until n) {
            val ch = closes[i] - closes[i - 1]
            ag = (ag * (period - 1) + (if (ch > 0.0) ch else 0.0)) / period
            al = (al * (period - 1) + (if (ch < 0.0) -ch else 0.0)) / period
        }
        if (al == 0.0) return if (ag == 0.0) 50.0 else 100.0
        return 100.0 - (100.0 / (1.0 + ag / al))
    }

    /**
     * Resolve trade dari bar `entryIndex + 1` ke depan.
     *
     * @param bars deret M1 berurutan waktu.
     * @param entryIndex indeks bar tempat entry terjadi.
     * @param lookaheadBars jumlah bar maksimum yang diperiksa (default 120, selaras replay).
     */
    fun resolve(
        bars: List<CandleBar>,
        entryIndex: Int,
        entry: Double,
        stopLoss: Double,
        tp1: Double,
        tp2: Double,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double,
        params: ScalpExitParams,
        lookaheadBars: Int = DEFAULT_LOOKAHEAD_BARS,
        // S8 — ATR% bar M1 saat entry. `<= 0` = tidak diketahui → jatuh ke nilai persen tetap.
        atrPct: Double = 0.0
    ): PaperExitDecision {
        val risk = entry - stopLoss
        if (risk <= 0.0 || entry <= 0.0) {
            return PaperExitDecision(
                PaperExitResult.UNRESOLVED, 0.0, null, false, PaperExitReason.BAD_RISK
            )
        }
        val riskPct = risk / entry * 100.0
        fun rAt(price: Double): Double =
            netProfitPct(entry, price, buyFeePct, sellFeePct, slippagePct) / riskPct
        // S2/S8 — ambang efektif. Lihat [ScalpExitParams] untuk alasan tiap nilai.
        fun grossAt(price: Double): Double =
            if (entry > 0.0 && price > 0.0) (price / entry - 1.0) * 100.0 else 0.0
        val floorGrossPct = params.minProfitFloorGrossPct(buyFeePct, sellFeePct, slippagePct)
        val beTriggerNetPct = params.effectiveBreakevenTriggerNetPct(atrPct)
        val trailDistancePct = params.effectiveTrailDistancePct(atrPct)

        val bePx = breakevenPrice(entry, buyFeePct, sellFeePct, slippagePct)

        var end = minOf(bars.size, entryIndex + 1 + lookaheadBars)
        if (params.maxHoldBarsM1 > 0) {
            end = minOf(end, entryIndex + 1 + params.maxHoldBarsM1)
        }

        var activeSl = stopLoss
        var beArmed = false
        var trailArmed = false
        var peak = entry
        var partialR = 0.0
        var partialDone = false
        var ambiguous = false

        fun done(
            totalR: Double,
            idx: Int,
            reason: PaperExitReason,
            amb: Boolean = false,
            price: Double = 0.0
        ) =
            PaperExitDecision(
                result = if (totalR > 0.0) PaperExitResult.WIN else PaperExitResult.LOSS,
                r = round4(totalR),
                exitIndex = idx,
                ambiguous = amb,
                reason = reason,
                exitPrice = price
            )

        for (j in (entryIndex + 1) until end) {
            val bar = bars[j]
            val hi = bar.high
            val lo = bar.low
            val cl = bar.close
            peak = max(peak, hi)

            // --- Hard SL selalu dicek lebih dulu ---
            if (lo <= activeSl) {
                if (hi >= tp2 && activeSl <= stopLoss + EPS) ambiguous = true
                val rem = rAt(activeSl)
                val totalR = if (partialDone) partialR + 0.5 * rem else rem
                val reason = when {
                    activeSl <= stopLoss + EPS -> PaperExitReason.SL
                    beArmed && !trailArmed -> PaperExitReason.BE_SL
                    else -> PaperExitReason.TRAIL_SL
                }
                return done(totalR, j, reason, ambiguous, activeSl)
            }

            // --- TP2 full ---
            if (hi >= tp2) {
                val totalR = if (partialDone) partialR + 0.5 * rAt(tp2) else rAt(tp2)
                return done(totalR, j, PaperExitReason.TP2, price = tp2)
            }

            // --- Logika spesifik mode ---
            if (params.mode != ScalpExitMode.CLASSIC) {
                val netHi = netProfitPct(entry, hi, buyFeePct, sellFeePct, slippagePct)
                val netCl = netProfitPct(entry, cl, buyFeePct, sellFeePct, slippagePct)

                // TP1 partial 50% + geser SL ke BE
                if (params.mode.usesTp1Partial && !partialDone && hi >= tp1) {
                    partialDone = true
                    partialR = 0.5 * rAt(tp1)
                    activeSl = max(activeSl, bePx)
                    beArmed = true
                }

                // BE tanpa partial (mode trail / early / full)
                // S2/S8 — selain trigger net, wajib sudah melewati lantai profit kotor (S2);
                // trigger net itu sendiri sudah dinaikkan bila ATR tersedia (S8).
                if (params.mode.usesBreakeven && !beArmed && netHi >= beTriggerNetPct &&
                    grossAt(hi) >= floorGrossPct
                ) {
                    activeSl = max(activeSl, bePx)
                    beArmed = true
                }

                // Trailing dari peak
                if (params.mode.usesTrailing && netHi >= beTriggerNetPct &&
                    grossAt(hi) >= floorGrossPct
                ) {
                    trailArmed = true
                    val trailSl = peak * (1.0 - trailDistancePct / 100.0)
                    activeSl = max(activeSl, trailSl)
                }

                // Early profit: net di close >= min_early DAN momentum lemah DAN lantai profit (S2)
                if (params.mode.usesEarlyProfit && netCl >= params.minEarlyProfitNetPct &&
                    grossAt(cl) >= floorGrossPct
                ) {
                    val window = bars.subList(max(0, j - 14), j + 1)
                    if (isMomentumWeak(window)) {
                        val rem = rAt(cl)
                        val totalR = if (partialDone) partialR + 0.5 * rem else rem
                        return done(totalR, j, PaperExitReason.EARLY_PROFIT, price = cl)
                    }
                }
            }
        }

        return PaperExitDecision(
            PaperExitResult.UNRESOLVED, 0.0, null, false, PaperExitReason.TIMEOUT
        )
    }

    const val DEFAULT_LOOKAHEAD_BARS = 120
    private const val EPS = 1e-12

    private fun round4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
}

/**
 * Progres [PaperExitStateMachine] yang **dipersistenkan** supaya selamat dari restart app (P4.4).
 *
 * ## Kenapa ini ada
 *
 * `PaperAutoTradeRuntime` menyimpan mesin exit **hanya di memori**, sedangkan posisinya persisten
 * di `SpotPositionStore` dan jalur auto-sell lama sudah dimatikan saat engine mengambil alih exit.
 * Sebelum P4.4, restart membuat mesin lahir kembali dalam keadaan **nol** — `peak`, `activeStopLoss`,
 * status BE/trailing/partial, dan `barsHeld` semuanya hilang, padahal harganya sudah bergerak.
 * Akibatnya SL kembali ke titik awal, trailing yang sudah terkunci lepas, dan time-stop mulai
 * menghitung dari nol lagi.
 *
 * ## `atrPct` ikut disimpan, tapi bukan sebagai progres
 *
 * `atrPct` adalah **parameter konstruktor** mesin, dan nilai turunannya
 * (`effectiveBreakevenTriggerNetPct`, `effectiveTrailDistancePct`) adalah `val` yang dihitung saat
 * mesin dibuat. Karena itu `atrPct` **tidak** diterapkan oleh `restore()` — ia dibaca dari sini lalu
 * dipakai saat **membangun** mesin di `PaperRehydrate.decide()`. Sebelum P4.4, `atrPct` tidak pernah
 * dipulihkan sehingga ambang BE/trailing S8 diam-diam jatuh ke nilai persen tetap setelah restart.
 *
 * ## `entry`/`stopLoss` disimpan untuk verifikasi identitas
 *
 * Simbol bisa dipakai ulang untuk posisi yang berbeda. Membandingkan `entry`/`stopLoss` tersimpan
 * dengan posisi hidup adalah pertahanan terakhir supaya state milik posisi lama tidak diterapkan ke
 * posisi baru (lihat [PaperExitStateMachine.restoreRejection]).
 *
 * @param entry harga masuk posisi saat state ini dibuat — **acuan identitas**, bukan progres.
 * @param stopLoss SL awal posisi — **acuan identitas**, bukan progres.
 * @param activeStopLoss level SL yang sedang aktif (sudah naik bila BE/trailing ter-arm).
 * @param peak harga tertinggi yang pernah disentuh selama posisi terbuka.
 * @param partialR R yang sudah direalisasi dari TP1 partial (50%); `0.0` bila belum partial.
 * @param barsHeld jumlah bar M1 yang sudah dihitung untuk time-stop.
 * @param atrPct ATR% bar M1 saat entry; `<= 0` berarti tidak diketahui.
 */
data class PaperExitFsmState(
    val entry: Double,
    val stopLoss: Double,
    val activeStopLoss: Double,
    val peak: Double,
    val isBeArmed: Boolean,
    val isTrailArmed: Boolean,
    val isPartialDone: Boolean,
    val partialR: Double,
    val barsHeld: Int,
    val atrPct: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("entry", entry)
        put("sl", stopLoss)
        put("active_sl", activeStopLoss)
        put("peak", peak)
        put("be", isBeArmed)
        put("trail", isTrailArmed)
        put("partial_done", isPartialDone)
        put("partial_r", partialR)
        put("bars_held", barsHeld)
        put("atr_pct", atrPct)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        /** Versi skema JSON. Naikkan bila makna field berubah; versi lain ditolak ([fromJson] → null). */
        const val VERSION = 1

        /**
         * Baca state dari JSON tersimpan.
         *
         * @return `null` bila string kosong, JSON rusak, versi tak dikenal, atau ada field inti yang
         *   bukan angka. `null` diperlakukan pemanggil sebagai "tidak ada state" — mesin dibangun
         *   baru tanpa progres, bukan sebagai kesalahan fatal.
         */
        fun fromJson(raw: String?): PaperExitFsmState? {
            if (raw.isNullOrBlank()) return null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (json.optInt("v", 0) != VERSION) return null
            val entry = json.optDouble("entry", Double.NaN)
            val stopLoss = json.optDouble("sl", Double.NaN)
            val activeSl = json.optDouble("active_sl", Double.NaN)
            val peak = json.optDouble("peak", Double.NaN)
            val partialR = json.optDouble("partial_r", Double.NaN)
            if (!entry.isFinite() || !stopLoss.isFinite() || !activeSl.isFinite() ||
                !peak.isFinite() || !partialR.isFinite()
            ) {
                return null
            }
            val atrRaw = json.optDouble("atr_pct", 0.0)
            return PaperExitFsmState(
                entry = entry,
                stopLoss = stopLoss,
                activeStopLoss = activeSl,
                peak = peak,
                isBeArmed = json.optBoolean("be", false),
                isTrailArmed = json.optBoolean("trail", false),
                isPartialDone = json.optBoolean("partial_done", false),
                partialR = partialR,
                barsHeld = json.optInt("bars_held", 0),
                // ATR yang tidak masuk akal diperlakukan sebagai "tidak diketahui", bukan alasan
                // membuang seluruh progres — lihat ScalpExitParams.effectiveTrailDistancePct.
                atrPct = if (atrRaw.isFinite() && atrRaw > 0.0) atrRaw else 0.0
            )
        }
    }
}

/**
 * State machine exit paper trade untuk pemakaian **live** (tick/bar demi bar).
 *
 * Menyimpan state yang sama dengan loop di [PaperExitResolver.resolve] sehingga UI bisa
 * menampilkan fase FSM ([PaperExitPhase]) dan engine bisa memutuskan exit pada tick berjalan.
 */
class PaperExitStateMachine(
    val entry: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val buyFeePct: Double,
    val sellFeePct: Double,
    val slippagePct: Double,
    val params: ScalpExitParams,
    /**
     * S8 — ATR% bar M1 saat entry (mis. 3,31 untuk GNS). `<= 0` = tidak diketahui, dan
     * trigger BE / jarak trailing jatuh ke nilai persen tetap di [ScalpExitParams].
     */
    val atrPct: Double = 0.0
) {
    private val riskPct: Double = if (entry > 0.0) (entry - stopLoss) / entry * 100.0 else 0.0
    private val bePx: Double = PaperExitResolver.breakevenPrice(entry, buyFeePct, sellFeePct, slippagePct)

    /** S2 — lantai profit kotor (%). Exit profit sukarela tidak boleh di bawah ini. */
    private val floorGrossPct: Double =
        params.minProfitFloorGrossPct(buyFeePct, sellFeePct, slippagePct)

    /** S8 — trigger BE efektif (net %) setelah memperhitungkan ATR. */
    val effectiveBreakevenTriggerNetPct: Double = params.effectiveBreakevenTriggerNetPct(atrPct)

    /** S8 — jarak trailing efektif (% kotor dari peak) setelah memperhitungkan ATR. */
    val effectiveTrailDistancePct: Double = params.effectiveTrailDistancePct(atrPct)

    /** Profit kotor (%) pada harga tertentu — dasar perbandingan lantai profit (S2). */
    fun grossProfitPct(price: Double): Double =
        if (entry > 0.0 && price > 0.0) (price / entry - 1.0) * 100.0 else 0.0

    var activeStopLoss: Double = stopLoss
        private set
    var peak: Double = entry
        private set
    var isBeArmed: Boolean = false
        private set
    var isTrailArmed: Boolean = false
        private set
    var isPartialDone: Boolean = false
        private set
    var partialR: Double = 0.0
        private set
    var barsHeld: Int = 0
        private set

    val isRiskValid: Boolean get() = riskPct > 0.0 && entry > 0.0

    /** Harga impas net (profit net = 0) — dipakai UI untuk menggambar garis BE. */
    val netBreakevenPrice: Double get() = bePx

    /** Risiko awal (entry → SL) dalam persen. */
    val riskPctValue: Double get() = riskPct

    /**
     * Potret progres mesin untuk dipersistenkan (P4.4).
     *
     * `atrPct` disalin apa adanya, tetapi nilai yang tidak masuk akal ditulis `0.0` supaya
     * `fromJson` tidak perlu menebak.
     */
    fun snapshot(): PaperExitFsmState = PaperExitFsmState(
        entry = entry,
        stopLoss = stopLoss,
        activeStopLoss = activeStopLoss,
        peak = peak,
        isBeArmed = isBeArmed,
        isTrailArmed = isTrailArmed,
        isPartialDone = isPartialDone,
        partialR = partialR,
        barsHeld = barsHeld,
        atrPct = if (atrPct.isFinite() && atrPct > 0.0) atrPct else 0.0
    )

    /**
     * Periksa apakah [state] boleh diterapkan ke mesin ini.
     *
     * @return `null` bila boleh; selain itu alasan penolakan dalam Bahasa Indonesia, siap
     *   ditampilkan di log. Pemisahan dari [restore] disengaja supaya pemanggil bisa melaporkan
     *   **kenapa** progres dibuang, bukan sekadar bahwa ia dibuang.
     */
    fun restoreRejection(state: PaperExitFsmState): String? {
        // Identitas posisi — simbol bisa dipakai ulang, jadi state lama bisa milik posisi lain.
        if (!nearEnough(state.entry, entry)) return REJECT_IDENTITY
        if (!nearEnough(state.stopLoss, stopLoss)) return REJECT_IDENTITY
        if (!state.activeStopLoss.isFinite() || !state.peak.isFinite() || !state.partialR.isFinite()) {
            return REJECT_NOT_FINITE
        }
        // peak dimulai dari entry dan hanya naik; nilai di bawah entry mustahil.
        if (state.peak < entry) return REJECT_PEAK_BELOW_ENTRY
        // SL hanya pernah naik (TP1 partial / BE / trailing), tidak pernah turun.
        if (state.activeStopLoss < stopLoss) return REJECT_SL_BELOW_INITIAL
        if (state.barsHeld < 0) return REJECT_BARS_HELD_NEGATIVE
        // partialR hanya diisi saat partial terpicu, jadi tidak ada partialR tanpa penanda.
        if (!state.isPartialDone && state.partialR != 0.0) return REJECT_PARTIAL_R_WITHOUT_FLAG
        // Blok trailing mensyaratkan syarat yang sama dengan blok BE dan hanya ada di mode yang
        // juga memakai BE (ScalpExitMode.usesTrailing ⊆ usesBreakeven).
        if (state.isTrailArmed && !state.isBeArmed) return REJECT_TRAIL_WITHOUT_BE
        // Blok TP1 partial selalu men-set isBeArmed = true.
        if (state.isPartialDone && !state.isBeArmed) return REJECT_PARTIAL_WITHOUT_BE
        return null
    }

    /**
     * Pulihkan progres dari [state] hasil [snapshot].
     *
     * Seluruh field divalidasi lebih dulu; bila ada satu pelanggaran, **tidak ada** yang diterapkan
     * sehingga mesin tetap pada kondisi awalnya (bukan kondisi setengah jadi). `atrPct` sengaja tidak
     * dipulihkan di sini — ia parameter konstruktor, jadi pemanggil harus membangun mesin dengan
     * `atrPct` yang benar (lihat `PaperRehydrate.decide`).
     *
     * @return `true` bila state diterapkan, `false` bila ditolak.
     */
    fun restore(state: PaperExitFsmState): Boolean {
        if (restoreRejection(state) != null) return false
        activeStopLoss = state.activeStopLoss
        peak = state.peak
        isBeArmed = state.isBeArmed
        isTrailArmed = state.isTrailArmed
        isPartialDone = state.isPartialDone
        partialR = state.partialR
        barsHeld = state.barsHeld
        return true
    }

    /** Perbandingan harga dengan toleransi relatif — menoleransi pembulatan `toString()` prefs. */
    private fun nearEnough(a: Double, b: Double): Boolean =
        abs(a - b) <= max(1e-9, abs(b) * 1e-6)

    val phase: PaperExitPhase
        get() = when {
            isTrailArmed -> PaperExitPhase.TRAILING
            isPartialDone -> PaperExitPhase.TP1_PARTIAL
            isBeArmed -> PaperExitPhase.BREAKEVEN
            else -> PaperExitPhase.HOLDING
        }

    fun netProfitPct(price: Double): Double =
        PaperExitResolver.netProfitPct(entry, price, buyFeePct, sellFeePct, slippagePct)

    fun rAt(price: Double): Double =
        if (riskPct > 0.0) netProfitPct(price) / riskPct else 0.0

    /**
     * Proses satu bar tertutup. Kembalikan [PaperExitDecision] bila posisi harus ditutup,
     * atau `null` bila masih ditahan.
     *
     * @param window14 bar terakhir (maks 15) untuk uji momentum early profit.
     * @param isNewBar `false` saat evaluasi dilakukan di tengah bar berjalan (per-tick).
     *   Dalam kondisi ini pemeriksaan level (SL/TP/BE/trailing/early) tetap jalan — supaya
     *   proteksi tidak menunggu akhir bar — tetapi `barsHeld` tidak ditambah dan time-stop
     *   tidak dievaluasi, karena hitungan bar M1 hanya sah di batas bar.
     * @param countAsNewBar apakah bar ini **menambah** `barsHeld`. P8 — tick pertama sesudah
     *   rehidrasi dievaluasi sebagai bar baru (`isNewBar = true`, supaya time-stop langsung
     *   dicek) tetapi **tidak** menambah hitungan: `PaperRehydrate.mergedBarsHeld` sudah
     *   memasukkan menit yang berjalan sejak posisi dibuka. Default = [isNewBar] supaya
     *   pemanggil lama tidak berubah.
     */
    fun onBar(
        bar: CandleBar,
        window14: List<CandleBar>,
        isNewBar: Boolean = true,
        countAsNewBar: Boolean = isNewBar
    ): PaperExitDecision? {
        if (!isRiskValid) {
            return PaperExitDecision(
                PaperExitResult.UNRESOLVED, 0.0, null, false, PaperExitReason.BAD_RISK
            )
        }
        if (countAsNewBar) barsHeld++
        peak = max(peak, bar.high)

        if (bar.low <= activeStopLoss) {
            val ambiguous = bar.high >= tp2 && activeStopLoss <= stopLoss + 1e-12
            val rem = rAt(activeStopLoss)
            val totalR = if (isPartialDone) partialR + 0.5 * rem else rem
            val reason = when {
                activeStopLoss <= stopLoss + 1e-12 -> PaperExitReason.SL
                isBeArmed && !isTrailArmed -> PaperExitReason.BE_SL
                else -> PaperExitReason.TRAIL_SL
            }
            return close(totalR, reason, ambiguous, activeStopLoss)
        }

        if (bar.high >= tp2) {
            val totalR = if (isPartialDone) partialR + 0.5 * rAt(tp2) else rAt(tp2)
            return close(totalR, PaperExitReason.TP2, price = tp2)
        }

        if (params.mode != ScalpExitMode.CLASSIC) {
            val netHi = netProfitPct(bar.high)
            val netCl = netProfitPct(bar.close)

            if (params.mode.usesTp1Partial && !isPartialDone && bar.high >= tp1) {
                isPartialDone = true
                partialR = 0.5 * rAt(tp1)
                activeStopLoss = max(activeStopLoss, bePx)
                isBeArmed = true
            }

            if (params.mode.usesBreakeven && !isBeArmed && netHi >= effectiveBreakevenTriggerNetPct &&
                grossProfitPct(bar.high) >= floorGrossPct
            ) {
                activeStopLoss = max(activeStopLoss, bePx)
                isBeArmed = true
            }

            if (params.mode.usesTrailing && netHi >= effectiveBreakevenTriggerNetPct &&
                grossProfitPct(bar.high) >= floorGrossPct
            ) {
                isTrailArmed = true
                val trailSl = peak * (1.0 - effectiveTrailDistancePct / 100.0)
                activeStopLoss = max(activeStopLoss, trailSl)
            }

            // Early profit hanya dievaluasi bila data momentum benar-benar tersedia. Pada
            // jalur live di mana jendela M1 belum terbentuk, lebih baik menahan posisi
            // daripada keluar berdasarkan momentum yang tidak terukur.
            if (params.mode.usesEarlyProfit && window14.isNotEmpty() &&
                netCl >= params.minEarlyProfitNetPct && grossProfitPct(bar.close) >= floorGrossPct
            ) {
                if (PaperExitResolver.isMomentumWeak(window14)) {
                    val rem = rAt(bar.close)
                    val totalR = if (isPartialDone) partialR + 0.5 * rem else rem
                    return close(totalR, PaperExitReason.EARLY_PROFIT, price = bar.close)
                }
            }
        }

        if (isNewBar && params.maxHoldBarsM1 > 0 && barsHeld >= params.maxHoldBarsM1) {
            val totalR = if (isPartialDone) partialR + 0.5 * rAt(bar.close) else rAt(bar.close)
            return close(totalR, PaperExitReason.TIMEOUT, price = bar.close)
        }

        return null
    }

    private fun close(
        totalR: Double,
        reason: PaperExitReason,
        ambiguous: Boolean = false,
        price: Double = 0.0
    ) =
        PaperExitDecision(
            result = if (totalR > 0.0) PaperExitResult.WIN else PaperExitResult.LOSS,
            r = Math.round(totalR * 10_000.0) / 10_000.0,
            exitIndex = null,
            ambiguous = ambiguous,
            reason = reason,
            exitPrice = price
        )

    companion object {
        /** Alasan penolakan [restoreRejection] — dipakai untuk log, jadi harus terbaca manusia. */
        const val REJECT_IDENTITY = "state tersimpan milik posisi lain (entry/SL tidak cocok)"
        const val REJECT_NOT_FINITE = "state tersimpan memuat angka tidak valid (NaN/Infinity)"
        const val REJECT_PEAK_BELOW_ENTRY = "peak tersimpan di bawah harga entry"
        const val REJECT_SL_BELOW_INITIAL = "SL aktif tersimpan lebih longgar dari SL awal"
        const val REJECT_BARS_HELD_NEGATIVE = "jumlah bar tersimpan negatif"
        const val REJECT_PARTIAL_R_WITHOUT_FLAG = "partialR terisi tanpa penanda partial"
        const val REJECT_TRAIL_WITHOUT_BE = "trailing ter-arm tanpa breakeven ter-arm"
        const val REJECT_PARTIAL_WITHOUT_BE = "partial ter-arm tanpa breakeven ter-arm"
    }
}
