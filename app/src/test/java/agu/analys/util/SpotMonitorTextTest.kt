package agu.analys.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3 — notifikasi persisten "Spot Monitor" (`TradingForegroundService`) memakai angka **net**.
 *
 * Sebelum perbaikan 2026-10-31 kartu menghitung `(currentPrice − entryPrice) / entryPrice`
 * (bruto), sehingga aset yang masih merugi net (mis. entry 1.000 → 1.005: bruto +0,50%,
 * net −0,32%) tampil sebagai untung dan berlabel "SIAP JUAL", berbeda dari
 * `ActiveHoldingSection` di dashboard.
 *
 * Kontrol negatif: mengganti `isProfit` menjadi `currentPrice > entryPrice` (atau memakai persen
 * bruto) harus membuat uji `aset yang masih rugi net` GAGAL.
 */
class SpotMonitorTextTest {

    private fun holding(
        baseAsset: String = "BTC",
        entryPrice: Double,
        currentPrice: Double,
        quoteAsset: String = "IDR",
        exchange: String = "TOKOCRYPTO"
    ) = SpotMonitorHolding(
        baseAsset = baseAsset,
        exchange = exchange,
        quoteAsset = quoteAsset,
        currentPrice = currentPrice,
        entryPrice = entryPrice,
        quantityLabel = "0,01 $baseAsset"
    )

    @Test
    fun `aset yang masih rugi net tidak dilabeli siap jual`() {
        // Entry 1.000 → 1.005: bruto +0,50%, net −0,32%.
        val item = holding(entryPrice = 1_000.0, currentPrice = 1_005.0)

        assertEquals(-0.3160, item.netProfitPct, 1e-3)
        assertFalse("net negatif tidak boleh dianggap untung", item.isProfit)

        val kartu = buildSpotMonitorHoldingCard(item)
        assertTrue("kartu harus berlabel HOLD, isi: $kartu", kartu.contains("[HOLD]"))
        assertFalse("kartu tidak boleh menjanjikan siap jual, isi: $kartu", kartu.contains("[SIAP JUAL]"))
        assertFalse("angka bruto +0.50% tidak boleh muncul, isi: $kartu", kartu.contains("+0.50%"))
        assertTrue("kartu harus memuat net '-0.32%', isi: $kartu", kartu.contains("-0.32%"))
    }

    @Test
    fun `aset yang untung net diberi label siap jual dengan persen net`() {
        // Entry 1.000 → 1.050: bruto +5,00%, net +4,15%.
        val item = holding(entryPrice = 1_000.0, currentPrice = 1_050.0)

        assertEquals(4.147456, item.netProfitPct, 1e-6)
        assertTrue(item.isProfit)

        val kartu = buildSpotMonitorHoldingCard(item)
        assertTrue("kartu harus berlabel SIAP JUAL, isi: $kartu", kartu.contains("[SIAP JUAL]"))
        assertTrue("persen harus net '+4.15%', isi: $kartu", kartu.contains("+4.15%"))
        assertFalse("angka bruto +5.00% tidak boleh muncul, isi: $kartu", kartu.contains("+5.00%"))
    }

    @Test
    fun `tanpa harga masuk aset tidak diklaim untung`() {
        val item = holding(entryPrice = 0.0, currentPrice = 1_050.0)
        assertEquals(0.0, item.netProfitPct, 0.0)
        assertFalse("tanpa biaya perolehan tidak ada dasar mengklaim untung", item.isProfit)
        assertTrue(buildSpotMonitorHoldingCard(item).contains("[HOLD]"))
    }

    @Test
    fun `judul dan teks ringkas menghitung untung secara net`() {
        val untung = holding(baseAsset = "BTC", entryPrice = 1_000.0, currentPrice = 1_050.0)
        val rugi = holding(baseAsset = "ETH", entryPrice = 1_000.0, currentPrice = 1_005.0)

        assertEquals("⚡ 1 Aset Siap Profit • Spot Monitor", buildSpotMonitorTitle(listOf(untung, rugi)))
        assertEquals("📈 Spot Monitor • 2 Aset Aktif",
            buildSpotMonitorTitle(listOf(rugi, holding(baseAsset = "SOL", entryPrice = 2_000.0, currentPrice = 2_001.0))))
        assertEquals("📈 Spot Monitor • Menunggu Posisi", buildSpotMonitorTitle(emptyList()))

        val ringkas = buildSpotMonitorCollapsedText(listOf(untung, rugi))
        assertTrue("teks ringkas harus menyebut Siap Jual, isi: $ringkas", ringkas.startsWith("Siap Jual: "))
        assertTrue("hanya aset untung net yang masuk daftar, isi: $ringkas", ringkas.contains("BTC (+4.15%)"))
        assertFalse("aset rugi net tidak boleh masuk daftar siap jual, isi: $ringkas", ringkas.contains("ETH"))

        val pantau = buildSpotMonitorCollapsedText(listOf(rugi))
        assertTrue("tanpa aset untung net, teks ringkas hanya memantau, isi: $pantau", pantau.startsWith("Pantau: "))
        assertFalse(pantau.startsWith("Siap Jual:"))
    }

    @Test
    fun `badan notifikasi memisahkan portofolio real dan simulasi`() {
        val real = holding(baseAsset = "BTC", entryPrice = 1_000.0, currentPrice = 1_050.0)
        val sim = holding(baseAsset = "ETH", entryPrice = 1_000.0, currentPrice = 1_005.0)

        val badan = buildSpotMonitorBody(listOf(real), listOf(sim))
        assertTrue(badan.contains("💼 PORTOFOLIO REAL"))
        assertTrue(badan.contains("🧪 PORTOFOLIO SIMULASI"))
        assertTrue("hitung siap jual hanya dari net, isi: $badan", badan.contains("PORTOFOLIO REAL (1 Siap Jual)"))
        assertFalse("ETH rugi net tidak menambah hitungan siap jual, isi: $badan",
            badan.contains("PORTOFOLIO SIMULASI ("))

        val kosong = buildSpotMonitorBody(emptyList(), emptyList())
        assertTrue(kosong.startsWith("Belum ada koin yang dimiliki"))
    }
}
