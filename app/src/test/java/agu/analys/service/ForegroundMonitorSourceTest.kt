package agu.analys.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **N15 + N16** — penjagaan tingkat sumber untuk foreground service.
 *
 * Keduanya tidak punya gejala yang bisa diuji lewat fungsi murni:
 *
 * - **N15** — keputusan start/stop service ada di `TradingViewModel.updateForegroundServiceState()`
 *   yang butuh `ViewModel` + `Context`. Yang bisa dijaga: fungsi itu **tidak boleh** membaca
 *   `isNotificationsEnabled` lagi. Kalau dibaca, mematikan sakelar push ikut mematikan keep-alive
 *   pemantauan posisi (dan membuat P&L tampil 0% karena `livePrices` kosong).
 * - **N16** — `AppPreferences` membangun `EncryptedSharedPreferences` + `MasterKey`; kalau
 *   konstruksinya gagal, catch block-nya **menghapus seluruh preferensi**. Karena itu jumlah
 *   konstruksi di service dijaga: hanya satu, milik `by lazy`.
 *
 * Kontrol negatif: kembalikan `&& isNotificationsEnabled.value` di
 * `updateForegroundServiceState`, atau tambahkan `AppPreferences(context)` di salah satu fungsi
 * service → uji ini GAGAL.
 */
class ForegroundMonitorSourceTest {

    private fun berkas(relatif: String): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/$relatif"),
            File(System.getProperty("user.dir"), "app/src/main/java/$relatif")
        )
        return kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    private fun kodeTanpaKomentar(sumber: String): String =
        sumber
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    /** Potong badan satu fungsi: dari `fun <nama>(` sampai `fun` berikutnya di awal baris. */
    private fun badanFungsi(kode: String, nama: String): String {
        val mulai = kode.indexOf("fun $nama(")
        assertTrue("fungsi `$nama` tidak ditemukan", mulai >= 0)
        val sesudah = kode.indexOf("\nfun ", mulai + 1)
        return kode.substring(mulai, if (sesudah < 0) kode.length else sesudah)
    }

    @Test
    fun `start-stop service tidak boleh bergantung pada sakelar notifikasi`() {
        val kode = kodeTanpaKomentar(
            berkas("agu/analys/viewmodel/TradingViewModelSync.kt").readText()
        )
        val badan = badanFungsi(kode, "TradingViewModel.updateForegroundServiceState")

        assertFalse(
            "N15: service ini keep-alive pemantauan posisi. Keputusan start/stop hanya boleh dari " +
                "keberadaan posisi (`hasActive`); bunyi notifikasi diatur canNotify(...) di helper.",
            badan.contains("isNotificationsEnabled")
        )
        assertTrue("keputusan harus tetap memakai hasActive", badan.contains("hasActive"))
    }

    @Test
    fun `startService dan forceRefresh tidak membaca preferensi`() {
        val kode = kodeTanpaKomentar(
            berkas("agu/analys/service/TradingForegroundService.kt").readText()
        )

        listOf("startService", "forceRefresh").forEach { nama ->
            val badan = badanFungsi(kode, nama)
            assertFalse(
                "N15: `$nama` tidak boleh menahan service karena sakelar notifikasi",
                badan.contains("isNotificationsEnabled")
            )
        }
    }

    @Test
    fun `service hanya membangun AppPreferences sekali`() {
        val kode = kodeTanpaKomentar(
            berkas("agu/analys/service/TradingForegroundService.kt").readText()
        )
        val jumlah = Regex("AppPreferences\\(").findAll(kode).count()

        assertEquals(
            "N16: `AppPreferences(...)` dibangun $jumlah kali. Harus 1 (milik `by lazy appPrefs`) — " +
                "konstruksinya mahal dan destruktif bila gagal, dan `updatePrices` dipanggil tiap tick.",
            1,
            jumlah
        )
    }

    @Test
    fun `updatePrice yang tidak terpakai tidak boleh kembali`() {
        val kode = kodeTanpaKomentar(
            berkas("agu/analys/service/TradingForegroundService.kt").readText()
        )

        assertFalse(
            "N16: `updatePrice` (tunggal) tidak punya pemanggil — pakai `updatePrices` yang " +
                "menerima peta harga sekaligus.",
            kode.contains("fun updatePrice(")
        )
    }

    @Test
    fun `updatePrices menerima bursa dari pemanggil`() {
        val kode = kodeTanpaKomentar(
            berkas("agu/analys/service/TradingForegroundService.kt").readText()
        )
        val badan = badanFungsi(kode, "updatePrices")

        assertFalse(
            "N16: `updatePrices` berjalan tiap tick dashboard — jangan membaca preferensi di sini",
            badan.contains("AppPreferences")
        )
        assertTrue(
            "bursa harus dioper pemanggil (parameter wajib), bukan ditebak dari prefs",
            badan.contains("exchange: String")
        )
    }
}
