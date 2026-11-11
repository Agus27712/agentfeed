package agu.analys.trading

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **S5 — seragamkan basis PnL.**
 *
 * Laporan audit dulu memakai PnL **fee-only** sementara mesin exit memakai **fee + 2× slippage**
 * (beda 0,16 pp per trade di Tokocrypto USDT: AMP +0,6889% vs +0,5289%). Slippage kini
 * diterapkan ke harga isi efektif order MARKET, sehingga kas, PnL laporan, dan basis mesin
 * exit memakai **satu** angka yang sama.
 */
@RunWith(RobolectricTestRunner::class)
class SimulationSlippageParityTest {

    private fun store(): SimulationTradeStore {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        return SimulationTradeStore(ctx).apply {
            resetWallet(100_000_000.0)
            topUpUsdt(10_000.0) // pair USDT butuh saldo USDT; konversi otomatis butuh kurs hidup
        }
    }

    private fun marketBuy(store: SimulationTradeStore, price: Double): Double? {
        val res = store.placeOrder(
            symbol = "AMPUSDT",
            baseAsset = "AMP",
            quoteAsset = "USDT",
            side = SimulationOrderSide.BUY,
            type = SimulationOrderType.MARKET,
            price = price,
            quantity = 10.0,
            currentMarketPrice = price
        )
        assertTrue("Order MARKET harus sukses: $res", res is SimulationOrderResult.Success)
        return (res as SimulationOrderResult.Success).order.filledAvgPrice.takeIf { it > 0.0 }
    }

    @Test
    fun marketBuy_tanpaSlippage_hargaIsiSamaDenganPasar() {
        val s = store() // slippagePct default 0.0
        assertEquals(100.0, marketBuy(s, 100.0)!!, 1e-9)
    }

    @Test
    fun marketBuy_denganSlippage_hargaIsiBergeser() {
        val s = store().apply { slippagePct = 0.08 }
        // Beli: harga isi lebih mahal 0,08% — basis yang sama dengan mesin exit.
        assertEquals(100.08, marketBuy(s, 100.0)!!, 1e-6)
    }

    @Test
    fun marketSell_denganSlippage_hargaIsiBergeserTurun() {
        val s = store().apply { slippagePct = 0.08 }
        marketBuy(s, 100.0)
        val res = s.placeOrder(
            symbol = "AMPUSDT",
            baseAsset = "AMP",
            quoteAsset = "USDT",
            side = SimulationOrderSide.SELL,
            type = SimulationOrderType.MARKET,
            price = 100.0,
            quantity = 10.0,
            currentMarketPrice = 100.0
        )
        assertTrue("Order SELL harus sukses: $res", res is SimulationOrderResult.Success)
        val sell = (res as SimulationOrderResult.Success).order
        // Jual: harga isi lebih murah 0,08%.
        assertEquals(99.92, sell.filledAvgPrice, 1e-6)
    }
}
