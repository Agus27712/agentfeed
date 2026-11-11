package agu.analys.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Uji sumber (source-scan) kerangka pengaturan mode — pola sama dengan
 * `PaperBuyNominalSourceTest` dan `ConfidenceTierRemovalTest`: keputusan yang hidup di kode
 * ber-Compose tidak bisa diuji perilakunya di JVM, jadi yang dijaga adalah BENTUK sumbernya.
 *
 * Keputusan owner 2026-11-01 (`AUDIT-2026-11-08.md` §89): Pengaturan → TRADING dulu menampilkan dua
 * kerangka. Kartu pemilih mode menampilkan angka aktif, lalu kartu "PARAMETER ENGINE … AKTIF"
 * mengulang angka yang sama, dan slider-nya hidup di dialog layar penuh terpisah. Sekarang slider
 * berada DI DALAM kartu mode. Uji ini GAGAL kalau kerangka kedua atau dialog itu dibangun ulang.
 */
class ModeSliderFrameSourceTest {

    private fun berkas(relatif: String): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/$relatif"),
            File(System.getProperty("user.dir"), "app/src/main/java/$relatif")
        )
        return kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    private fun kode(relatif: String): String =
        // Pindai kode saja: komentar // dan blok /* */ dibuang supaya uji tidak dipicu oleh
        // tulisan penjelas (KDoc di berkas ini justru menyebut nama kerangka yang dilarang).
        berkas(relatif).readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")

    @Test
    fun `kartu parameter engine kedua tidak boleh hidup lagi`() {
        val kartu = kode("agu/analys/ui/components/settings/TradingModeSettings.kt")
        assertFalse(
            "Kartu 'PARAMETER ENGINE … AKTIF' kembali di TradingModeSettings — " +
                "keputusan owner 2026-11-01: kerangka kedua dihapus, bukan disembunyikan",
            kartu.contains("PARAMETER ENGINE")
        )
        assertFalse(
            "Label 'AKTIF' (kartu parameter mode) kembali di TradingModeSettings",
            kartu.contains("AKTIF")
        )
    }

    @Test
    fun `slider swing hidup di dalam kartu mode`() {
        val kartu = kode("agu/analys/ui/components/settings/TradingModeSettings.kt")
        assertTrue(
            "Kartu SWING wajib memuat slider SwingSensitivitySettings di dalam kadarnya",
            kartu.contains("SwingSensitivitySettings(")
        )
        assertTrue(
            "Kartu SWING wajib membawa tombol Simpan (onSaveSwing) di dalam kartu",
            kartu.contains("onSaveSwing")
        )
        // Draft, bukan terapan langsung: kartu menampilkan draft dan onStrategyChange tidak
        // dipakai lagi oleh kartu SWING — mengaktifkan mode lewat "Simpan & Aktifkan".
        assertFalse(
            "Kartu SWING tidak boleh langsung mengubah mode tanpa Simpan & Aktifkan",
            kartu.contains("onClick = { onStrategyChange(StrategyMode.SWING) }")
        )
    }

    @Test
    fun `dialog engine layar penuh tidak boleh hidup lagi`() {
        val layar = kode("agu/analys/ui/screens/SettingsScreen.kt")
        assertFalse(
            "Dialog engine scalping kembali di SettingsScreen",
            layar.contains("showScalpingEngineSettings")
        )
        assertFalse(
            "Dialog engine intraday kembali di SettingsScreen",
            layar.contains("showIntradayEngineSettings")
        )
        assertFalse(
            "Dialog lebar penuh (usePlatformDefaultWidth = false) kembali di SettingsScreen",
            layar.contains("DialogProperties(usePlatformDefaultWidth = false)")
        )
    }

    @Test
    fun `slider hidup di dalam kartu mode`() {
        val kartu = kode("agu/analys/ui/components/settings/TradingModeSettings.kt")
        listOf(
            "ScalpingSensitivitySettings(",
            "IntradaySensitivitySettings(",
            "PaperAutoTradeSettings(",
            "onSaveScalping",
            "onSaveIntraday"
        ).forEach { bagian ->
            assertTrue(
                "Kartu mode wajib memuat '$bagian' — slider dan tombol Simpan hidup di dalam " +
                    "kartu mode, bukan di dialog terpisah",
                kartu.contains(bagian)
            )
        }
    }

    @Test
    fun `simpan tetap menyalurkan draft ke engine`() {
        val layar = kode("agu/analys/ui/screens/SettingsScreen.kt")
        listOf(
            "viewModel.updateScalpingRuntimeConfig(draftScalpingConfig, draftScalpingPreset)",
            "viewModel.updateIntradayRuntimeConfig(draftIntradayConfig, draftIntradayPreset)",
            "viewModel.updateSwingRuntimeConfig(draftSwingConfig, draftSwingPreset)",
            "viewModel.setStrategyMode(StrategyMode.OFFICE_DAILY)"
        ).forEach { bagian ->
            assertTrue(
                "Tombol Simpan & Aktifkan wajib menyalurkan draft ke engine: '$bagian' hilang",
                layar.contains(bagian)
            )
        }
        assertFalse(
            "Jalur draft tidak boleh memakai preset yang langsung menyimpan ke engine " +
                "(melanggar semantik draft → Simpan & Aktifkan)",
            layar.contains("viewModel.applyScalpingPreset") ||
                layar.contains("viewModel.applyIntradayPreset") ||
                layar.contains("viewModel.applySwingPreset")
        )
    }
}
