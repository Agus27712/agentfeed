package agu.analys.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * **N2** — kepemilikan edge pemicu trailing.
 *
 * `SpotPositionStore.updateTrailingPrice` bersifat **edge-once**: pemanggil pertama yang melihat
 * harga melewati batas akan mengonsumsi edge-nya, dan `justTriggered` hanya `true` sekali.
 *
 * Sebelum perbaikan 2026-10-16, loop 4 detik `TradingForegroundService` ikut memanggil fungsi ini
 * **dan membuang** nilai `justTriggered`-nya. Karena `TradingViewModel.checkTrailingForMode`
 * adalah satu-satunya jalur yang mengeksekusi `executeAutoSellOrder`, bila service menang balapan
 * maka VM tidak pernah melihat `true` → notifikasi trailing hilang dan **auto-sell tidak pernah
 * terkirim**.
 *
 * Perbaikannya: pengamat non-pemilik wajib mengirim `consumeTrigger = false`, yang hanya
 * menaikkan peak tanpa menyentuh mesin pemicu.
 *
 * Kontrol negatif: ubah panggilan "pengamat" di bawah menjadi `consumeTrigger = true` →
 * uji pertama GAGAL pada dua assertion (pengamat melaporkan trigger, dan VM kehilangan edge-nya).
 */
@RunWith(RobolectricTestRunner::class)
class TrailingEdgeOwnershipTest {

    private val ex = "TOKOCRYPTO"
    private val sym = "BTCIDR"

    private fun newStore(): SpotPositionStore = SpotPositionStore(RuntimeEnvironment.getApplication())

    /**
     * Posisi siap-trailing yang deterministik:
     * entry 100, peak dinaikkan ke 120 → batas trailing `120 × (1 − 2%) = 117,6`
     * (di atas entry, jadi lolos syarat profit-lock).
     */
    private fun seedSiapTrailing(store: SpotPositionStore) {
        store.setHolding(sym, invested = 1_000.0, entry = 100.0, quantity = 10.0, isReal = false, exchange = ex)
        store.setTrailingStop(
            symbol = sym,
            enabled = true,
            trailingPercent = 2.0,
            referencePrice = 100.0,
            isTieredEnabled = false,
            isReal = false,
            exchange = ex
        )
        store.updateTrailingPrice(sym, 120.0, isReal = false, exchange = ex)
    }

    @Test
    fun `pengamat tidak boleh mengonsumsi edge trailing milik pemilik eksekusi`() {
        val store = newStore()
        seedSiapTrailing(store)

        // Tick pertama di bawah batas: filter "minimal 2 tick" belum terpenuhi.
        val tick1 = store.updateTrailingPrice(sym, 117.0, isReal = false, exchange = ex)
        assertFalse("tick pertama di bawah batas belum boleh memicu", tick1.second)

        // Pengamat non-pemilik (loop 4 detik service) melihat crossing yang sama.
        val pengamat = store.updateTrailingPrice(
            sym, 117.0, isReal = false, exchange = ex, consumeTrigger = false
        )
        assertFalse("pengamat tidak boleh melaporkan trigger", pengamat.second)
        assertFalse(
            "pengamat tidak boleh menandai posisi ter-trigger",
            store.get(sym, isReal = false, exchange = ex).isTrailingTriggered
        )

        // Pemilik eksekusi (VM) harus tetap menerima edge-nya.
        val pemilik = store.updateTrailingPrice(sym, 117.0, isReal = false, exchange = ex)
        assertTrue("VM harus tetap menerima edge trailing", pemilik.second)
    }

    @Test
    fun `pengamat tetap menaikkan peak walau tidak mengonsumsi edge`() {
        val store = newStore()
        seedSiapTrailing(store)

        val hasil = store.updateTrailingPrice(
            sym, 130.0, isReal = false, exchange = ex, consumeTrigger = false
        )

        assertEquals("peak harus tetap naik saat pengamat melaporkan harga baru", 130.0, hasil.first.peakPrice, 0.0001)
        assertFalse("pengamat tetap tidak boleh mengonsumsi edge", hasil.second)
        assertFalse(
            "peak baru belum menyentuh batas (130 × 0,98 = 127,4 < 130)",
            store.get(sym, isReal = false, exchange = ex).isTrailingTriggered
        )
    }

    @Test
    fun `edge trailing hanya bisa dikonsumsi sekali`() {
        val store = newStore()
        seedSiapTrailing(store)

        store.updateTrailingPrice(sym, 117.0, isReal = false, exchange = ex)
        val pemicu = store.updateTrailingPrice(sym, 117.0, isReal = false, exchange = ex)
        val setelahnya = store.updateTrailingPrice(sym, 117.0, isReal = false, exchange = ex)

        assertTrue("tick kedua di bawah batas yang memicu", pemicu.second)
        assertFalse("edge-once: pemanggilan berikutnya tidak boleh ikut terpicu", setelahnya.second)
    }
}
