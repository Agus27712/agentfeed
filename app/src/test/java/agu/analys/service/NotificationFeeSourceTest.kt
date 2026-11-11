package agu.analys.service

import agu.analys.config.FeeSchedule
import agu.analys.config.TradingFeeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **N3** — jalur notifikasi wajib memakai biaya milik user, bukan default legacy.
 *
 * Sebelum perbaikan 2026-10-16, `TradingForegroundService` memanggil
 * `SellSignalEvaluator.evaluate(..., tradingFees = TradingFeeConfig(), ...)`. Tanpa argumen,
 * `TradingFeeConfig` jatuh ke preset **legacy** (0,11 / 0,21 / 0,32 / 0,42) — bukan
 * biaya bursa aktif milik user. Akibatnya "Estimasi PnL" di notifikasi exit/TP dihitung dengan
 * friksi yang berbeda dari jalur UI, dan aturan **SATU rumus P&L** (AGENTS.md) bocor di permukaan
 * notifikasi.
 *
 * Berkas ini berisi dua lapis penjagaan:
 *  1. **invarian sumber** — jalur runtime service dilarang memakai `TradingFeeConfig()` tanpa
 *     argumen lagi (langsung menjaga baris yang diperbaiki);
 *  2. **bukti dampak** — membuktikan default legacy memang menghasilkan angka yang berbeda,
 *     jadi bug ini bukan sekadar kosmetik.
 *
 * Kontrol negatif: kembalikan `tradingFees = TradingFeeConfig()` di
 * `TradingForegroundService` → uji invarian sumber GAGAL.
 */
class NotificationFeeSourceTest {

    private fun serviceSource(): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/agu/analys/service/TradingForegroundService.kt"),
            File(System.getProperty("user.dir"), "app/src/main/java/agu/analys/service/TradingForegroundService.kt")
        )
        return kandidat.firstOrNull { it.exists() }
            ?: error("TradingForegroundService.kt tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    /** Buang komentar dulu, supaya dokumentasi yang menyebut `TradingFeeConfig()` tidak ikut tertangkap. */
    private fun kodeTanpaKomentar(sumber: String): String =
        sumber
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("""\s+"""), "")

    @Test
    fun `service foreground dilarang memakai default legacy TradingFeeConfig`() {
        val berkas = serviceSource()
        val kode = kodeTanpaKomentar(berkas.readText())

        assertFalse(
            "N3: jalur runtime service tidak boleh memakai TradingFeeConfig() tanpa argumen — " +
                "pakai prefs user (AppPreferences.tradingFees) agar sama dengan jalur UI",
            kode.contains("TradingFeeConfig()")
        )
        assertTrue(
            "N3 + FEE_TRUTH: service harus membaca biaya user untuk bursa+pair posisi",
            kode.contains("tradingFees=appPrefs.feeConfigFor(sym,ex)")
        )
    }

    @Test
    fun `default legacy berbeda dari biaya bursa aktif`() {
        val legacy = TradingFeeConfig()
        val tokocryptoIdr = FeeSchedule.configFor("TOKOCRYPTO", "IDR")

        assertNotEquals("default data class memang bukan biaya Tokocrypto IDR", legacy, tokocryptoIdr)
        assertEquals(0.21, legacy.buyTakerPct, 1e-9)
        assertEquals(0.2222, tokocryptoIdr.buyTakerPct, 1e-9)
    }

    @Test
    fun `friksi legacy menggeser angka PnL sehingga estimasi notifikasi salah`() {
        val legacy = TradingFeeConfig()
        val user = FeeSchedule.configFor("TOKOCRYPTO", "IDR")

        // Posisi tipis: entry 1.000, harga 1.010 (+1,0% kotor).
        val netLegacy = FeeSchedule.netProfitPct(
            entry = 1_000.0,
            price = 1_010.0,
            buyFeePct = legacy.buyTakerPct,
            sellFeePct = legacy.sellTakerPct
        )
        val netUser = FeeSchedule.netProfitPct(
            entry = 1_000.0,
            price = 1_010.0,
            buyFeePct = user.buyTakerPct,
            sellFeePct = user.sellTakerPct
        )

        assertNotEquals("friksi berbeda harus menghasilkan net PnL berbeda", netLegacy, netUser, 1e-9)
        // Friksi legacy lebih ringan, jadi net-nya terlihat lebih besar daripada kenyataan.
        assertTrue(
            "default legacy membuat PnL terlihat lebih besar dari kenyataan: legacy=$netLegacy user=$netUser",
            netLegacy > netUser
        )
    }
}
