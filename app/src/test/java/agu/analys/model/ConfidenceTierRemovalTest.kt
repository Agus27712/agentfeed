package agu.analys.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N11 — keputusan produk: **tabel "Akurasi per Level Confidence" tidak ditampilkan lagi.**
 *
 * Alasannya bukan kosmetik. Ambang tier (≥80 / 60–79 / <60) lintas-mode membuat hampir semua sinyal
 * scalping pra-entry jatuh ke "Awal" karena cap tahap (`≤59/49/39/29`), sehingga tabel itu mengukur
 * "sudah READY atau belum", bukan kualitas berjenjang. Owner memilih tidak menampilkannya daripada
 * mengubah cap (yang akan menggeser skor yang sudah tampil di tempat lain).
 *
 * Uji ini menjaga keputusan itu: kalau kartu/tier dihidupkan lagi tanpa keputusan baru, ia GAGAL.
 */
class ConfidenceTierRemovalTest {

    private fun berkas(relatif: String): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/$relatif"),
            File(System.getProperty("user.dir"), "app/src/main/java/$relatif")
        )
        return kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    private fun kodeTanpaKomentar(relatif: String): String =
        berkas(relatif).readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `layar log tidak lagi menampilkan tabel tier confidence`() {
        val layar = kodeTanpaKomentar("agu/analys/ui/screens/SignalLogScreen.kt")

        assertFalse("N11: kartu tier tidak boleh kembali", layar.contains("RELIABILITAS SKOR KEYAKINAN"))
        assertFalse("N11: baris tier tidak boleh kembali", layar.contains("ConfidenceTierRow"))
        assertFalse("N11: section tier tidak boleh kembali", layar.contains("confidence_tier_section"))
        assertTrue(
            "kartu akurasi per setup tetap ada — itu bukan tabel tier",
            layar.contains("SetupAccuracyCard")
        )
    }

    @Test
    fun `model ringkasan tidak lagi membawa statistik tier`() {
        val model = kodeTanpaKomentar("agu/analys/model/SignalReliabilityModels.kt")

        assertFalse("N11: ConfidenceTierStats sudah dihapus", model.contains("ConfidenceTierStats"))
        assertFalse("N11: field tier sudah dihapus", model.contains("ConfidenceStats"))
    }
}
