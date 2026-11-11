package agu.analys.engine.scalping

import agu.analys.model.AISignalState

/**
 * P0 — umur sinyal scalping dalam candle M1 dan identitas setup untuk anti re-arm.
 *
 * **Kenapa bukan hitungan tick.** Tick bisa datang beberapa kali per detik saat pasar ramai,
 * atau tertinggal saat app tidur. p0 menghitung `elapsed_sec // 60` lalu membandingkan dengan
 * jumlah candle, jadi umur sinyal harus berbasis jam dinding. Pola ini sama dengan
 * `PaperRehydrate.mergedBarsHeld`.
 *
 * **Mengapa `EXPIRED` harus terminal.** Sebelum P0, `SignalLifecycleManager` menulis `EXPIRED`
 * lalu **pada pemanggilan yang sama** masuk cabang `IDLE/EXPIRED/INVALIDATED` dan bisa langsung
 * menaikkan setup yang sama ke `DETECTED` lagi. Akibatnya batas waktu tidak pernah benar-benar
 * menghentikan setup yang sudah basi: state hanya berkedip `EXPIRED` satu tick. Identitas setup
 * di sini dipakai sebagai kunci: selama setup + arah sama, siklus tetap `EXPIRED`; setup baru
 * (atau setup yang sempat hilang) boleh memulai episode baru.
 */
object SignalTtl {

    /** Satu candle M1 = 60 detik. */
    const val M1_BAR_MS = 60_000L

    data class Decision(
        val elapsedBars: Long,
        val maxBars: Int,
        val remainingBars: Long,
        val expired: Boolean
    )

    /**
     * @param detectedAtMs waktu setup pertama terdeteksi (0 = belum pernah terdeteksi).
     * @param nowMs waktu evaluasi.
     * @param maxBars batas umur dari config (`ttl_bars_m1`). Nilai <= 0 diperlakukan 1.
     */
    fun evaluate(detectedAtMs: Long, nowMs: Long, maxBars: Int): Decision {
        val limit = maxBars.coerceAtLeast(1)
        if (detectedAtMs <= 0L || nowMs <= 0L) {
            return Decision(elapsedBars = 0L, maxBars = limit, remainingBars = limit.toLong(), expired = false)
        }
        val elapsedMs = (nowMs - detectedAtMs).coerceAtLeast(0L)
        val elapsedBars = elapsedMs / M1_BAR_MS
        return Decision(
            elapsedBars = elapsedBars,
            maxBars = limit,
            remainingBars = (limit - elapsedBars).coerceAtLeast(0L),
            expired = elapsedBars >= limit
        )
    }

    /**
     * Identitas episode setup. Hanya pola + arah: level entry/TP/SL ikut bergerak setiap tick,
     * sehingga memasukkannya ke kunci akan membuat setup yang sama terlihat "baru" terus dan
     * anti re-arm tidak pernah berlaku.
     */
    fun setupKey(signal: AISignalState): String {
        val setup = signal.scalpingSetup.ifBlank { "NONE" }
        val direction = signal.scalpingDirection.ifBlank { "WAIT" }
        return "$setup|$direction"
    }

    /** Setup yang benar-benar ada; `NONE`/kosong tidak boleh dianggap episode. */
    fun hasSetup(signal: AISignalState): Boolean {
        val setup = signal.scalpingSetup
        return setup.isNotBlank() && !setup.equals("NONE", ignoreCase = true)
    }
}
