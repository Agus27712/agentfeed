package agu.analys.engine.intraday

import agu.analys.model.CandleBar

/**
 * Mode keluar untuk posisi **INTRADAY** (`StrategyMode.OFFICE_DAILY`) — paket E.
 *
 * Sebelum paket ini, mode intraday hanya punya satu exit: SELL paksa di sesi Close Malam
 * (19:30–23:30 WIB) dan TP1/TP2/SL statis. Mesin exit scalping (`PaperExitStateMachine`) tidak
 * boleh dipakai: ia produk scalping dan digerbang `StrategyMode.SCALPING`.
 *
 * Mesin ini **murni** (tanpa Android, tanpa state global) supaya replay dan app memakai aturan
 * yang sama. Ia **belum disambungkan** ke posisi paper/app: aturan area `app/AGENTS.md` melarang
 * menyambungkan exit baru tanpa pengukuran tersendiri. Pengukurannya ada di
 * `IntradayExitModeSweepTest` (env `RUN_INTRADAY_EXIT_SWEEP=true`), hasilnya di
 * `AUDIT-2026-11-08.md`.
 *
 * @property mode aturan keluar yang dipakai.
 * @property timeStopBars batas tahan dalam **jumlah bar** (bukan jam) untuk [IntradayExitMode.TIME_STOP].
 * @property trailAtrMult jarak trailing dari puncak = `trailAtrMult × ATR` untuk
 *   [IntradayExitMode.TRAIL_ATR].
 * @property armTrailAfterTp1 `true` = trailing baru aktif setelah TP1 tersentuh; `false` = aktif
 *   sejak bar pertama.
 */
data class IntradayExitConfig(
    val mode: IntradayExitMode = IntradayExitMode.SESSION_CLOSE,
    val timeStopBars: Int = 6,
    val trailAtrMult: Double = 1.0,
    val armTrailAfterTp1: Boolean = true
) {
    init {
        require(timeStopBars >= 1) { "timeStopBars harus >= 1" }
        require(trailAtrMult > 0.0 && trailAtrMult.isFinite()) { "trailAtrMult harus > 0" }
    }

    companion object {
        /** Bawaan = perilaku lama (hanya Close Malam + SL/TP statis). */
        val DEFAULT = IntradayExitConfig()
    }
}

enum class IntradayExitMode {
    /** Perilaku lama: SL, TP1/TP2, dan SELL paksa di sesi Close Malam. */
    SESSION_CLOSE,

    /**
     * TP1 tidak menutup posisi penuh; saat TP1 tersentuh stop dinaikkan ke **harga impas net**
     * (fee beli + fee jual + slippage dua sisi) lalu posisi ditahan menuju TP2.
     * SL/TP2/sesi tetap berlaku.
     */
    TP1_BREAKEVEN,

    /**
     * Setelah TP1 (atau sejak awal bila [IntradayExitConfig.armTrailAfterTp1] = false), stop
     * mengikuti puncak: `puncak − trailAtrMult × ATR`. Stop hanya pernah **naik**.
     */
    TRAIL_ATR,

    /** Keluar di close bar ke-`timeStopBars` bila SL/TP belum tersentuh. Sesi tetap berlaku. */
    TIME_STOP
}

/** Parameter posisi yang tidak berubah selama trade (dihitung sekali saat entry). */
data class IntradayExitPosition(
    val entryPrice: Double,
    val initialStopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    /** Harga yang menutup biaya round-trip (fee + slippage dua sisi). */
    val breakevenPrice: Double,
    /** ATR saat entry — dasar jarak trailing. */
    val atr: Double
)

/** State yang berubah per bar. */
data class IntradayExitState(
    val barsHeld: Int = 0,
    val stopLoss: Double,
    val peakPrice: Double,
    val tp1Hit: Boolean = false
)

/** Keputusan satu bar: tahan (dengan state baru) atau keluar. */
sealed interface IntradayExitDecision {
    data class Hold(val state: IntradayExitState) : IntradayExitDecision

    data class Exit(
        val price: Double,
        val reason: String,
        /** `true` bila SL dan TP tersentuh di bar yang sama (dinilai konservatif: SL dulu). */
        val ambiguousSameBar: Boolean = false
    ) : IntradayExitDecision
}

