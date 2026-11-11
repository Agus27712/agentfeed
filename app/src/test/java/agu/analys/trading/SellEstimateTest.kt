package agu.analys.trading

import agu.analys.config.FeeSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji J7 — radar jual wajib memakai basis biaya yang sama dengan FSM.
 *
 * Sebelum perbaikan, `RadarSellSection` menghitung sendiri:
 *
 * ```kotlin
 * val sellFeeIdr   = grossSellValueIdr * (activeFeePct / 100.0)   // hanya fee jual
 * val costBasisIdr = activeSellQty * effectiveBuyPrice            // TANPA fee beli
 * val netProfitIdr = netReceivedSellIdr - costBasisIdr            // tanpa slippage
 * ```
 *
 * Friksi Tokocrypto IDR taker yang benar-benar dipotong jadi 0,4322%, padahal FSM di halaman
 * yang sama memakai 0,8186%. Selisih 0,3864 pp itu membuat kartu menulis
 * "JUAL UNTUNG (PROFIT) … sudah bersih fee & pajak" pada kenaikan harga +0,50% — padahal net-nya
 * **rugi −0,3160%**. Uji ini mengunci basis yang benar.
 */
class SellEstimateTest {

    private val tokocryptoIdrBuyTaker = FeeSchedule.TOKOCRYPTO_IDR_BUY_TAKER * 100.0
    private val tokocryptoIdrSellTaker = FeeSchedule.TOKOCRYPTO_IDR_SELL_TAKER * 100.0
    private val tokocryptoIdrSellMaker = FeeSchedule.TOKOCRYPTO_IDR_SELL_MAKER * 100.0
    private val tokocryptoUsdtTaker = FeeSchedule.TOKOCRYPTO_USDT_BUY_TAKER * 100.0
    private val slippage = FeeSchedule.DEFAULT_SLIPPAGE_PCT

    private fun tokocryptoIdr(
        entry: Double,
        price: Double,
        qty: Double = 1.0,
        sellFeePct: Double = tokocryptoIdrSellTaker
    ) = SellEstimateResolver.resolve(
        quantity = qty,
        entryPrice = entry,
        sellPrice = price,
        buyFeePct = tokocryptoIdrBuyTaker,
        sellFeePct = sellFeePct,
        slippagePct = slippage
    )

    // ── 1. Inti J7: klaim yang dulu salah ────────────────────────────────────────

    @Test
    fun `pada kenaikan 0,50 persen kartu tidak lagi mengklaim untung`() {
        val entry = 100.0
        val price = 100.5

        // Rumus LAMA, ditulis ulang di sini sebagai dokumentasi bug: hanya fee jual yang
        // dipotong, dan ia menyimpulkan UNTUNG.
        val rumusLamaPct = ((price * (1.0 - tokocryptoIdrSellTaker / 100.0)) - entry) / entry * 100.0
        assertTrue("rumus lama memang mengklaim untung: $rumusLamaPct", rumusLamaPct > 0.0)

        val hasil = tokocryptoIdr(entry, price)

        assertFalse("+0,50% belum menutup friksi 0,8186%", hasil.isProfitable)
        assertEquals(-0.316006, hasil.netProfitPct, 1e-6)
    }

    @Test
    fun `klaim untung baru muncul setelah harga melewati harga impas`() {
        val entry = 100.0
        val impas = FeeSchedule.breakevenPrice(
            entry = entry,
            buyFeePct = tokocryptoIdrBuyTaker,
            sellFeePct = tokocryptoIdrSellTaker,
            slippagePct = slippage
        )

        // Sedikit di bawah impas masih rugi; sedikit di atas sudah untung.
        assertFalse(tokocryptoIdr(entry, impas * 0.999).isProfitable)
        assertTrue(tokocryptoIdr(entry, impas * 1.001).isProfitable)
        assertEquals(0.0, tokocryptoIdr(entry, impas).netProfitPct, 1e-9)
    }

    @Test
    fun `beli lalu jual di harga sama rugi sebesar friksi round-trip`() {
        val hasil = tokocryptoIdr(100.0, 100.0)

        assertEquals(-0.811946, hasil.netProfitPct, 1e-6)
        assertTrue(hasil.netProceeds < hasil.costBasisNet)
    }

    // ── 2. Paritas dengan FeeSchedule (satu rumus P&L) ───────────────────────────

