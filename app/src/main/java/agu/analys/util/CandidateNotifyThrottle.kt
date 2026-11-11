package agu.analys.util

/**
 * N12 — cooldown **per pair** untuk notifikasi kandidat BUY.
 *
 * Notifikasi kandidat dipicu transisi `READY` (edge-trigger), dan itu sudah menangkal sebagian
 * pengulangan. Masalahnya state bisa **flapping** `READY → INVALIDATED → READY` pada pasar yang
 * berombak; setiap siklus memenuhi syarat edge dan mengirim notifikasi lagi. Untuk scalping M5 itu
 * berarti bel berulang untuk setup yang praktis sama.
 *
 * Pembanding yang sudah ada: peak trailing 30 detik, emergency 60 detik. Kandidat sengaja lebih
 * panjang — satu setup butuh beberapa candle untuk layak dinilai ulang, dan notifikasi "entry siap"
 * lebih mengganggu daripada pembaruan trailing.
 *
 * Jendela 5 menit dipilih dengan alasan yang bisa diperiksa:
 * - worker latar memindai tiap **15 menit**, jadi cooldown ini tidak pernah menelan dua siklus
 *   pemindaian yang berbeda;
 * - sinyal scalping berbasis M5 — 5 menit = satu candle penuh, cukup untuk membedakan "setup yang
 *   sama berulang" dari "setup baru".
 *
 * Kalau nanti angka ini terasa terlalu ketat/longgar, ubah [WINDOW_MS] — satu tempat, teruji.
 */
object CandidateNotifyThrottle {

    /** Jendela minimum antar notifikasi kandidat untuk satu pair. */
    const val WINDOW_MS = 5 * 60_000L

    /**
     * @param lastNotifiedAt waktu kirim terakhir untuk pair ini (ms epoch); `0L` = belum pernah.
     * @param now waktu sekarang (ms epoch).
     */
    fun shouldNotify(
        lastNotifiedAt: Long,
        now: Long,
        windowMs: Long = WINDOW_MS
    ): Boolean = now - lastNotifiedAt > windowMs
}

/**
 * N12 — penjaga cooldown **berstatus** (satu instance per proses).
 *
 * Dipisah dari [CandidateNotifyThrottle] yang murni supaya mekanismenya bisa diuji tanpa Android:
 * notifikasi dengan ID yang sama hanya **menimpa** entri di shade, jadi "berapa notifikasi tampil"
 * tidak bisa membedakan satu kiriman dari dua. Yang bisa diuji adalah keputusan `allow(...)`.
 */
class CandidateNotifyCooldown(
    private val windowMs: Long = CandidateNotifyThrottle.WINDOW_MS
) {

    private val lastNotifiedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * @param key identitas pair, mis. `TOKOCRYPTO_BTCIDR`.
     * @return `true` bila notifikasi boleh dikirim; sekaligus mencatat waktunya.
     */
    fun allow(key: String, now: Long): Boolean {
        val last = lastNotifiedAt[key] ?: 0L
        if (!CandidateNotifyThrottle.shouldNotify(last, now, windowMs)) return false
        lastNotifiedAt[key] = now
        return true
    }

    fun clear() {
        lastNotifiedAt.clear()
    }
}
