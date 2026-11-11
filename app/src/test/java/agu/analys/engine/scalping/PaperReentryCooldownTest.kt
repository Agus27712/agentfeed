package agu.analys.engine.scalping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji jeda entri-ulang auto-buy paper (temuan 2026-10-26, WINUSDT).
 *
 * Kontrol negatif: hapus gerbang `cfg.reentryAllowed` di `tryPaperAutoBuy` — lalu uji
 * wiring `PaperAutoBuyReentryCooldownWiringTest` gagal. Uji ini menjaga aturannya sendiri.
 */
class PaperReentryCooldownTest {

    private val lastSold = 1_000_000L

    @Test
    fun `jeda nonaktif saat cooldownMinutes nol atau negatif`() {
        assertTrue(PaperReentryCooldown.verify(lastSold, lastSold + 1, 0) is PaperReentryVerdict.Allow)
        assertTrue(PaperReentryCooldown.verify(lastSold, lastSold + 1, -5) is PaperReentryVerdict.Allow)
    }

    @Test
    fun `tanpa catatan sell selalu boleh masuk`() {
        assertTrue(PaperReentryCooldown.verify(0L, lastSold, 15) is PaperReentryVerdict.Allow)
        assertTrue(PaperReentryCooldown.verify(-1L, lastSold, 15) is PaperReentryVerdict.Allow)
    }

    @Test
    fun `di dalam jendela jeda tidak boleh masuk dan sisa dihitung benar`() {
        // 3 menit dari sell, jeda 15 menit → sisa 12 menit.
        val nowMs = lastSold + 3L * 60_000L
        val verdict = PaperReentryCooldown.verify(lastSold, nowMs, 15)
        assertTrue(verdict is PaperReentryVerdict.Wait)
        val wait = verdict as PaperReentryVerdict.Wait
        assertEquals(12L * 60_000L, wait.remainingMs)
        assertEquals(15L * 60_000L, wait.cooldownMs)
        assertEquals(lastSold, wait.lastSoldAtMs)
    }

    @Test
    fun `tepat di batas jeda sudah boleh masuk`() {
        val nowMs = lastSold + 15L * 60_000L
        assertTrue(PaperReentryCooldown.verify(lastSold, nowMs, 15) is PaperReentryVerdict.Allow)
    }

    @Test
    fun `jam perangkat mundur diperlakukan baru saja dijual`() {
        // nowMs < lastSold: elapsed dijepit ke 0, seluruh jeda berlaku.
        val verdict = PaperReentryCooldown.verify(lastSold, lastSold - 60_000, 15)
        assertTrue(verdict is PaperReentryVerdict.Wait)
        assertEquals(15L * 60_000L, (verdict as PaperReentryVerdict.Wait).remainingMs)
    }

    @Test
    fun `cooldownMs mengonversi menit ke milidetik`() {
        assertEquals(900_000L, PaperReentryCooldown.cooldownMs(15))
        assertEquals(0L, PaperReentryCooldown.cooldownMs(0))
    }
}
