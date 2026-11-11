package agu.analys.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Uji wiring jeda entri-ulang auto-buy paper (temuan 2026-10-26, WINUSDT).
 *
 * Kontrol negatif: hapus gerbang `cfg.reentryAllowed(...)` di `tryPaperAutoBuy`,
 * hapus `if (!isReal) editor.putLong(...)` di `markSold`, atau hapus field
 * `reentryCooldownMinutes` dari `PaperAutoTradeConfig` — uji di sini gagal.
 */
class PaperAutoBuyReentryCooldownWiringTest {

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
    private val store: String by lazy {
        baca("src/main/java/agu/analys/trading/SpotPositionStore.kt")
    }
    private val config: String by lazy {
        baca("src/main/java/agu/analys/engine/scalping/PaperAutoTradeConfig.kt")
    }

    @Test
    fun `auto-buy membaca catatan sell dan mengecek jeda entri-ulang`() {
        assertTrue(coordinator.contains("positionStore.lastPaperSoldAt("))
        assertTrue(coordinator.contains("cfg.reentryAllowed(lastSoldAt"))
    }

    @Test
    fun `markSold paper menulis waktu sell untuk jeda entri-ulang`() {
        assertTrue(store.contains("paperSoldKey(norm, exchange), changedAt"))
        assertTrue(store.contains("fun lastPaperSoldAt("))
    }

    @Test
    fun `jeda live di config, bukan literal di runtime`() {
        assertTrue(config.contains("reentryCooldownMinutes: Int = 15"))
        assertTrue(config.contains("PaperReentryCooldown.verify(lastSoldAtMs, nowMs, reentryCooldownMinutes)"))
    }
}
