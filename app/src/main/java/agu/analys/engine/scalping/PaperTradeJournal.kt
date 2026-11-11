package agu.analys.engine.scalping

import org.json.JSONArray
import org.json.JSONObject

/**
 * Satu baris jurnal paper trade (P2) — hasil akhir satu posisi simulasi.
 *
 * Disimpan agar kartu statistik di layar paper trade bisa mengakumulasi performa lintas
 * sesi, bukan hanya selama app hidup. Semua angka memakai satuan R net setelah fee all-in,
 * sehingga bisa dibandingkan langsung dengan `AUDIT-2026-11-08.md`.
 */
data class PaperTradeRecord(
    val id: Long,
    val symbol: String,
    val exchange: String,
    val mode: ScalpExitMode,
    val reason: PaperExitReason,
    val result: PaperExitResult,
    val entry: Double,
    val exitPrice: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    /** R net terealisasi (setelah buy fee + sell fee + 2x slippage). */
    val r: Double,
    val ambiguous: Boolean,
    val barsHeld: Int,
    val openedAt: Long,
    val closedAt: Long
) {
    val isResolved: Boolean get() = reason in PaperExitReason.RESOLVED

    val outcome: PaperTradeOutcome
        get() = PaperTradeOutcome.of(
            PaperExitDecision(result, r, null, ambiguous, reason, exitPrice)
        )

    /** Konversi ke [PaperExitDecision] agar bisa dipakai ulang oleh [PaperTradeStats]. */
    fun toDecision(): PaperExitDecision = PaperExitDecision(
        result = result,
        r = r,
        exitIndex = null,
        ambiguous = ambiguous,
        reason = reason,
        exitPrice = exitPrice
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("symbol", symbol)
        put("exchange", exchange)
        put("mode", mode.key)
        put("reason", reason.key)
        put("result", result.key)
        put("entry", entry)
        put("exit_price", exitPrice)
        put("sl", stopLoss)
        put("tp1", tp1)
        put("tp2", tp2)
        put("r", r)
        put("ambiguous", ambiguous)
        put("bars_held", barsHeld)
        put("opened_at", openedAt)
        put("closed_at", closedAt)
    }

    companion object {
        /** Batas jumlah baris yang disimpan; baris terlama dibuang lebih dulu. */
        const val MAX_RECORDS = 500

        fun fromJson(json: JSONObject): PaperTradeRecord? {
            val symbol = json.optString("symbol", "")
            if (symbol.isBlank()) return null
            return PaperTradeRecord(
                id = json.optLong("id", 0L),
                symbol = symbol,
                exchange = json.optString("exchange", ""),
                mode = ScalpExitMode.fromKey(json.optString("mode", ScalpExitMode.CLASSIC.key)),
                reason = PaperExitReason.fromKey(json.optString("reason", PaperExitReason.TIMEOUT.key)),
                result = PaperExitResult.fromKey(json.optString("result", PaperExitResult.UNRESOLVED.key)),
                entry = json.optDouble("entry", 0.0),
                exitPrice = json.optDouble("exit_price", 0.0),
                stopLoss = json.optDouble("sl", 0.0),
                tp1 = json.optDouble("tp1", 0.0),
                tp2 = json.optDouble("tp2", 0.0),
                r = json.optDouble("r", 0.0),
                ambiguous = json.optBoolean("ambiguous", false),
                barsHeld = json.optInt("bars_held", 0),
                openedAt = json.optLong("opened_at", 0L),
                closedAt = json.optLong("closed_at", 0L)
            )
        }

        fun listToJson(records: List<PaperTradeRecord>): String {
            val arr = JSONArray()
            for (r in records) arr.put(r.toJson())
            return arr.toString()
        }

        fun listFromJson(raw: String?): List<PaperTradeRecord> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { fromJson(it) }
                }
            }.getOrDefault(emptyList())
        }
    }
}
