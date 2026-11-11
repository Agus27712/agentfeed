package agu.analys.trading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Label "Skor Konfidensi" di berkas jurnal hasil ekspor (`.md`).
 *
 * Latar belakang: label lama memakai simbol irisan, `Skor Konfidensi (engine ∩ cap tahap)`.
 * Di sebagian font penampil Markdown, simbol itu dirender mirip aksara CJK sehingga laporan
 * terbaca seperti "tulisan Mandarin". Yang dijaga di sini bukan perilaku angka, melainkan
 * **teks labelnya**: harus kata Indonesia biasa, tanpa simbol matematika.
 *
 * Kontrol negatif: kembalikan label lama (dengan simbol irisan) → uji ini GAGAL.
 */
class TradeLogExporterLabelTest {

    private fun sumber(): String {
        val relatif = "agu/analys/trading/TradeLogExporter.kt"
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/$relatif"),
            File(System.getProperty("user.dir"), "app/src/main/java/$relatif")
        )
        val berkas = kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
        return berkas.readText()
    }

    /** Buang komentar: komentar justru menjelaskan simbol lama, jangan ikut tertangkap. */
    private fun kodeTanpaKomentar(teks: String): String =
        teks.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .lineSequence()
            .joinToString("\n") { baris -> baris.substringBefore("//") }

    @Test
    fun `label skor konfidensi tidak memakai simbol irisan`() {
        val kode = kodeTanpaKomentar(sumber())
        assertFalse(
            "simbol irisan pada label ekspor membuat .md terbaca seperti aksara CJK",
            kode.contains("∩")
        )
    }

    @Test
    fun `label skor konfidensi memakai kata Indonesia`() {
        val kode = kodeTanpaKomentar(sumber())
        assertTrue(
            "label harus menjelaskan bahwa angkanya sudah dibatasi tahap",
            kode.contains("Skor Konfidensi (skor engine, dibatasi tahap)")
        )
    }
}
