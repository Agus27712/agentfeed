package agu.analys.util

import agu.analys.model.AISignalState
import agu.analys.model.MarketKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **N1** — `AISignalState.confidence` bertipe `Int` skala **0–100**, bukan pecahan 0–1.
 *
 * Sebelum perbaikan 2026-10-16, `sendCandidateFoundNotification` menulis
 * `(signal.confidence * 100).toInt()`, sehingga skor `68` muncul sebagai **`6800%`** di push
 * notification kandidat — permukaan yang paling sering dilihat user. Uji ini menjaga agar
 * faktor pengali itu tidak kembali.
 *
 * Kontrol negatif: kembalikan `* 100` di [buildCandidateSignalMessage] → uji ini GAGAL.
 */
class CandidateSignalMessageTest {

    private fun signal(confidence: Int) = AISignalState(
        confidence = confidence,
        entryPrice = 1234.0,
        targetPrice1 = 1300.0,
        stopLoss = 1180.0
    )

    private val keyIdr = MarketKey.resolve("BTCIDR", "TOKOCRYPTO", "IDR")
    private val keyUsdt = MarketKey.resolve("BTCUSDT", "TOKOCRYPTO", "USDT")

    @Test
    fun `skor keyakinan dipakai apa adanya, bukan dikali 100`() {
        val pesan = buildCandidateSignalMessage(keyIdr, signal(68))

        assertTrue(
            "pesan harus memuat skor asli '68%', isi pesan: $pesan",
            pesan.contains("Skor Keyakinan: 68%")
        )
        assertFalse(
            "N1: '6800' adalah gejala bug ×100, isi pesan: $pesan",
            pesan.contains("6800")
        )
    }

    @Test
    fun `batas atas 100 tidak pernah membengkak jadi 10000`() {
        val pesan = buildCandidateSignalMessage(keyUsdt, signal(100))

        assertTrue(pesan.contains("Skor Keyakinan: 100%"))
        assertFalse("skor 100 tidak boleh jadi 10000", pesan.contains("10000"))
    }

    @Test
    fun `skor nol tetap nol`() {
        val pesan = buildCandidateSignalMessage(keyIdr, signal(0))

        assertTrue(pesan.contains("Skor Keyakinan: 0%"))
        assertFalse("skor 0 tidak boleh jadi 0%0% atau serupa", pesan.contains("0%0%"))
    }

    @Test
    fun `pesan tetap membawa entry, target, dan stop loss`() {
        val pesan = buildCandidateSignalMessage(keyIdr, signal(55))

        assertTrue("entry hilang dari pesan: $pesan", pesan.contains("Entry:"))
        assertTrue("target hilang dari pesan: $pesan", pesan.contains("Target 1:"))
        assertTrue("stop loss hilang dari pesan: $pesan", pesan.contains("Stop Loss:"))
        assertTrue("nama bursa hilang dari pesan: $pesan", pesan.contains(keyIdr.displayExchange))
    }

    /**
     * **N5** — push notification kandidat membawa Entry/Target/Stop Loss, jadi ia permukaan yang
     * paling berisiko dibaca sebagai ajakan beli nyata. AGENTS.md mewajibkan setiap permukaan
     * status siap menyatakan bahwa eksekusi uang riil masih diblokir.
     *
     * Kontrol negatif: hapus baris `CANDIDATE_DISCLAIMER` → uji ini GAGAL.
     */
    @Test
    fun `pesan kandidat wajib membawa disclaimer uang riil diblokir`() {
        val pesan = buildCandidateSignalMessage(keyIdr, signal(72))

        assertTrue(
            "disclaimer wajib ada di pesan kandidat: $pesan",
            pesan.contains(CANDIDATE_DISCLAIMER)
        )
        assertTrue("disclaimer harus menyebut eksekusi uang riil", pesan.contains("uang riil"))
        assertTrue("disclaimer harus menyatakan DIBLOKIR", pesan.contains("DIBLOKIR"))
    }
}
