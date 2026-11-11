package agu.analys.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N12 — cooldown per pair untuk notifikasi kandidat.
 *
 * Yang dijaga: siklus `READY → INVALIDATED → READY` pada pasar berombak tidak boleh membunyikan
 * notifikasi berulang untuk setup yang praktis sama.
 */
class CandidateNotifyThrottleTest {

    @Test
    fun `belum pernah mengirim selalu boleh`() {
        assertTrue(CandidateNotifyThrottle.shouldNotify(lastNotifiedAt = 0L, now = 1_700_000_000_000L))
    }

    @Test
    fun `satu menit setelah kirim masih ditahan`() {
        assertFalse(CandidateNotifyThrottle.shouldNotify(lastNotifiedAt = 1_000_000L, now = 1_060_000L))
    }

    @Test
    fun `tepat lima menit masih ditahan`() {
        assertFalse(
            CandidateNotifyThrottle.shouldNotify(
                lastNotifiedAt = 1_000_000L,
                now = 1_000_000L + CandidateNotifyThrottle.WINDOW_MS
            )
        )
    }

    @Test
    fun `satu milidetik lewat jendela boleh mengirim`() {
        assertTrue(
            CandidateNotifyThrottle.shouldNotify(
                lastNotifiedAt = 1_000_000L,
                now = 1_000_000L + CandidateNotifyThrottle.WINDOW_MS + 1L
            )
        )
    }

    @Test
    fun `jam mundur tidak memicu notifikasi`() {
        assertFalse(CandidateNotifyThrottle.shouldNotify(lastNotifiedAt = 2_000_000L, now = 1_900_000L))
    }

    // ── Penjaga berstatus ───────────────────────────────────────────────────

    @Test
    fun `pair yang sama ditahan di dalam jendela`() {
        val c = CandidateNotifyCooldown()
        assertTrue(c.allow("TOKOCRYPTO_BTCIDR", 1_000_000L))
        assertFalse(c.allow("TOKOCRYPTO_BTCIDR", 1_060_000L))
    }

    @Test
    fun `pair lain punya jendela sendiri`() {
        val c = CandidateNotifyCooldown()
        assertTrue(c.allow("TOKOCRYPTO_BTCIDR", 1_000_000L))
        assertTrue(c.allow("TOKOCRYPTO_ETHIDR", 1_000_001L))
    }

    @Test
    fun `setelah jendela lewat pair yang sama boleh lagi`() {
        val c = CandidateNotifyCooldown()
        assertTrue(c.allow("TOKOCRYPTO_BTCIDR", 1_000_000L))
        assertTrue(c.allow("TOKOCRYPTO_BTCIDR", 1_000_000L + CandidateNotifyThrottle.WINDOW_MS + 1L))
    }

    @Test
    fun `clear mengosongkan seluruh jendela`() {
        val c = CandidateNotifyCooldown()
        assertTrue(c.allow("TOKOCRYPTO_BTCIDR", 1_000_000L))
        c.clear()
        assertTrue(c.allow("TOKOCRYPTO_BTCIDR", 1_000_001L))
    }

    @Test
    fun `pencatatan hanya terjadi saat diizinkan`() {
        val c = CandidateNotifyCooldown()
        assertTrue(c.allow("TOKOCRYPTO_BTCIDR", 1_000_000L))
        // ditahan: waktu tercatat tidak boleh bergeser ke 1_010_000
        assertFalse(c.allow("TOKOCRYPTO_BTCIDR", 1_010_000L))
        assertFalse(c.allow("TOKOCRYPTO_BTCIDR", 1_020_000L))
        // jendela tetap dihitung dari kiriman yang benar-benar terjadi
        assertTrue(c.allow("TOKOCRYPTO_BTCIDR", 1_000_000L + CandidateNotifyThrottle.WINDOW_MS + 1L))
    }

    @Test
    fun `jendela lebih panjang daripada cooldown peak dan emergency`() {
        // kandidat = ajakan menilai setup baru, bukan pembaruan status: boleh lebih jarang
        assertTrue(CandidateNotifyThrottle.WINDOW_MS > TrailingPeakThrottle.WINDOW_MS)
        assertTrue(CandidateNotifyThrottle.WINDOW_MS == 5 * 60_000L)
    }
}
