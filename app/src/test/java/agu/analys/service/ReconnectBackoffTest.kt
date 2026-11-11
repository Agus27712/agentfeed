package agu.analys.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **K8** — kebijakan backoff sambung-ulang soket realtime.
 *
 * Cacat lama: `reconnectAttempt` di-reset di `onOpen`, jeda tidak berjitter dan tanpa batas.
 * Server yang menerima lalu langsung menutup koneksi membuat siklus buka → tutup → tunggu
 * 600–800 ms tanpa henti.
 *
 * Kebijakan murni di [ReconnectBackoff] diuji di sini tanpa jaringan dan tanpa keacakan
 * (nilai jitter `random01` dioper pemanggil).
 *
 * Kontrol negatif: ubah [ReconnectBackoff.STABLE_AFTER_MS] menjadi `0` → uji
 * "sesi baru belum dianggap stabil" GAGAL; naikkan [ReconnectBackoff.GIVE_UP_AFTER_ATTEMPTS]
 * menjadi `999` → uji "batas menyerah" GAGAL.
 */
class ReconnectBackoffTest {

    private val baseMs = 800L
    private val maxMs = 12_000L

    @Test
    fun `jeda tumbuh berlipat tanpa jitter`() {
        assertEquals(baseMs, ReconnectBackoff.delayMs(1, baseMs, maxMs, 0.5))
        assertEquals(baseMs * 2, ReconnectBackoff.delayMs(2, baseMs, maxMs, 0.5))
        assertEquals(baseMs * 4, ReconnectBackoff.delayMs(3, baseMs, maxMs, 0.5))
    }

    @Test
    fun `jeda dibatasi plafon`() {
        assertEquals(
            "percobaan ke-20 tidak boleh melewati plafon",
            maxMs,
            ReconnectBackoff.delayMs(20, baseMs, maxMs, 0.5)
        )
    }

    @Test
    fun `jitter hanya menggeser paling banyak dua puluh persen`() {
        assertEquals((baseMs * 0.8).toLong(), ReconnectBackoff.delayMs(1, baseMs, maxMs, 0.0))
        assertEquals((baseMs * 1.2).toLong(), ReconnectBackoff.delayMs(1, baseMs, maxMs, 1.0))
    }

    @Test
    fun `jeda selalu positif walau basis nol`() {
        assertTrue(
            "jeda 0 ms berarti siklus tanpa jeda",
            ReconnectBackoff.delayMs(1, 0L, maxMs, 0.0) >= 1L
        )
    }

    @Test
    fun `sesi baru belum dianggap stabil`() {
        val connectedAt = 1_000_000L
        assertFalse(ReconnectBackoff.isStable(connectedAt, connectedAt + ReconnectBackoff.STABLE_AFTER_MS - 1))
        assertTrue(ReconnectBackoff.isStable(connectedAt, connectedAt + ReconnectBackoff.STABLE_AFTER_MS))
    }

    @Test
    fun `connectedAt nol berarti belum pernah tersambung`() {
        assertFalse(
            "belum tersambung bukan berarti stabil",
            ReconnectBackoff.isStable(0L, 10_000_000L)
        )
    }

    @Test
    fun `batas menyerah tercapai tepat pada percobaan keenam`() {
        assertFalse(ReconnectBackoff.shouldGiveUp(ReconnectBackoff.GIVE_UP_AFTER_ATTEMPTS - 1))
        assertTrue(ReconnectBackoff.shouldGiveUp(ReconnectBackoff.GIVE_UP_AFTER_ATTEMPTS))
    }

    @Test
    fun `jeda menyerah jauh lebih panjang dari jeda biasa`() {
        val jedaTerakhir = ReconnectBackoff.delayMs(
            ReconnectBackoff.GIVE_UP_AFTER_ATTEMPTS - 1, baseMs, maxMs, 0.5
        )
        assertTrue(
            "jeda menyerah harus beberapa kali jeda biasa, bukan sekadar plafon",
            ReconnectBackoff.GIVE_UP_PAUSE_MS > jedaTerakhir * 4
        )
    }
}
