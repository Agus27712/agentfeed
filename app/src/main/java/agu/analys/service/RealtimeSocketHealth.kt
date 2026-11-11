package agu.analys.service

/**
 * Keputusan murni: kapan sebuah socket realtime boleh disambung ulang.
 *
 * Latar belakang (**K7**): pengecekan lama hanya melihat frame data. Untuk pair yang sepi,
 * 30 detik tanpa frame berarti socket yang sebenarnya sehat diputus, lalu disambung lagi —
 * berulang terus, dan histori candle menit berjalan ikut hilang.
 *
 * Padahal kematian socket sudah ditangani di lapisan transport: `NetworkClientProvider` memasang
 * `pingInterval` 15 detik pada `webSocketClient`, jadi OkHttp mengirim ping dan memfailkan
 * koneksi bila pong tidak datang (`onFailure` → reconnect). Pengecekan di sini karena itu
 * **bukan** pendeteksi kematian utama, melainkan jaring pengaman untuk dua keadaan:
 *
 *  1. **Zombie** — socket terbuka tetapi tidak pernah mengirim satu frame pun (langganan gagal,
 *     token ditolak tanpa menutup koneksi). Tidak pernah ada frame, jadi pengecekan frame
 *     tidak akan pernah menganggapnya usang.
 *  2. **Kanal berhenti terbit** — pernah ada frame, lalu diam lebih lama daripada beberapa siklus
 *     ping. Ambang dibuat jauh lebih panjang dari 30 detik supaya ping/pong lebih dulu
 *     mendeteksi kematian, dan supaya pair sepi tidak ikut terputus.
 */
object RealtimeSocketHealth {

    /** Ping OkHttp dikirim tiap 15 detik; sama dengan `NetworkClientProvider.webSocketClient`. */
    const val PING_INTERVAL_MS = 15_000L

    /** Berapa siklus ping harus gagal sebelum diam dianggap kanal mati. */
    const val PING_CYCLES = 6

    /** Diam lebih dari ini setelah pernah menerima frame = kanal berhenti terbit. */
    const val SILENCE_MS = PING_INTERVAL_MS * PING_CYCLES

    /** Batas waktu bagi sesi baru untuk menunjukkan frame pertamanya. */
    const val HANDSHAKE_GRACE_MS = 20_000L

    /**
     * Apakah socket perlu disambung ulang.
     *
     * @param socketOpen socket sedang terbuka; bila `false`, urusan ada pada job reconnect,
     *        jadi jangan menumpuk percobaan dari pengecekan ini.
     * @param connectedAtMs waktu `onOpen` sesi sekarang (`0` bila belum pernah terbuka).
     * @param lastMessageAtMs waktu frame terakhir (`0` bila sesi ini belum menerima frame).
     */
    fun needsRestart(
        socketOpen: Boolean,
        connectedAtMs: Long,
        lastMessageAtMs: Long,
        nowMs: Long
    ): Boolean {
        if (!socketOpen) return false
        val anchor: Long
        val limit: Long
        if (lastMessageAtMs > 0L) {
            anchor = lastMessageAtMs
            limit = SILENCE_MS
        } else {
            anchor = connectedAtMs
            limit = HANDSHAKE_GRACE_MS
        }
        if (anchor <= 0L) return false
        return nowMs - anchor > limit
    }
}
