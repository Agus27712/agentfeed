package agu.analys.database

/**
 * Hasil akhir sebuah log sinyal.
 *
 * **Eksklusif** — satu log hanya boleh masuk tepat satu keranjang. Itu bukan detail gaya:
 * sebelum perbaikan J2 (2026-10-16) `SignalLogRepository` punya **tiga** definisi menang/kalah,
 * dan dua di antaranya memakai dua filter yang saling independen:
 *
 * ```kotlin
 * val wins   = resolved.filter { status in WIN_STATUSES || pnl > 0 }   // filter A
 * val losses = resolved.filter { status in LOSS_STATUSES || pnl < 0 }  // filter B — bebas dari A
 * ```
 *
 * Sebuah log berstatus `HIT_SL` yang P&L-nya positif (mungkin terjadi ketika `entryPrice` lama
 * dipasangkan dengan `stopLoss` baru — akar yang sama dengan [hasEntryDrifted]) masuk **kedua**
 * keranjang. Akibatnya `winCount + lossCount > resolvedCount` dan win-rate bisa melewati 100%.
 */
enum class SignalOutcome { WIN, LOSS, NEUTRAL }

/** Status yang menutup sinyal sebagai menang, berapa pun besaran P&L-nya. */
private val WIN_STATUSES = setOf("HIT_TP1", "HIT_TP2", "MANUAL_WIN")

/** Status yang menutup sinyal sebagai kalah, berapa pun besaran P&L-nya. */
private val LOSS_STATUSES = setOf("HIT_SL", "MANUAL_LOSS")

/**
 * Satu-satunya penentu menang/kalah di seluruh aplikasi.
 *
 * Urutan prioritas: **status menang → status kalah → tanda P&L**.
 *
 * Status didahulukan atas P&L karena status adalah peristiwa yang benar-benar terjadi
 * ("stop loss tersentuh"), sedangkan besaran P&L bisa menyesatkan bila datanya warisan lama.
 * Konsekuensinya, `HIT_SL` dengan P&L positif dihitung **kalah** — sesuai kenyataan bahwa
 * posisinya ditutup karena stop loss.
 *
 * [SignalOutcome.NEUTRAL] dipakai untuk status yang tidak dikenal dengan P&L nol/absen —
 * keranjang yang sama dengan perilaku lama (dulu log seperti itu memang tidak masuk
 * keranjang mana pun), jadi tidak ada perubahan hitungan untuk kasus itu.
 */
fun classifySignalOutcome(status: String, realizedPnlPct: Double?): SignalOutcome = when {
    status in WIN_STATUSES -> SignalOutcome.WIN
    status in LOSS_STATUSES -> SignalOutcome.LOSS
    else -> {
        val pnl = realizedPnlPct ?: 0.0
        when {
            pnl > 0.0 -> SignalOutcome.WIN
            pnl < 0.0 -> SignalOutcome.LOSS
            else -> SignalOutcome.NEUTRAL
        }
    }
}

/** [classifySignalOutcome] untuk sebuah log. Tidak menghitung log `TRACKING`. */
fun SignalLogEntity.classifyOutcome(): SignalOutcome =
    classifySignalOutcome(outcomeStatus, realizedPnlPct)