    @Test
    fun `netProfitPct sama persis dengan FeeSchedule untuk seluruh rentang harga`() {
        val entry = 1_840_000.0
        for (grossPct in listOf(-3.0, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0, 5.0)) {
            val price = entry * (1.0 + grossPct / 100.0)
            val dariResolver = tokocryptoIdr(entry, price).netProfitPct
            val dariFeeSchedule = FeeSchedule.netProfitPct(
                entry = entry,
                price = price,
                buyFeePct = tokocryptoIdrBuyTaker,
                sellFeePct = tokocryptoIdrSellTaker,
                slippagePct = slippage
            )
            assertEquals("bruto $grossPct% harus identik", dariFeeSchedule, dariResolver, 1e-9)
        }
    }

    @Test
    fun `persen dari rupiah sama dengan persen langsung`() {
        // Invarian anti-drift: angka persen dan angka rupiah di kartu tidak boleh berasal dari
        // basis biaya yang berbeda.
        val hasil = tokocryptoIdr(1_840_000.0, 1_905_000.0, qty = 0.0027)

        val pctDariRupiah = (hasil.netProceeds / hasil.costBasisNet - 1.0) * 100.0
        assertEquals(pctDariRupiah, hasil.netProfitPct, 1e-9)
    }

    @Test
    fun `rupiah konsisten dengan komponen friksi`() {
        val hasil = tokocryptoIdr(100.0, 100.5)

        assertEquals(100.5, hasil.grossSellValue, 1e-9)
        assertEquals(0.434361, hasil.sellFeeAmount, 1e-9)
        assertEquals(0.0804, hasil.sellSlippageAmount, 1e-9)
        assertEquals(0.2222, hasil.buyFeeAmount, 1e-9)
        assertEquals(0.08, hasil.buySlippageAmount, 1e-9)

        // Kas bersih = bruto dikurangi friksi sisi jual.
        assertEquals(
            hasil.grossSellValue - hasil.sellSideFrictionAmount,
            hasil.netProceeds,
            1e-9
        )
        // Modal bersih = nominal ditambah friksi sisi beli.
        assertEquals(
            hasil.quantity * hasil.entryPrice + hasil.buyFeeAmount + hasil.buySlippageAmount,
            hasil.costBasisNet,
            1e-9
        )
        assertEquals(-0.316961, hasil.netProfit, 1e-9)
    }

    @Test
    fun `modal bersih selalu di atas nominal karena fee beli dan slippage`() {
        val hasil = tokocryptoIdr(1_000_000.0, 1_000_000.0, qty = 2.0)

        assertTrue(hasil.costBasisNet > hasil.quantity * hasil.entryPrice)
        assertEquals(0.3022, (hasil.costBasisNet / (hasil.quantity * hasil.entryPrice) - 1.0) * 100.0, 1e-4)
    }

    // ── 3. Tarif per kuotasi dan per jenis order ─────────────────────────────────

    @Test
    fun `friksi Tokocrypto USDT memakai tarifnya sendiri`() {
        val hasil = SellEstimateResolver.resolve(
            quantity = 1.0,
            entryPrice = 100.0,
            sellPrice = 100.5,
            buyFeePct = tokocryptoUsdtTaker,
            sellFeePct = tokocryptoUsdtTaker,
            slippagePct = slippage
        )

        // USDT: 0,4044% dua sisi + slippage -> impas 0,9735%, jadi +0,50% masih rugi.
        assertEquals(-0.468950, hasil.netProfitPct, 1e-6)
        assertFalse(hasil.isProfitable)
    }

    @Test
    fun `mode maker memakai fee jual maker tetapi fee beli tetap taker`() {
        val maker = tokocryptoIdr(100.0, 100.5, sellFeePct = tokocryptoIdrSellMaker)
        val taker = tokocryptoIdr(100.0, 100.5, sellFeePct = tokocryptoIdrSellTaker)

        // Kaki beli tidak ikut murah: order beli sudah terjadi dan jenisnya tidak diketahui.
        assertEquals(taker.buyFeeAmount, maker.buyFeeAmount, 1e-12)
        assertEquals(0.3322, maker.sellFeePct, 1e-12)

        // Friksi maker 0,7174% -> lebih ringan dari taker 0,8186%, tapi tetap rugi di +0,50%.
        assertEquals(-0.215809, maker.netProfitPct, 1e-6)
        assertFalse(maker.isProfitable)
        assertTrue(maker.netProfitPct > taker.netProfitPct)
    }

