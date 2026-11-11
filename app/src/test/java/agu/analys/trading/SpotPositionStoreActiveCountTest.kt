package agu.analys.trading

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Uji hitungan posisi paper aktif untuk gerbang batas posisi auto-buy.
 *
 * Yang dijaga: hitungan harus **global** (lintas kuotasi), memakai **semua**
 * posisi paper, dan **tidak** ikut menghitung posisi uang riil. Keputusan owner: posisi yang
 * dibeli manual juga terhitung, karena tujuannya membatasi jumlah koin yang dipegang
 * bersamaan — bukan membatasi asal ordernya.
 */
@RunWith(RobolectricTestRunner::class)
class SpotPositionStoreActiveCountTest {

    private fun newStore(): SpotPositionStore =
        SpotPositionStore(RuntimeEnvironment.getApplication())

    @Test
    fun `menghitung semua posisi paper lintas kuotasi`() {
        val store = newStore()
        store.markBought(symbol = "BTCUSDT", entryPrice = 100.0, quantity = 1.0, exchange = "TOKOCRYPTO")
        store.markBought(symbol = "ETHUSDT", entryPrice = 200.0, quantity = 1.0, exchange = "TOKOCRYPTO")
        store.markBought(symbol = "BTCIDR", entryPrice = 1_000_000.0, quantity = 1.0, exchange = "TOKOCRYPTO")

        assertEquals(3, store.countActivePaperPositions())
    }

    @Test
    fun `posisi real tidak ikut dihitung`() {
        val store = newStore()
        store.markBought(
            symbol = "BTCUSDT",
            entryPrice = 100.0,
            quantity = 1.0,
            isReal = true,
            exchange = "TOKOCRYPTO"
        )
        store.markBought(symbol = "ETHUSDT", entryPrice = 200.0, quantity = 1.0, exchange = "TOKOCRYPTO")

        assertEquals(1, store.countActivePaperPositions())
    }

    @Test
    fun `posisi yang dijual keluar dari hitungan`() {
        val store = newStore()
        store.markBought(symbol = "BTCUSDT", entryPrice = 100.0, quantity = 1.0, exchange = "TOKOCRYPTO")
        store.markBought(symbol = "ETHUSDT", entryPrice = 200.0, quantity = 1.0, exchange = "TOKOCRYPTO")
        assertEquals(2, store.countActivePaperPositions())

        store.markSold(symbol = "BTCUSDT", exchange = "TOKOCRYPTO")

        assertEquals(1, store.countActivePaperPositions())
    }

    @Test
    fun `beli manual ikut dihitung`() {
        val store = newStore()
        // Beli manual tidak menulis penanda `_paper_engine`, tetapi tetap posisi paper
        // (isReal = false) sehingga wajib ikut terhitung.
        store.markBought(symbol = "SOLUSDT", entryPrice = 50.0, quantity = 2.0, exchange = "TOKOCRYPTO")

        assertEquals(1, store.countActivePaperPositions())
    }
}
