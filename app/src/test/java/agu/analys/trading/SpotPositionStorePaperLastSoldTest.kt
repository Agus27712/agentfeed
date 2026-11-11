package agu.analys.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Uji pencatatan waktu sell terakhir posisi paper (temuan 2026-10-26).
 *
 * `markSold(isReal = false)` wajib menulis waktu sell yang dibaca
 * `lastPaperSoldAt` — sumber jeda entri-ulang auto-buy. Posisi uang riil
 * tidak dicatat: jeda ini hanya untuk siklus paper.
 */
@RunWith(RobolectricTestRunner::class)
class SpotPositionStorePaperLastSoldTest {

    private val ex = "TOKOCRYPTO"
    private fun newStore(): SpotPositionStore =
        SpotPositionStore(RuntimeEnvironment.getApplication())

    @Test
    fun `markSold paper menulis waktu sell yang bisa dibaca kembali`() {
        val store = newStore()
        assertEquals(0L, store.lastPaperSoldAt("WINUSDT", exchange = ex))

        store.markBought("WINUSDT", entryPrice = 0.000064, invested = 64.0, quantity = 1.0, exchange = ex)
        store.markSold("WINUSDT", isReal = false, exchange = ex)

        val soldAt = store.lastPaperSoldAt("WINUSDT", exchange = ex)
        assertTrue("waktu sell harus positif, dapat $soldAt", soldAt > 0L)
    }

    @Test
    fun `markSold uang riil tidak menulis catatan jeda paper`() {
        val store = newStore()
        store.markBought("BTCUSDT", entryPrice = 100.0, invested = 100.0, quantity = 1.0, isReal = true, exchange = ex)
        store.markSold("BTCUSDT", isReal = true, exchange = ex)

        assertEquals(0L, store.lastPaperSoldAt("BTCUSDT", exchange = ex))
    }

    @Test
    fun `catatan terpisah antar simbol`() {
        val store = newStore()
        store.markBought("WINUSDT", entryPrice = 0.000064, invested = 64.0, quantity = 1.0, exchange = ex)
        store.markSold("WINUSDT", isReal = false, exchange = ex)

        assertTrue(store.lastPaperSoldAt("WINUSDT", exchange = ex) > 0L)
        assertEquals(0L, store.lastPaperSoldAt("ADAUSDT", exchange = ex))
    }

    @Test
    fun `tanpa posisi markSold tidak menulis catatan`() {
        val store = newStore()
        // Tidak ada posisi apa pun: markSold harus early-return tanpa menulis.
        store.markSold("XRPUSDT", isReal = false, exchange = ex)
        assertEquals(0L, store.lastPaperSoldAt("XRPUSDT", exchange = ex))
    }
}
