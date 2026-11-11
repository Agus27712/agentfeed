package agu.analys.trading

import agu.analys.config.FeeSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J8 — P&L penjualan uang riil memakai **satu rumus** (`FeeSchedule`), termasuk slippage.
 *
 * Rumus lama (`TradingViewModelSync.kt:56-65`) tidak memasukkan slippage, sehingga jurnal trade
 * real berbeda ≈0,16 pp dari portofolio/radar jual untuk trade yang sama.
 */
class RealTradePnlTest {

    private val eps = 1e-9

    @Test
    fun `tokocrypto IDR sama persis dengan FeeSchedule`() {
        val p = RealTradePnlResolver.resolve(
            quantity = 1.0, entry = 100_000_000.0, exitPrice = 102_000_000.0,
            exchange = "TOKOCRYPTO", quoteAsset = "IDR"
        )
        val expected = FeeSchedule.netProfitPctFor(100_000_000.0, 102_000_000.0, "TOKOCRYPTO", "IDR")
        assertEquals(expected, p.pnlPct, eps)
    }

    @Test
    fun `tokocrypto USDT sama persis dengan FeeSchedule`() {
        val p = RealTradePnlResolver.resolve(
            quantity = 2.5, entry = 60_000.0, exitPrice = 61_200.0,
            exchange = "TOKOCRYPTO", quoteAsset = "USDT"
        )
        val expected = FeeSchedule.netProfitPctFor(60_000.0, 61_200.0, "TOKOCRYPTO", "USDT")
        assertEquals(expected, p.pnlPct, eps)
    }

    @Test
    fun `slippage ikut dihitung - bukan rumus tanpa slippage`() {
        // rumus lama: (exit*(1-sellFee)) / (entry*(1+buyFee)) - 1  -- tanpa slippage
        val entry = 100_000_000.0
        val exit = 101_000_000.0
        val buyFee = FeeSchedule.buyRate("TOKOCRYPTO", "IDR")
        val sellFee = FeeSchedule.sellRate("TOKOCRYPTO", "IDR")
        val oldPct = (((exit * (1.0 - sellFee)) / (entry * (1.0 + buyFee))) - 1.0) * 100.0

        val p = RealTradePnlResolver.resolve(
            quantity = 1.0, entry = entry, exitPrice = exit,
            exchange = "TOKOCRYPTO", quoteAsset = "IDR"
        )
        assertTrue("angka baru harus lebih rendah karena slippage dua sisi", p.pnlPct < oldPct)
        assertEquals(0.16, oldPct - p.pnlPct, 0.01)
    }

    @Test
    fun `rupiah konsisten dengan persen`() {
        val p = RealTradePnlResolver.resolve(
            quantity = 4.0, entry = 250_000_000.0, exitPrice = 260_000_000.0,
            exchange = "TOKOCRYPTO", quoteAsset = "IDR"
        )
        val cost = FeeSchedule.costBasisNet(
            4.0, 250_000_000.0, FeeSchedule.buyRate("TOKOCRYPTO", "IDR") * 100.0
        )
        assertEquals(cost * p.pnlPct / 100.0, p.pnlIdr, 1e-3)
    }

    @Test
    fun `rugi menghasilkan angka negatif`() {
        val p = RealTradePnlResolver.resolve(
            quantity = 1.0, entry = 100_000_000.0, exitPrice = 95_000_000.0,
            exchange = "TOKOCRYPTO", quoteAsset = "IDR"
        )
        assertTrue(p.pnlPct < 0.0)
        assertTrue(p.pnlIdr < 0.0)
    }

    @Test
    fun `harga jual sama dengan entry tetap rugi karena friksi`() {
        val p = RealTradePnlResolver.resolve(
            quantity = 1.0, entry = 100_000_000.0, exitPrice = 100_000_000.0,
            exchange = "TOKOCRYPTO", quoteAsset = "IDR"
        )
        assertTrue("impas harga bukan impas net", p.pnlPct < 0.0)
    }

    @Test
    fun `harga masuk nol menghasilkan nol`() {
        val p = RealTradePnlResolver.resolve(
            quantity = 1.0, entry = 0.0, exitPrice = 100_000_000.0,
            exchange = "TOKOCRYPTO", quoteAsset = "IDR"
        )
        assertEquals(RealTradePnl.ZERO, p)
    }

    @Test
    fun `harga jual nol menghasilkan nol`() {
        val p = RealTradePnlResolver.resolve(
            quantity = 1.0, entry = 100_000_000.0, exitPrice = 0.0,
            exchange = "TOKOCRYPTO", quoteAsset = "IDR"
        )
        assertEquals(RealTradePnl.ZERO, p)
    }

    @Test
    fun `kuantitas nol menghasilkan nol`() {
        val p = RealTradePnlResolver.resolve(
            quantity = 0.0, entry = 100_000_000.0, exitPrice = 101_000_000.0,
            exchange = "TOKOCRYPTO", quoteAsset = "IDR"
        )
        assertEquals(RealTradePnl.ZERO, p)
    }

    @Test
    fun `rupiah berskala dengan kuantitas`() {
        val satu = RealTradePnlResolver.resolve(
            1.0, 100_000_000.0, 102_000_000.0, "TOKOCRYPTO", "IDR"
        )
        val dua = RealTradePnlResolver.resolve(
            2.0, 100_000_000.0, 102_000_000.0, "TOKOCRYPTO", "IDR"
        )
        assertEquals(satu.pnlPct, dua.pnlPct, eps)
        assertEquals(satu.pnlIdr * 2.0, dua.pnlIdr, 1e-3)
    }

    @Test
    fun `taker dan maker berbeda`() {
        val taker = RealTradePnlResolver.resolve(
            1.0, 100_000_000.0, 102_000_000.0, "TOKOCRYPTO", "IDR", useMaker = false
        )
        val maker = RealTradePnlResolver.resolve(
            1.0, 100_000_000.0, 102_000_000.0, "TOKOCRYPTO", "IDR", useMaker = true
        )
        assertTrue(maker.pnlPct > taker.pnlPct)
    }
}
