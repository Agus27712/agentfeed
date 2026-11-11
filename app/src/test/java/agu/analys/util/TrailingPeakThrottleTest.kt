package agu.analys.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N14 — pembatas notifikasi "Trailing Stop Naik".
 *
 * Yang dijaga: satu posisi yang harganya terus naik tidak boleh membunyikan notifikasi tiap tick
 * (~10 detik). Jendela 30 detik harus **benar-benar** berlaku — sebelum perbaikan, jalur kembar
 * mengirim lebih dulu tanpa pembatas, sehingga angka ini tidak pernah mengatur apa pun.
 */
class TrailingPeakThrottleTest {

    @Test
    fun `belum pernah mengirim selalu boleh`() {
        assertTrue(TrailingPeakThrottle.shouldNotify(lastNotifiedAt = 0L, now = 1_700_000_000_000L))
    }

    @Test
    fun `sepuluh detik setelah kirim masih ditahan`() {
        assertFalse(TrailingPeakThrottle.shouldNotify(lastNotifiedAt = 1_000_000L, now = 1_010_000L))
    }

    @Test
    fun `tepat tiga puluh detik masih ditahan`() {
        // batasnya strict `>`: dua tick pada milidetik yang sama tidak boleh lolos dua kali
        assertFalse(
            TrailingPeakThrottle.shouldNotify(
                lastNotifiedAt = 1_000_000L,
                now = 1_000_000L + TrailingPeakThrottle.WINDOW_MS
            )
        )
    }

    @Test
    fun `satu milidetik lewat jendela boleh mengirim`() {
        assertTrue(
            TrailingPeakThrottle.shouldNotify(
                lastNotifiedAt = 1_000_000L,
                now = 1_000_000L + TrailingPeakThrottle.WINDOW_MS + 1L
            )
        )
    }

    @Test
    fun `jam mundur tidak memicu notifikasi`() {
        // pengaturan waktu perangkat bisa bergeser; selisih negatif harus dianggap "baru saja kirim"
        assertFalse(TrailingPeakThrottle.shouldNotify(lastNotifiedAt = 2_000_000L, now = 1_900_000L))
    }

    @Test
    fun `jendela yang dipakai adalah tiga puluh detik`() {
        assertTrue(TrailingPeakThrottle.WINDOW_MS == 30_000L)
    }
}
