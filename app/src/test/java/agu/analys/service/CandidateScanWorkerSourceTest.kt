package agu.analys.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N6 + N17 — penjagaan tingkat sumber untuk "satu mode, satu mesin, satu angka".
 *
 * Gejala N6 sulit diuji lewat perilaku: worker dan engine hidup di proses yang sama, memakai
 * `SignalLifecycleManager` singleton, dan hanya berbeda pada **cara merakit input**. Yang bisa
 * dijaga secara deterministik adalah bentuk sumbernya:
 *
 * - worker hanya mengevaluasi **satu** mode (`prefs.strategyMode`), bukan tiga sekaligus;
 * - worker memakai [agu.analys.engine.ModeSignalEvaluator], bukan memanggil evaluator mode langsung;
 * - worker tidak memakai jalur diagnostik yang melewati gerbang orderbook;
 * - engine juga memakai seam yang sama, sehingga penalti anomali makro tidak bisa "lupa" diteruskan.
 *
 * Kontrol negatif: kembalikan pemanggilan `SwingEvaluator.evaluate` langsung di worker, atau
 * hidupkan lagi `diagnosticIgnoreOrderBookWhenUnavailable = true` → uji ini GAGAL.
 */
class CandidateScanWorkerSourceTest {

    private fun berkas(relatif: String): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/$relatif"),
            File(System.getProperty("user.dir"), "app/src/main/java/$relatif")
        )
        return kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    /** Buang komentar supaya dokumentasi yang menyebut nama lama tidak ikut tertangkap. */
    private fun kodeTanpaKomentar(sumber: String): String =
        sumber
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private fun workerKode(): String =
        kodeTanpaKomentar(berkas("agu/analys/service/CandidateScanWorker.kt").readText())

    private fun engineKode(): String =
        kodeTanpaKomentar(berkas("agu/analys/engine/LearningTradingEngine.kt").readText())

    private fun hitung(teks: String, potongan: String): Int {
        var i = 0
        var n = 0
        while (true) {
            val idx = teks.indexOf(potongan, i)
            if (idx < 0) return n
            n++
            i = idx + potongan.length
        }
    }

    @Test
    fun `worker hanya memproses satu mode aktif`() {
        val kode = workerKode()

        assertTrue("worker wajib membaca mode aktif", kode.contains("prefs.strategyMode"))
        assertTrue(
            "worker wajib memakai seam evaluasi bersama",
            kode.contains("ModeSignalEvaluator.evaluate(")
        )
        assertEquals(
            "N6: satu simbol hanya boleh melewati satu lifecycle.process per pemindaian",
            1,
            hitung(kode, "SignalLifecycleManager.process(")
        )
        assertTrue("pemindaian wajib dibatasi ke watchlist", kode.contains("prefs.getWatchlist()"))
    }

    @Test
    fun `worker tidak memanggil evaluator mode secara langsung`() {
        val kode = workerKode()

        listOf("SwingEvaluator.evaluate(", "IntradayEvaluator.evaluate(", "ScalpingMtfEvaluator.evaluate(")
            .forEach { panggilan ->
                assertFalse(
                    "N17: $panggilan di worker berarti ada perakitan input kedua yang bisa menyimpang " +
                        "dari engine. Pakai ModeSignalEvaluator.",
                    kode.contains(panggilan)
                )
            }
    }

    @Test
    fun `worker tidak memakai jalur diagnostik yang melewati gerbang orderbook`() {
        val kode = workerKode()

        assertFalse(
            "N6: `diagnosticIgnoreOrderBookWhenUnavailable = true` membuat skor worker lebih tinggi " +
                "dari engine untuk input yang sama. Orderbook kosong harus berarti Step2 GAGAL.",
            kode.contains("diagnosticIgnoreOrderBookWhenUnavailable = true")
        )
        assertTrue(
            "worker wajib mengambil orderbook nyata untuk mode scalping",
            kode.contains("fetchOrderBook(")
        )
    }

    @Test
    fun `worker menyiapkan D1 untuk penalti anomali makro`() {
        val kode = workerKode()

        assertTrue(
            "N17: SWING/INTRADAY butuh candle D1 agar penalti anomali makro bisa dihitung",
            kode.contains("Timeframe.D1")
        )
        assertTrue(
            "candle yang masih berjalan harus dibuang seperti buffer engine",
            kode.contains("dropLast(1)")
        )
    }

    @Test
    fun `engine juga memakai seam yang sama`() {
        val kode = engineKode()

        assertTrue("engine wajib memakai ModeSignalEvaluator", kode.contains("ModeSignalEvaluator.evaluate("))
        assertEquals(
            "N17: anomali makro hanya boleh dihitung di dalam seam, bukan di engine",
            0,
            hitung(kode, "MacroAnomalyDetector.evaluate(")
        )
    }
}
