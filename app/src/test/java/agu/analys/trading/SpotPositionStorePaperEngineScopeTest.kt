package agu.analys.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * P1 — enumerasi posisi paper di [SpotPositionStore].
 *
 * [SpotPositionStore.getPaperEnginePositions] harus menemukan seluruh posisi paper yang ditandai,
 * dan hasilnya bisa langsung dipakai untuk membaca kembali posisinya.
 */
@RunWith(RobolectricTestRunner::class)
class SpotPositionStorePaperEngineScopeTest {

    private fun newStore(): SpotPositionStore =
        SpotPositionStore(RuntimeEnvironment.getApplication())

    private fun bukaPosisi(
        store: SpotPositionStore,
        symbol: String,
        exchange: String,
        owned: Boolean = true
    ) {
        store.markBought(symbol = symbol, entryPrice = 100.0, invested = 100.0, quantity = 1.0, exchange = exchange)
        store.setPaperEngineOwned(symbol = symbol, isReal = false, exchange = exchange, owned = owned)
    }

    @Test
    fun `posisi tanpa penanda paper tidak ikut terhitung`() {
        val store = newStore()
        bukaPosisi(store, "SOLIDR", "TOKOCRYPTO", owned = false)

        assertTrue(store.getPaperEnginePositions().isEmpty())
    }

    @Test
    fun `posisi yang sudah tidak HOLDING tidak ikut terhitung`() {
        val store = newStore()
        bukaPosisi(store, "XRPIDR", "TOKOCRYPTO")
        store.markSold("XRPIDR", exchange = "TOKOCRYPTO")

        assertTrue(store.getPaperEnginePositions().isEmpty())
    }

    @Test
    fun `penanda real tidak tercampur dengan simulasi`() {
        val store = newStore()
        store.markBought(symbol = "ADAIDR", entryPrice = 100.0, invested = 100.0, quantity = 1.0, isReal = true, exchange = "TOKOCRYPTO")
        store.setPaperEngineOwned(symbol = "ADAIDR", isReal = true, exchange = "TOKOCRYPTO", owned = true)
        bukaPosisi(store, "ADAIDR", "TOKOCRYPTO")

        assertEquals(
            "hanya partisi simulasi yang boleh muncul",
            listOf(PaperEnginePosition("TOKOCRYPTO", "ADAIDR")),
            store.getPaperEnginePositions(isReal = false)
        )
        assertEquals(
            listOf(PaperEnginePosition("TOKOCRYPTO", "ADAIDR")),
            store.getPaperEnginePositions(isReal = true)
        )
    }

    @Test
    fun `simbol hasil enumerasi bisa langsung dipakai untuk membaca posisinya`() {
        val store = newStore()
        bukaPosisi(store, "DOTIDR", "TOKOCRYPTO")

        val pos = store.getPaperEnginePositions().single()

        assertTrue(
            "simbol harus kanonik agar get() menemukan posisinya",
            store.get(pos.symbol, isReal = false, exchange = pos.exchange).isHolding
        )
    }
}
