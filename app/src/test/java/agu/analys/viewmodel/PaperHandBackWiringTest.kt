package agu.analys.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Uji serah-terima auto-exit paper ke jalur auto-sell lama — **P9**.
 *
 * Cacat lama: mematikan auto-exit saat posisi terbuka tidak menyalakan kembali
 * `auto_sell_enabled`, sehingga posisi tanpa SL/TP sampai restart — padahal setelah restart
 * `PaperRehydrate` justru menyerahkannya ke jalur lama. Uji ini memastikan transisi
 * `true -> false` memanggil serah-terima, dan `clearAll` tidak lagi dijalankan saat auto-exit
 * masih aktif (mematikan auto-buy saja tidak boleh membunuh mesin exit).
 *
 * Kontrol negatif: hapus pemanggilan `handBackAllPaperEnginesToLegacy` atau kembalikan
 * `if (!config.autoBuyEnabled) clearAll()` → uji gagal.
 */
class PaperHandBackWiringTest {

    private fun baca(relatif: String): String {
        val base = File(System.getProperty("user.dir"))
        val kandidat = listOf(File(base, relatif), File(base, "app/$relatif"))
        val file = kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${base.absolutePath}")
        return file.readLines()
            .filterNot { baris ->
                val s = baris.trimStart()
                s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
            }
            .joinToString("\n")
    }

    private val viewModel: String by lazy { baca("src/main/java/agu/analys/viewmodel/TradingViewModel.kt") }
    private val coordinator: String by lazy { baca("src/main/java/agu/analys/viewmodel/PaperAutoTradeCoordinator.kt") }

    @Test
    fun `transisi auto-exit true ke false menyerahkan posisi ke jalur lama`() {
        val idx = viewModel.indexOf("fun updatePaperAutoTradeConfig")
        assertTrue("updatePaperAutoTradeConfig harus ada", idx >= 0)
        val akhir = viewModel.indexOf("fun updateTradingFees", idx)
        assertTrue("batas fungsi tidak ditemukan", akhir > idx)
        val badan = viewModel.substring(idx, akhir)

        assertTrue(
            "transisi auto-exit harus terdeteksi dari nilai sebelumnya",
            badan.contains("previous.autoExitEnabled && !config.autoExitEnabled")
        )
        assertTrue(
            "posisi harus diserahkan, bukan ditinggal tanpa stop",
            badan.contains("handBackAllPaperEnginesToLegacy()")
        )
    }

    @Test
    fun `mematikan auto-buy saja tidak menghapus mesin exit`() {
        val idx = viewModel.indexOf("fun updatePaperAutoTradeConfig")
        val akhir = viewModel.indexOf("fun updateTradingFees", idx)
        val badan = viewModel.substring(idx, akhir)

        assertTrue(
            "clearAll hanya boleh saat auto-buy DAN auto-exit sama-sama mati",
            badan.contains("!config.autoBuyEnabled && !config.autoExitEnabled")
        )
        assertFalse(
            "guard lama hanya melihat auto-buy dan menelantarkan posisi auto-exit",
            badan.contains("if (!config.autoBuyEnabled) {")
        )
    }

    @Test
    fun `serah-terima mengisi SL, melepas penanda, dan menghapus progres FSM`() {
        assertTrue(coordinator.contains("fun TradingViewModel.handBackAllPaperEnginesToLegacy("))
        assertTrue(coordinator.contains("PaperHandBackResolver.resolveStopLoss("))
        assertTrue("SL hasil resolver harus ditulis ke jalur lama", coordinator.contains("stopLossPrice = stop.price"))
        assertTrue("tanpa SL valid auto-sell tidak boleh dinyalakan", coordinator.contains("enabled = stop.usable"))
        assertTrue("penanda kepemilikan mesin harus dilepas", coordinator.contains("owned = false"))
        assertTrue("progres FSM milik mesin tidak boleh tertinggal", coordinator.contains("clearPaperFsmState("))
    }

    @Test
    fun `serah-terima mencakup semua bursa, bukan hanya bursa aktif`() {
        assertTrue(coordinator.contains("PaperAutoTradeRuntime.activePositions()"))
        assertTrue(coordinator.contains("PaperAutoTradeRuntime.clear(exchange, rawSymbol)"))
    }
}
