package agu.analys.util

import agu.analys.model.MarketKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dasar angka P&L untuk semua teks notifikasi (H1, H2, H3): **net** setelah fee beli + fee jual
 * + slippage dua sisi, sama persis dengan halaman detail (`SellEstimate.netProfitPct`,
 * `ActiveHoldingSection`).
 *
 * Kontrol negatif dicatat di KDoc tiap uji: mengembalikan rumus gross di pembentuk pesan harus
 * membuat uji ini GAGAL.
 */
class NotificationNetPnlTest {

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

    private val keyTokoIdr = MarketKey.resolve("BTCIDR", "TOKOCRYPTO", "IDR")

    // Tarif taker Tokocrypto IDR (bukan preset legacy): beli 0,2222% / jual 0,4322%, slippage 0,08%.
    private val netDiHargaSama = -0.811946  // impas net = kerugian sebesar round-trip friksi

    @Test
    fun `net profit pct sama dengan rumus FeeSchedule`() {
        val hasil = trailingHitNetProfitPct(keyTokoIdr, entryPrice = 1_000_000.0, limitSellPrice = 1_000_000.0)
        assertEquals("impas net harus negatif setara round-trip friksi", netDiHargaSama, hasil, 1e-6)
    }

    @Test
    fun `notifikasi trailing hit menampilkan net, bukan gross`() {
        // Entry 1.000.000 → batas jual 1.050.000: gross +5,00%, net +4,15%.
        val pesan = buildTrailingHitMessage(keyTokoIdr, entryPrice = 1_000_000.0, peakPrice = 1_060_000.0,
            currentPrice = 1_040_000.0, limitSellPrice = 1_050_000.0)

        assertTrue("pesan harus memuat estimasi profit NET '+4.15%', isi: $pesan", pesan.contains("+4.15%"))
        assertFalse("N1/H1: '5.00' adalah gejala gross, jangan muncul di pesan, isi: $pesan",
            pesan.contains("+5.00%"))
        assertTrue("pesan harus menyebut basis net", pesan.lowercase().contains("net"))
    }

    @Test
    fun `notifikasi trailing hit di bawah breakeven menampilkan net negatif`() {
        // Entry 1.000.000 → batas jual 1.002.000: gross +0,20%, net −0,61%.
        val pesan = buildTrailingHitMessage(keyTokoIdr, entryPrice = 1_000_000.0, peakPrice = 1_010_000.0,
            currentPrice = 1_001_000.0, limitSellPrice = 1_002_000.0)
        assertTrue("net harus negatif karena di bawah harga impas, isi: $pesan", pesan.contains("-0.61%"))
        assertFalse("gross +0,20% tidak boleh muncul sebagai klaim profit, isi: $pesan",
            pesan.contains("+0.20%"))
    }

    @Test
    fun `notifikasi peak update menampilkan net puncak, bukan gross`() {
        // Entry 1.000 → puncak 1.040: gross +4,00%, net +3,16%.
        val pesan = buildTrailingPeakUpdateMessage(keyTokoIdr, newPeak = 1_040.0,
            stopLimitPrice = 1_020.0, entryPrice = 1_000.0)

        assertTrue("pesan harus memuat profit puncak NET '+3.16%', isi: $pesan", pesan.contains("+3.16%"))
        assertFalse("H2: '4.00' adalah gejala gross dari pemanggil, isi: $pesan",
            pesan.contains("+4.00%"))
        assertTrue("pesan harus menyebut basis net", pesan.lowercase().contains("net"))
    }

    @Test
    fun `jalur notifikasi dilarang menghitung persen profit secara gross di call site`() {
        val pelanggaran = mutableListOf<String>()
        val berkasKandidat = listOf(
            "agu/analys/util/AlertNotificationHelper.kt",
            "agu/analys/viewmodel/TradingViewModelAlerts.kt",
            "agu/analys/service/TradingForegroundService.kt"
        )
        for (relatif in berkasKandidat) {
            val kode = kodeTanpaKomentar(berkas(relatif).readText())
            val grossFormula = Regex("""- entryPrice\) / entryPrice""")
            // `netProfitPct: Double` (jalur take-profit) sah; yang dilarang parameter `profitPct` polos.
            val legacyParam = Regex("""(?<![A-Za-z])profitPct: Double(,|\s|$)""")
            if (grossFormula.containsMatchIn(kode) || legacyParam.containsMatchIn(kode)) {
                pelanggaran.add(relatif)
            }
        }
        assertTrue("jalur notifikasi tidak boleh mencari tahu persen profit secara gross: $pelanggaran",
            pelanggaran.isEmpty())
    }
}
