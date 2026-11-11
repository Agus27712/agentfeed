package agu.analys.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Uji wiring Fase 1 auto-order paper (eksekusi, fill, exit gagal).
 *
 * Kontrol negatif: kembalikan `simBal` untuk exit penuh, hapus `return success`,
 * atau hapus `val closed = finalizePaperExit(...)` → uji gagal.
 */
class PaperAutoBuyExecutionWiringTest {

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

    private val coordinator: String by lazy {
        baca("src/main/java/agu/analys/viewmodel/PaperAutoTradeCoordinator.kt")
    }
    private val alerts: String by lazy {
        baca("src/main/java/agu/analys/viewmodel/TradingViewModelAlerts.kt")
    }

    @Test
    fun `auto-buy memakai harga dan quantity fill, bukan harga sinyal`() {
        assertTrue(coordinator.contains("val fillPrice = order.filledAvgPrice.takeIf"))
        assertTrue(coordinator.contains("val filledQty = order.filledQuantity.takeIf"))
        assertTrue(coordinator.contains("entryPrice = fillPrice"))
        assertTrue(coordinator.contains("quantity = filledQty"))
    }

    @Test
    fun `auto-buy dan arm engine memakai resolver SL yang sama`() {
        assertTrue(coordinator.contains("PaperStopResolver.resolveForEntry("))
        assertTrue(coordinator.contains("PaperAutoTradeRuntime.ownsExit("))
        assertTrue(coordinator.contains("entryPrice = fillPrice"))
    }

    @Test
    fun `exit gagal tidak melepas mesin FSM atau snapshot`() {
        assertTrue(coordinator.contains("val closed = finalizePaperExit("))
        assertTrue(coordinator.contains("persistPaperFsm(exchange, symbol, machine)"))
        assertTrue(coordinator.contains("PaperExitSnapshot.of(machine, symbol, exchange, currentPrice)"))
        assertTrue(coordinator.contains("posisi dan FSM dipertahankan untuk dicoba lagi"))
    }

    @Test
    fun `exit paper tidak menjual seluruh saldo base dan mengembalikan sukses aktual`() {
        assertTrue(alerts.contains("val finalSellQty = quantity.coerceAtMost(simBal)"))
        assertTrue(alerts.contains("return success"))
    }
}