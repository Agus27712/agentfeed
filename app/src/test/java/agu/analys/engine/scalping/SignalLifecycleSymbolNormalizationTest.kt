package agu.analys.engine.scalping

import agu.analys.config.StrategyMode
import agu.analys.model.AISignalState
import agu.analys.model.LifecycleState
import agu.analys.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **N7** — `markTriggered` dan `reset` harus menormalkan simbol.
 *
 * Key di `activeSignals` dibangun lewat `cacheKey` = `"${normalizeSymbol(symbol)}#${mode}"`, dan
 * `normalizeSymbol` membuang pemisah `/ _ -`. Kedua fungsi itu dulu memakai
 * `"${symbol.uppercase()}#"` mentah, sehingga simbol berpemisah tidak pernah cocok:
 *
 * - `markTriggered("eth_idr")` → prefix `"ETH_IDR#"` vs key `"ETHIDR#SCALPING"` → **tidak cocok**,
 *   sinyal diam-diam tidak pernah berstatus TRIGGERED (efek sampingnya: siklus bisa langsung
 *   mendeteksi ulang → notifikasi berulang);
 * - `reset("eth_idr")` → tidak menghapus apa pun, state lama tertinggal di mesin.
 *
 * Ini **aktif**, bukan latent: jalur real trade masih bisa mengirim `pair` berpemisah `btc_idr`
 * (`RealTradeExecutor.baseFromPair` bahkan melakukan `.replace("_","")` defensif).
 *
 * Kontrol negatif: kembalikan `normalizeSymbol(symbol)` menjadi `symbol.uppercase()` di
 * `SignalLifecycleManager` → kedua uji ini GAGAL.
 */
class SignalLifecycleSymbolNormalizationTest {

    /** Simbol bersih (bentuk yang dipakai key) dan bentuk berpemisah (bentuk yang dikirim pemanggil). */
    private val bersih = "ETHIDR"
    private val berpemisah = "eth_idr"

    private val sinyalSiap = AISignalState(action = SignalAction.BUY, confidence = 80)

    @Before
    fun setUp() {
        SignalLifecycleManager.reset(bersih)
    }

    @Test
    fun `markTriggered mengenali simbol berpemisah`() {
        val t1 = SignalLifecycleManager.process(bersih, 50_000.0, sinyalSiap, StrategyMode.SWING)
        assertEquals("prasyarat: sinyal harus mencapai READY", LifecycleState.READY, t1.state)

        // Pemanggil mengirim format berdelimiter — seperti `RealTradeExecutor.markTriggered(pair)`.
        SignalLifecycleManager.markTriggered(berpemisah)

        val t2 = SignalLifecycleManager.process(bersih, 50_100.0, sinyalSiap, StrategyMode.SWING)
        assertEquals(
            "markTriggered harus mengenali simbol berpemisah dan menaikkan status ke TRIGGERED",
            LifecycleState.TRIGGERED,
            t2.state
        )
    }

    @Test
    fun `reset menghapus state walau simbol berpemisah`() {
        val t1 = SignalLifecycleManager.process(bersih, 50_000.0, sinyalSiap, StrategyMode.SWING)
        assertEquals(LifecycleState.READY, t1.state)

        SignalLifecycleManager.reset(berpemisah)

        // Bila reset gagal, state lama (READY) bertahan dan transisi READY tidak terpicu ulang.
        val t2 = SignalLifecycleManager.process(bersih, 50_100.0, sinyalSiap, StrategyMode.SWING)
        assertTrue(
            "reset harus menghapus state lama sehingga siklus READY terpicu dari nol",
            t2.transition?.isNewReady == true
        )
    }
}
