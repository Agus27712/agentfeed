package agu.analys.service

import kotlin.math.min

/**
 * Kebijakan backoff sambung-ulang untuk soket realtime (**K8**).
 *
 * Cacat lama: hitungan percobaan di-reset di `onOpen` dan jeda tidak berjitter serta tanpa batas.
 * Server yang menerima lalu langsung menutup koneksi (mis. token ditolak) membuat siklus
 * buka → tutup → tunggu 800 ms → buka lagi tanpa henti, membakar baterai dan memenuhi log.
 *
 * Tiga aturan di sini:
 *  1. hitungan dianggap "berhasil" hanya bila sesi **stabil** ([STABLE_AFTER_MS]); reset terjadi
 *     saat frame datang setelah sesi stabil, bukan saat `onOpen`;
 *  2. jeda diberi jitter ±[JITTER_FRACTION] supaya beberapa klien tidak menghantam bersamaan;
 *  3. setelah [GIVE_UP_AFTER_ATTEMPTS] percobaan berturut-turut, jeda panjang
 *     ([GIVE_UP_PAUSE_MS]) dipakai sebelum siklus percobaan dimulai lagi.
 *
 * `random01` dioper pemanggil supaya keputusan ini murni dan bisa diuji tanpa keacakan.
 */
object ReconnectBackoff {

    /** Sesi dianggap stabil (percobaan di-reset) bila bertahan selama ini. */
    const val STABLE_AFTER_MS = 60_000L

    /** Jumlah percobaan berturut-turut sebelum jeda panjang dipakai. */
    const val GIVE_UP_AFTER_ATTEMPTS = 6

    /** Jeda panjang sebelum siklus percobaan dimulai lagi. */
    const val GIVE_UP_PAUSE_MS = 5 * 60_000L

    /** Batas jitter relatif; 0,2 berarti ±20%. */
    const val JITTER_FRACTION = 0.2

    /** True bila sesi sudah bertahan cukup lama untuk menganggap koneksi berhasil. */
    fun isStable(connectedAtMs: Long, nowMs: Long): Boolean =
        connectedAtMs > 0L && nowMs - connectedAtMs >= STABLE_AFTER_MS

    /** True bila percobaan ke-[attempt] sudah menembus batas sehingga jeda panjang dipakai. */
    fun shouldGiveUp(attempt: Int): Boolean = attempt >= GIVE_UP_AFTER_ATTEMPTS

    /**
     * Jeda sebelum percobaan ke-[attempt] (1-based), dengan jitter ±[JITTER_FRACTION].
     *
     * @param random01 nilai di [0,1); 0,5 berarti tanpa jitter. Nilai di luar rentang dijepit.
     */
    fun delayMs(attempt: Int, baseMs: Long, maxMs: Long, random01: Double): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        val exponent = min(safeAttempt - 1, 5)
        val raw = (baseMs * (1L shl exponent)).coerceAtMost(maxMs)
        val r = random01.coerceIn(0.0, 1.0)
        val factor = 1.0 + JITTER_FRACTION * (2.0 * r - 1.0)
        return (raw * factor).toLong().coerceAtLeast(1L)
    }
}
