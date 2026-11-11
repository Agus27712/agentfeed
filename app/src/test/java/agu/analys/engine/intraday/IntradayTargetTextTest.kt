package agu.analys.engine.intraday

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Penjaga teks kartu target intraday (paket F).
 *
 * Menguji isi teks yang benar-benar ditampilkan `RadarTargetLevelsSection` (lewat
 * [IntradayTargetText]) tanpa Robolectric. Yang dijaga bukan "string ada di berkas", melainkan
 * tiga syarat yang pernah gagal di mode ini:
 *
 * - label R:R **menyebut TP1** (gate dinilai dari TP1 sejak paket B3);
 * - catatan menyatakan persen target **gross** dan net = setelah fee beli + fee jual + slippage
 *   **dua sisi**;
 * - tidak ada klaim "sudah bersih fee & pajak" (frasa yang dilarang temuan J7).
 */
class IntradayTargetTextTest {

    @Test
    fun labelNetRrMenyebutTp1() {
        assertTrue(
            "Label net R:R wajib menyebut TP1, dapat: '${IntradayTargetText.NET_RR_ROW_LABEL}'",
            IntradayTargetText.NET_RR_ROW_LABEL.contains("TP1")
        )
        assertTrue(
            "Label net R:R wajib menyatakan basis net",
            IntradayTargetText.NET_RR_ROW_LABEL.contains("Net", ignoreCase = true)
        )
    }

    @Test
    fun catatanMenyebutGrossDanKomponenBiayaDuaSisi() {
        val note = IntradayTargetText.NET_RR_NOTE
        listOf("gross", "fee beli", "fee jual", "slippage", "dua sisi").forEach { kata ->
            assertTrue("Catatan kartu intraday wajib menyebut '$kata', dapat: '$note'", note.contains(kata))
        }
    }

    @Test
    fun catatanMenyebutSampelBelumCukup() {
        assertTrue(
            "Catatan wajib menyebut keterbatasan sampel, dapat: '${IntradayTargetText.NET_RR_NOTE}'",
            IntradayTargetText.NET_RR_NOTE.contains("sampel")
        )
    }

    @Test
    fun catatanTidakMemakaiFrasaTerlarang() {
        // Frasa "sudah bersih fee & pajak" dilarang (temuan J7): kartu target hanya menampilkan
        // perubahan harga, bukan hasil bersih.
        assertFalse(
            "Frasa 'sudah bersih' dilarang di kartu target",
            IntradayTargetText.NET_RR_NOTE.contains("sudah bersih", ignoreCase = true)
        )
    }
}
