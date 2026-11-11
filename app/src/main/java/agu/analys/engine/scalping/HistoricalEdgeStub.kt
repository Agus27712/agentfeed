package agu.analys.engine.scalping

import agu.analys.model.MarketRegime
import agu.analys.model.ScalpSetupType

/**
 * Historical Edge (P2) — **stub sadar data**.
 *
 * Spek KriptoYoi: jangan menampilkan probabilitas yang tidak berasal dari data.
 * Hingga backtest / journal historis nyata tersedia, selalu kembalikan status
 * INSUFFICIENT dengan pesan [ScalpingConfig.HISTORICAL_EDGE_INSUFFICIENT].
 *
 * Jangan hardcode win-rate (mis. "63.4%") di UI lewat stub ini.
 */
object HistoricalEdgeStub {

    enum class Status {
        /** Belum ada sample historis yang cukup / engine belum di-wire ke storage. */
        INSUFFICIENT,
        /** Cadangan untuk Phase 5+ ketika sample valid tersedia. */
        AVAILABLE
    }

    data class Query(
        val setup: ScalpSetupType = ScalpSetupType.NONE,
        val regime: MarketRegime = MarketRegime.RANGING,
        val minRvol: Double = ScalpingConfig.RVOL_SETUP_MIN,
        val minNetRr: Double = ScalpingConfig.MIN_NET_RR,
        val symbol: String = "",
        val timeframe: String = ""
    )

    data class Result(
        val status: Status,
        val message: String,
        /** Selalu null pada stub — isi hanya jika status AVAILABLE + data nyata. */
        val occurrences: Int? = null,
        val tpFirstPct: Double? = null,
        val slFirstPct: Double? = null,
        val averageReturnPct: Double? = null
    ) {
        val isDisplayable: Boolean
            get() = status == Status.AVAILABLE &&
                occurrences != null &&
                occurrences >= 1 &&
                tpFirstPct != null

        /** Teks aman untuk UI / reasoning (Bahasa Indonesia). */
        fun displayText(): String = when (status) {
            Status.INSUFFICIENT -> message
            Status.AVAILABLE -> buildString {
                append("Historical Edge: ")
                append("${occurrences ?: 0} setup serupa")
                tpFirstPct?.let { append(", TP first ${fmt(it)}%") }
                slFirstPct?.let { append(", SL first ${fmt(it)}%") }
                averageReturnPct?.let { append(", avg return ${fmt(it)}%") }
            }
        }

        private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
    }

    /**
     * Query edge historis. Implementasi saat ini **selalu** INSUFFICIENT.
     * Parameter disimpan agar API stabil saat diganti engine berbasis journal/backtest.
     */
    @Suppress("UNUSED_PARAMETER")
    fun query(query: Query = Query()): Result {
        return Result(
            status = Status.INSUFFICIENT,
            message = ScalpingConfig.HISTORICAL_EDGE_INSUFFICIENT,
            occurrences = null,
            tpFirstPct = null,
            slFirstPct = null,
            averageReturnPct = null
        )
    }

    /** Convenience untuk pipeline evaluator. */
    fun stubMessage(
        setup: ScalpSetupType = ScalpSetupType.NONE,
        regime: MarketRegime = MarketRegime.RANGING
    ): String = query(Query(setup = setup, regime = regime)).displayText()
}