/**
 * Aturan keluar intraday — satu definisi untuk replay dan (nanti) app.
 *
 * Urutan pemeriksaan per bar, sama dengan `IntradayReplayEngine` sebelum paket E:
 * 1. SL dan TP tersentuh bersamaan → **SL** (konservatif), ditandai `ambiguousSameBar`;
 * 2. TP2 → keluar di TP2;
 * 3. TP1 → keluar di TP1 (mode [IntradayExitMode.SESSION_CLOSE]/[IntradayExitMode.TIME_STOP]),
 *    atau stop dinaikkan ke impas/trailing lalu posisi ditahan (mode TP1_BREAKEVEN/TRAIL_ATR);
 * 4. SL → keluar di stop;
 * 5. time-stop (bila mode-nya);
 * 6. sesi Close Malam → keluar di close bar;
 * 7. selain itu tahan, stop trailing diperbarui (hanya boleh naik).
 */
object IntradayExitEngine {

    fun initialState(position: IntradayExitPosition): IntradayExitState = IntradayExitState(
        barsHeld = 0,
        stopLoss = position.initialStopLoss,
        peakPrice = position.entryPrice,
        tp1Hit = false
    )

    fun evaluate(
        bar: CandleBar,
        position: IntradayExitPosition,
        state: IntradayExitState,
        config: IntradayExitConfig,
        sessionClose: Boolean
    ): IntradayExitDecision {
        val barsHeld = state.barsHeld + 1
        val peak = maxOf(state.peakPrice, bar.high)
        var stop = state.stopLoss
        var tp1Hit = state.tp1Hit

        val hitTp1 = bar.high >= position.takeProfit1
        val hitTp2 = bar.high >= position.takeProfit2
        val hitStop = bar.low <= stop

        // 1. Bar yang sama menyentuh SL dan TP → konservatif SL (perilaku lama).
        if (hitTp1 && hitStop) {
            return IntradayExitDecision.Exit(
                price = stop,
                reason = "AMBIGUOUS_SAME_BAR",
                ambiguousSameBar = true
            )
        }

        // 2. TP2 selalu keluar penuh.
        if (hitTp2) {
            return IntradayExitDecision.Exit(price = position.takeProfit2, reason = "TP2_HIT")
        }

        // 3. TP1: keluar penuh, atau kunci impas/trailing lalu tahan.
        if (hitTp1) {
            val menahanSetelahTp1 = config.mode == IntradayExitMode.TP1_BREAKEVEN ||
                config.mode == IntradayExitMode.TRAIL_ATR
            if (!menahanSetelahTp1) {
                return IntradayExitDecision.Exit(price = position.takeProfit1, reason = "TP1_HIT")
            }
            tp1Hit = true
            stop = maxOf(stop, position.breakevenPrice)
        }

        // 4. Stop loss. Stop yang sudah dinaikkan (impas/trailing) dilaporkan terpisah supaya
        //    laporan tidak menyebutnya "SL awal kena" — arahnya bisa untung.
        if (hitStop) {
            val dinaikkan = stop > position.initialStopLoss + 1e-12
            return IntradayExitDecision.Exit(
                price = stop,
                reason = if (dinaikkan) "TRAIL_STOP_HIT" else "STOP_LOSS_HIT"
            )
        }

        // 5. Time-stop dalam jumlah bar.
        if (config.mode == IntradayExitMode.TIME_STOP && barsHeld >= config.timeStopBars) {
            return IntradayExitDecision.Exit(price = bar.close, reason = "TIME_STOP")
        }

        // 6. Sesi Close Malam menutup posisi.
        if (sessionClose) {
            return IntradayExitDecision.Exit(price = bar.close, reason = "CLOSE_MALAM_SESSION_EXIT")
        }

        // 7. Stop trailing: hanya pernah naik, tidak pernah turun.
        if (config.mode == IntradayExitMode.TRAIL_ATR && (!config.armTrailAfterTp1 || tp1Hit)) {
            val trail = peak - config.trailAtrMult * position.atr
            if (trail > stop) stop = trail
        }

        return IntradayExitDecision.Hold(
            state = IntradayExitState(
                barsHeld = barsHeld,
                stopLoss = stop,
                peakPrice = peak,
                tp1Hit = tp1Hit
            )
        )
    }

    /**
     * Harga impas net dari tarif yang benar-benar dibayar. Taker adalah bawaan (order market),
     * sama dengan basis FSM dan `FeeSchedule.netProfitPct`.
     */
    fun breakevenPrice(
        entry: Double,
        fees: agu.analys.config.TradingFeeConfig,
        useMaker: Boolean,
        slippagePct: Double
    ): Double = agu.analys.config.FeeSchedule.breakevenPrice(
        entry = entry,
        buyFeePct = if (useMaker) fees.buyMakerPct else fees.buyTakerPct,
        sellFeePct = if (useMaker) fees.sellMakerPct else fees.sellTakerPct,
        slippagePct = slippagePct
    )
}
