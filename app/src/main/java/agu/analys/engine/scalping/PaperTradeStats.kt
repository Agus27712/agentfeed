package agu.analys.engine.scalping

/**
 * Ringkasan performa paper trade (P2) — akumulasi **R net setelah fee all-in**.
 *
 * Menutup dua keterbatasan yang didokumentasikan di `AGENT_BRIDGE.md`:
 * 1. Trade `TIMEOUT` **tidak** dibuang diam-diam — dihitung terpisah lewat [timeoutCount]
 *    dan tetap dilaporkan di [byExitReason].
 * 2. Exit breakeven (r ≈ 0) tidak lagi dipaksa jadi LOSS; diklasifikasi [PaperTradeOutcome.BREAKEVEN].
 */
data class PaperTradeStats(
    /** Semua trade yang dievaluasi, termasuk yang belum selesai. */
    val totalTrades: Int,
    /** Trade yang benar-benar keluar (SL / BE_SL / TRAIL_SL / TP2 / EARLY_PROFIT). */
    val resolvedTrades: Int,
    val wins: Int,
    val losses: Int,
    val breakevens: Int,
    val timeouts: Int,
    /** Jumlah R bersih terakumulasi (hanya trade resolved). */
    val totalR: Double,
    /** Rata-rata R per trade resolved. */
    val avgR: Double,
    /** Gross profit / gross loss. `null` bila tidak ada trade rugi. */
    val profitFactor: Double?,
    /** Rata-rata R trade menang. */
    val avgWinR: Double,
    /** Rata-rata R trade kalah. */
    val avgLossR: Double,
    val byExitReason: Map<PaperExitReason, Int>
) {
    /** Win rate dihitung dari trade resolved; BE tidak dihitung sebagai kalah. */
    val winRate: Double
        get() = if (resolvedTrades > 0) wins.toDouble() / resolvedTrades * 100.0 else 0.0

    val hasData: Boolean get() = totalTrades > 0

    companion object {
        val EMPTY = PaperTradeStats(
            totalTrades = 0, resolvedTrades = 0, wins = 0, losses = 0, breakevens = 0,
            timeouts = 0, totalR = 0.0, avgR = 0.0, profitFactor = null,
            avgWinR = 0.0, avgLossR = 0.0, byExitReason = emptyMap()
        )

        /** Ambang |R| di bawah ini dianggap breakeven (setelah pembulatan 4 desimal replay). */
        const val BREAKEVEN_EPSILON = 1e-4

        fun compute(decisions: List<PaperExitDecision>): PaperTradeStats {
            if (decisions.isEmpty()) return EMPTY

            val byReason = linkedMapOf<PaperExitReason, Int>()
            var wins = 0
            var losses = 0
            var breakevens = 0
            var timeouts = 0
            var resolved = 0
            var totalR = 0.0
            var grossWin = 0.0
            var grossLoss = 0.0
            var winSum = 0.0
            var lossSum = 0.0

            for (d in decisions) {
                byReason[d.reason] = (byReason[d.reason] ?: 0) + 1

                if (!d.isResolved) {
                    // TIMEOUT / bad_risk: dihitung terpisah, tidak masuk statistik R
                    timeouts++
                    continue
                }

                resolved++
                totalR += d.r
                when {
                    d.r > BREAKEVEN_EPSILON -> {
                        wins++; winSum += d.r; grossWin += d.r
                    }
                    d.r < -BREAKEVEN_EPSILON -> {
                        losses++; lossSum += d.r; grossLoss += -d.r
                    }
                    else -> breakevens++
                }
            }

            val pf = if (grossLoss > 0.0) grossWin / grossLoss else null

            return PaperTradeStats(
                totalTrades = decisions.size,
                resolvedTrades = resolved,
                wins = wins,
                losses = losses,
                breakevens = breakevens,
                timeouts = timeouts,
                totalR = round4(totalR),
                avgR = if (resolved > 0) round4(totalR / resolved) else 0.0,
                profitFactor = pf?.let { round4(it) },
                avgWinR = if (wins > 0) round4(winSum / wins) else 0.0,
                avgLossR = if (losses > 0) round4(lossSum / losses) else 0.0,
                byExitReason = byReason
            )
        }

        /**
         * Hitung statistik dari baris jurnal.
         *
         * Nama berbeda dari `compute(List<PaperExitDecision>)` karena kedua parameter
         * terhapus ke `List` di JVM (platform declaration clash).
         */
        fun computeFromRecords(records: List<PaperTradeRecord>): PaperTradeStats =
            compute(records.map { it.toDecision() })

        private fun round4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
    }
}

/** Klasifikasi hasil per trade untuk tampilan jurnal. */
enum class PaperTradeOutcome(val label: String) {
    WIN("Menang"),
    LOSS("Kalah"),
    BREAKEVEN("Breakeven"),
    UNRESOLVED("Belum selesai");

    companion object {
        fun of(decision: PaperExitDecision): PaperTradeOutcome = when {
            !decision.isResolved -> UNRESOLVED
            decision.r > PaperTradeStats.BREAKEVEN_EPSILON -> WIN
            decision.r < -PaperTradeStats.BREAKEVEN_EPSILON -> LOSS
            else -> BREAKEVEN
        }
    }
}
