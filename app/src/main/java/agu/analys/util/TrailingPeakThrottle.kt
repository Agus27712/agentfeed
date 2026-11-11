package agu.analys.util

/**
 * N14 — pembatas notifikasi "Trailing Stop Naik" (peak bergerak).
 *
 * `peak` bergerak **setiap** kali harga membuat puncak baru, dan polling trailing berjalan tiap
 * ~10 detik. Tanpa pembatas, satu posisi yang sedang naik menghasilkan bunyi terus-menerus.
 *
 * Sebelum perbaikan 2026-10-17 pembatas 30 detik ini **tidak pernah berlaku penuh**: jalur kembar
 * (`updateSimTrailingOrder` / `updateRealTrailingOrder`) mengirim notifikasinya sendiri lebih dulu,
 * tanpa pembatas apa pun. Sekarang hanya satu pengirim ([shouldNotify] menentukan kapan), sehingga
 * angka di sini benar-benar mengatur bunyi.
 *
 * Batasnya **strict** (`>`) supaya dua tick pada milidetik yang sama tidak lolos dua kali; jendela
 * 30.000 ms persis masih ditahan, sama seperti perilaku lama.
 */
object TrailingPeakThrottle {

    /** Jendela minimum antar notifikasi peak untuk satu posisi. */
    const val WINDOW_MS = 30_000L

    /**
     * @param lastNotifiedAt waktu kirim terakhir (ms epoch); `0L` berarti belum pernah.
     * @param now waktu sekarang (ms epoch).
     */
    fun shouldNotify(lastNotifiedAt: Long, now: Long): Boolean =
        now - lastNotifiedAt > WINDOW_MS
}
