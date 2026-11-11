package agu.analys.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * J9 (N9) — skor keyakinan yang dicatat tidak boleh dikarang.
 *
 * Sebelumnya `recordBuy` memakai `signalConfidence = snapshot?.confidenceScore ?: 75`; bila
 * snapshot tidak ada, jurnal menulis 75 — angka yang tidak pernah dihasilkan siapa pun.
 */
class TradeSignalConfidenceTest {

    @Test
    fun `nilai eksplisit menang atas snapshot`() {
        assertEquals(83, TradeSignalConfidence.resolve(explicit = 83, fromSnapshot = 61))
    }

    @Test
    fun `tanpa eksplisit memakai skor snapshot`() {
        assertEquals(61, TradeSignalConfidence.resolve(explicit = null, fromSnapshot = 61))
    }

    @Test
    fun `keduanya kosong menghasilkan nol bukan 75`() {
        val value = TradeSignalConfidence.resolve(explicit = null, fromSnapshot = null)
        assertEquals(0, value)
        assertNotEquals("75 adalah angka karangan lama (J9)", 75, value)
    }

    @Test
    fun `nol eksplisit dihormati bukan dianggap kosong`() {
        assertEquals(0, TradeSignalConfidence.resolve(explicit = 0, fromSnapshot = 61))
    }

    @Test
    fun `nol dari snapshot tetap nol`() {
        assertEquals(0, TradeSignalConfidence.resolve(explicit = null, fromSnapshot = 0))
    }

    @Test
    fun `skor seratus diteruskan`() {
        assertEquals(100, TradeSignalConfidence.resolve(explicit = 100, fromSnapshot = null))
    }
}
