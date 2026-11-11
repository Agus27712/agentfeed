package agu.analys.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N8 + N13 — penjagaan tingkat sumber untuk jalur notifikasi.
 *
 * Kedua temuan ini tidak bisa dijaga uji perilaku biasa:
 *
 * - **N8** — parameter `onlyWhenBackground` diteruskan ke `sendPriceAlertNotification` tetapi
 *   **tidak pernah dibaca** di dalam fungsi. Param mati tidak punya gejala runtime yang bisa
 *   diuji; yang bisa dijaga adalah "jangan hidupkan lagi".
 * - **N13** — kategori notifikasi ditentukan di **call site**, bukan di gerbang. Uji gerbang hanya
 *   membuktikan `allowsNotification` benar; ia tidak bisa tahu call site memilih kategori yang
 *   salah. Karena itu helper generik `sendPriceAlertNotification` **dihapus namanya**: penggantinya
 *   `sendUserPriceAlertNotification` (kategori PRICE_ALERT) dan `sendExecutionResultNotification`
 *   (kategori TRAILING_STOP). Nama lama tidak boleh muncul lagi — kalau muncul, artinya ada
 *   pemanggil baru yang memakai satu helper untuk dua makna.
 *
 * Kontrol negatif: tambahkan lagi parameter `onlyWhenBackground` di `AlertNotificationHelper`,
 * atau kembalikan nama `sendPriceAlertNotification` di salah satu call site → uji ini GAGAL.
 */
class NotificationSourceTest {

    private fun berkas(relatif: String): File {
        val kandidat = listOf(
            File(System.getProperty("user.dir"), "src/main/java/$relatif"),
            File(System.getProperty("user.dir"), "app/src/main/java/$relatif")
        )
        return kandidat.firstOrNull { it.exists() }
            ?: error("$relatif tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
    }

    /** `src/main/java` — akar seluruh kode produksi, bukan hanya paket `util`. */
    private fun akarSumber(): File =
        berkas("agu/analys/util/AlertNotificationHelper.kt").parentFile.parentFile.parentFile.parentFile

    /** Buang komentar supaya dokumentasi yang menyebut nama lama tidak ikut tertangkap. */
    private fun kodeTanpaKomentar(sumber: String): String =
        sumber
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `param onlyWhenBackground tidak boleh kembali`() {
        val kode = kodeTanpaKomentar(berkas("agu/analys/util/AlertNotificationHelper.kt").readText())

        assertFalse(
            "N8: `onlyWhenBackground` dulu diteruskan tetapi tidak pernah dibaca. Jangan tambahkan " +
                "lagi tanpa benar-benar memakainya (kalau fitur 'hanya saat background' dibutuhkan, " +
                "implementasikan di dalam fungsi + uji perilakunya).",
            kode.contains("onlyWhenBackground")
        )
    }

    @Test
    fun `helper generik sendPriceAlertNotification tidak boleh hidup lagi`() {
        val kode = kodeTanpaKomentar(berkas("agu/analys/util/AlertNotificationHelper.kt").readText())

        assertFalse(
            "N13: satu helper untuk dua makna adalah akar temuan ini. Pakai " +
                "`sendUserPriceAlertNotification` (alert harga pengguna) atau " +
                "`sendExecutionResultNotification` (hasil eksekusi).",
            kode.contains("fun sendPriceAlertNotification")
        )
    }

    @Test
    fun `tidak ada pemanggil yang masih memakai nama lama`() {
        val pelanggaran = akarSumber().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { kodeTanpaKomentar(it.readText()).contains("sendPriceAlertNotification") }
            .map { it.name }
            .toList()

        assertTrue(
            "N13: nama lama masih dipakai di $pelanggaran — pindahkan ke helper yang sesuai kategorinya",
            pelanggaran.isEmpty()
        )
    }

    /**
     * N12 — cooldown kandidat harus benar-benar dipasang di jalur kirim.
     *
     * Notifikasi dengan ID sama hanya menimpa entri di shade, jadi "jumlah notifikasi tampil" tidak
     * bisa membedakan satu kiriman dari dua. Yang dijaga di sini: helper kandidat **memanggil**
     * penjaga berstatus itu, bukan sekadar memilikinya.
     */
    @Test
    fun `helper kandidat memakai cooldown per pair`() {
        val kode = kodeTanpaKomentar(berkas("agu/analys/util/AlertNotificationHelper.kt").readText())
        val badan = kode.substringAfter("fun sendCandidateFoundNotification(", "")
            .substringAfter("fun sendCandidateFoundNotification(")

        assertTrue(
            "N12: `sendCandidateFoundNotification` harus memanggil `candidateCooldown.allow(...)` " +
                "sebelum mengirim, supaya state yang flapping tidak membunyikan notifikasi berulang.",
            badan.contains("candidateCooldown.allow(")
        )
    }

    /**
     * N14 — satu peristiwa, satu pengiriman.
     *
     * "Trailing Stop Naik" dikirim dari dua tempat sebelum perbaikan: `updateSimTrailingOrder` /
     * `updateRealTrailingOrder` (tanpa pembatas) **dan** jalur peak ber-jendela 30 detik. Kalau
     * jumlah pengirim bertambah lagi, cooldown otomatis tidak berlaku lagi — persis gejala N14.
     */
    @Test
    fun `notifikasi peak trailing hanya punya satu pengirim`() {
        val kode = kodeTanpaKomentar(berkas("agu/analys/viewmodel/TradingViewModelAlerts.kt").readText())
        val jumlah = Regex("sendTrailingPeakUpdateNotification").findAll(kode).count()

        assertTrue(
            "N14: `sendTrailingPeakUpdateNotification` dipanggil $jumlah kali di TradingViewModelAlerts.kt. " +
                "Harus tepat 1 — pembaruan order tidak boleh mengirim notifikasi sendiri.",
            jumlah == 1
        )
    }
}
