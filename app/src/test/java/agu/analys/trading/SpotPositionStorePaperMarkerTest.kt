package agu.analys.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Uji persistensi yang menjadi dasar rehidrasi posisi paper (P2).
 *
 * Dua hal yang dijaga:
 * 1. `setAutoSellParams(stopLossPrice = ...)` benar-benar menimpa SL sintetis
 *    `entry * 0.99` yang ditulis `setHolding`.
 * 2. Penanda `_paper_engine` bertahan dan bisa dienumerasi — satu-satunya cara mengenali
 *    posisi yang exit-nya dipegang engine paper setelah app restart.
 */
@RunWith(RobolectricTestRunner::class)
class SpotPositionStorePaperMarkerTest {

    private val ex = "TOKOCRYPTO"

    private fun newStore(): SpotPositionStore =
        SpotPositionStore(RuntimeEnvironment.getApplication())

    @Test
    fun `setAutoSellParams menimpa SL sintetis dengan SL asli sinyal`() {
        val store = newStore()
        store.markBought(symbol = "BTCUSDT", entryPrice = 100.0, invested = 100.0, quantity = 1.0, exchange = ex)

        // SL sintetis dari setHolding.
        assertEquals(99.0, store.get("BTCUSDT", exchange = ex).stopLossPrice, 1e-9)

        store.setAutoSellParams(
            symbol = "BTCUSDT",
            enabled = false,
            tp1Price = 104.0,
            tp1Percent = 50.0,
            tp2Price = 108.0,
            tp2Percent = 50.0,
            exchange = ex,
            stopLossPrice = 94.0
        )

        assertEquals(94.0, store.get("BTCUSDT", exchange = ex).stopLossPrice, 1e-9)
    }

    @Test
    fun `stopLossPrice nol mempertahankan perilaku lama`() {
        val store = newStore()
        store.markBought(symbol = "ETHUSDT", entryPrice = 200.0, invested = 200.0, quantity = 1.0, exchange = ex)

        store.setAutoSellParams(
            symbol = "ETHUSDT",
            enabled = true,
            tp1Price = 0.0,
            tp1Percent = 50.0,
            tp2Price = 0.0,
            tp2Percent = 50.0,
            exchange = ex
        )

        // Tidak ditimpa -> tetap SL sintetis dari setHolding.
        assertEquals(198.0, store.get("ETHUSDT", exchange = ex).stopLossPrice, 1e-9)
    }

    @Test
    fun `penanda paper engine bisa dibaca kembali`() {
        val store = newStore()
        store.markBought(symbol = "SOLUSDT", entryPrice = 150.0, invested = 150.0, quantity = 1.0, exchange = ex)

        assertFalse(store.isPaperEngineOwned("SOLUSDT", exchange = ex))
        store.setPaperEngineOwned("SOLUSDT", exchange = ex, owned = true)
        assertTrue(store.isPaperEngineOwned("SOLUSDT", exchange = ex))
    }

    @Test
    fun `getPaperEngineSymbols hanya mengembalikan posisi bertanda dan masih HOLDING`() {
        val store = newStore()
        store.markBought(symbol = "APTUSDT", entryPrice = 10.0, invested = 10.0, quantity = 1.0, exchange = ex)
        store.markBought(symbol = "NEARUSDT", entryPrice = 5.0, invested = 5.0, quantity = 1.0, exchange = ex)

        store.setPaperEngineOwned("APTUSDT", exchange = ex, owned = true)

        // NEARUSDT tidak ditandai -> bukan urusan engine paper.
        assertEquals(listOf("APTUSDT"), store.getPaperEngineSymbols(exchange = ex))
    }

    @Test
    fun `posisi bertanda yang sudah tidak HOLDING tidak ikut terenumerasi`() {
        val store = newStore()
        store.markBought(symbol = "SUIUSDT", entryPrice = 3.0, invested = 3.0, quantity = 1.0, exchange = ex)
        store.setPaperEngineOwned("SUIUSDT", exchange = ex, owned = true)
        assertEquals(listOf("SUIUSDT"), store.getPaperEngineSymbols(exchange = ex))

        store.markSold("SUIUSDT", exchange = ex)
        assertTrue(store.getPaperEngineSymbols(exchange = ex).isEmpty())
    }

    @Test
    fun `markSold membersihkan penanda paper engine`() {
        val store = newStore()
        store.markBought(symbol = "WUSDT", entryPrice = 1.0, invested = 1.0, quantity = 1.0, exchange = ex)
        store.setPaperEngineOwned("WUSDT", exchange = ex, owned = true)
        store.markSold("WUSDT", exchange = ex)
        assertFalse(store.isPaperEngineOwned("WUSDT", exchange = ex))
    }

    @Test
    fun `melepas penanda membuat simbol keluar dari enumerasi`() {
        val store = newStore()
        store.markBought(symbol = "ZROUSDT", entryPrice = 2.0, invested = 2.0, quantity = 1.0, exchange = ex)
        store.setPaperEngineOwned("ZROUSDT", exchange = ex, owned = true)
        store.setPaperEngineOwned("ZROUSDT", exchange = ex, owned = false)
        assertTrue(store.getPaperEngineSymbols(exchange = ex).isEmpty())
    }
}
