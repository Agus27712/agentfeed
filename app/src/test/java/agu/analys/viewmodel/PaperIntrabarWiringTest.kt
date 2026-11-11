package agu.analys.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Uji pemasangan high intrabar M1 di evaluator exit paper — **P6 bagian kedua**.
 *
 * Cacat lama: `evaluatePaperAutoExitLocked` membuat bar sintetis dengan `high = currentPrice`,
 * sehingga `peak` tak pernah melihat high M1 dan trailing jalur latar lebih longgar daripada
 * replay. Uji ini memastikan jalur itu memakai `PaperIntrabarPeakResolver` dan cache M1 dibaca
 * **sekali** untuk dua kebutuhan (jendela momentum dan peak).
 *
 * Kontrol negatif: kembalikan `high = currentPrice`, atau baca cache dua kali → uji gagal.
 */
class PaperIntrabarWiringTest {

    private val kode: String by lazy {
        val base = File(System.getProperty("user.dir"))
        val kandidat = listOf(
            File(base, "src/main/java/agu/analys/viewmodel/PaperAutoTradeCoordinator.kt"),
            File(base, "app/src/main/java/agu/analys/viewmodel/PaperAutoTradeCoordinator.kt")
        )
        val file = kandidat.firstOrNull { it.exists() }
            ?: error("PaperAutoTradeCoordinator.kt tidak ditemukan dari user.dir=${base.absolutePath}")
        file.readLines()
            .filterNot { baris ->
                val s = baris.trimStart()
                s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
            }
            .joinToString("\n")
    }

    @Test
    fun `bar sintetis memakai high intrabar, bukan harga tick`() {
        assertTrue(kode.contains("PaperIntrabarPeakResolver.effectiveHigh("))
        assertTrue("bar wajib memakai high hasil resolver", kode.contains("high = intrabarHigh"))
        assertFalse("high dari tick adalah cacat P6", kode.contains("high = currentPrice"))
    }

    @Test
    fun `cache M1 dibaca sekali untuk jendela dan peak`() {
        assertEquals(1, Regex("""getCachedCandles\(""").findAll(kode).count())
    }

    @Test
    fun `openedAt posisi dipakai sebagai batas bawah candle`() {
        assertTrue(kode.contains("openedAtMs = PaperAutoTradeRuntime.openedAt("))
    }
}