    @Test
    fun `slippage dapat dimatikan dan friksinya ikut turun`() {
        val denganSlippage = tokocryptoIdr(100.0, 100.5)
        val tanpaSlippage = SellEstimateResolver.resolve(
            quantity = 1.0,
            entryPrice = 100.0,
            sellPrice = 100.5,
            buyFeePct = tokocryptoIdrBuyTaker,
            sellFeePct = tokocryptoIdrSellTaker,
            slippagePct = 0.0
        )

        assertEquals(0.0, tanpaSlippage.sellSlippageAmount, 1e-12)
        assertEquals(0.0, tanpaSlippage.buySlippageAmount, 1e-12)
        assertEquals(-0.156214, tanpaSlippage.netProfitPct, 1e-6)
        assertTrue(tanpaSlippage.netProfitPct > denganSlippage.netProfitPct)
    }

    // ── 4. Penjagaan masukan tidak valid ────────────────────────────────────────

    @Test
    fun `tanpa modal yang diketahui laba tidak dikarang`() {
        val hasil = tokocryptoIdr(0.0, 100.5)

        assertFalse(hasil.hasBasis)
        assertEquals(0.0, hasil.netProfit, 0.0)
        assertEquals(0.0, hasil.netProfitPct, 0.0)
        assertFalse(hasil.isProfitable)
        // Kas bersih tetap dihitung — sisi jual tidak butuh modal.
        assertTrue(hasil.netProceeds > 0.0)
        assertEquals(hasil.sellSideFrictionAmount, hasil.frictionAmount, 1e-12)
    }

    @Test
    fun `harga jual nol menghasilkan kas nol dan rugi penuh`() {
        val hasil = tokocryptoIdr(100.0, 0.0)

        assertEquals(0.0, hasil.grossSellValue, 0.0)
        assertEquals(0.0, hasil.netProceeds, 0.0)
        assertEquals(-100.0, hasil.netProfitPct, 1e-9)
        assertFalse(hasil.isProfitable)
    }

    @Test
    fun `masukan tidak valid tidak menghasilkan NaN atau melempar`() {
        for (nilai in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val hasil = SellEstimateResolver.resolve(
                quantity = nilai,
                entryPrice = nilai,
                sellPrice = nilai,
                buyFeePct = nilai,
                sellFeePct = nilai,
                slippagePct = nilai
            )
            assertTrue("quantity $nilai", hasil.quantity.isFinite())
            assertTrue("netProfitPct $nilai", hasil.netProfitPct.isFinite())
            assertTrue("frictionAmount $nilai", hasil.frictionAmount.isFinite())
            assertTrue("quantity tidak negatif", hasil.quantity >= 0.0)
        }
    }

    // ── 5. Teks kartu ikut jujur ────────────────────────────────────────────────

    @Test
    fun `label friksi menyebut fee beli dan slippage saat modal diketahui`() {
        val label = frictionBreakdownLabel(
            buyFeePct = tokocryptoIdrBuyTaker,
            sellFeePct = tokocryptoIdrSellTaker,
            slippagePct = slippage,
            hasBasis = true,
            sellIsMaker = false
        )

        assertTrue(label, label.contains("Beli taker 0.22%"))
        assertTrue(label, label.contains("Jual taker 0.43%"))
        assertTrue(label, label.contains("Slippage 2×0.08%"))
    }

    @Test
    fun `label friksi jujur mengakui biaya beli belum diketahui`() {
        val label = frictionBreakdownLabel(
            buyFeePct = tokocryptoIdrBuyTaker,
            sellFeePct = tokocryptoIdrSellMaker,
            slippagePct = slippage,
            hasBasis = false,
            sellIsMaker = true
        )

        assertTrue(label, label.contains("belum diketahui"))
        assertFalse("tidak boleh mengaku memotong fee beli", label.contains("Beli taker"))
        assertTrue(label, label.contains("Jual maker 0.33%"))
    }

    @Test
    fun `klaim untung tidak lagi memakai frasa sudah bersih`() {
        for (untung in listOf(true, false)) {
            val catatan = profitBasisNote(untung)
            assertFalse(catatan, catatan.contains("sudah bersih"))
            assertTrue(catatan, catatan.contains("biaya beli"))
            assertTrue(catatan, catatan.contains("slippage"))
        }
    }
}
