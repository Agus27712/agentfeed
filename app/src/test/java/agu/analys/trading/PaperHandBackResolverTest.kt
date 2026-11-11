package agu.analys.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji pemilih SL serah-terima mesin exit paper ke jalur auto-sell lama — **P9**.
 *
 * Inti temuan: mematikan auto-exit saat posisi terbuka meninggalkan posisi tanpa SL/TP sampai
 * restart. Perbaikannya menyerahkan posisi ke jalur lama — tetapi SL hasil serah-terima tidak
 * boleh melebar mundur (trailing/BE yang sudah terkunci harus dipertahankan), dan tidak boleh
 * `>= entry` kecuali memang stop BE/profit dari mesin (pengecualian sadar terhadap invarian lama
 * `0 < SL < entry`, karena jalur lama menjual saat `currentPrice <= stopLossPrice`).
 *
 * Kontrol negatif (mutasi yang harus menggagalkan uji):
 * - kandidat diambil `min` bukan `max` → uji "trailing terkunci tidak melebar" gagal;
 * - cabang `activeSl >= entry` dihapus → uji "SL BE atau profit dipertahankan" gagal;
 * - label PROFIT selalu ditulis BREAKEVEN → uji "SL BE atau profit dipertahankan" gagal.
 */
class PaperHandBackResolverTest {

    private fun resolve(
        entry: Double = 100.0,
        storedSl: Double = 94.0,
        activeSl: Double = Double.NaN,
        bePx: Double = 100.9,
        floorPct: Double = 6.0
    ) = PaperHandBackResolver.resolveStopLoss(
        entryPrice = entry,
        storedSl = storedSl,
        activeSl = activeSl,
        bePx = bePx,
        floorPct = floorPct
    )

    @Test
    fun `SL BE atau profit dari mesin dipertahankan`() {
        val profit = resolve(activeSl = 103.0)
        assertEquals(103.0, profit.price, 1e-9)
        assertEquals(PaperHandBackSource.PROFIT, profit.source)
        assertTrue(profit.usable)
    }

    @Test
    fun `SL aktif antara entry dan harga impas dilabeli breakeven`() {
        val be = resolve(activeSl = 100.5, bePx = 100.9)
        assertEquals(100.5, be.price, 1e-9)
        assertEquals(PaperHandBackSource.BREAKEVEN, be.source)
    }

    @Test
    fun `SL aktif sama dengan entry dianggap breakeven`() {
        val be = resolve(activeSl = 100.0)
        assertEquals(100.0, be.price, 1e-9)
        assertEquals(PaperHandBackSource.BREAKEVEN, be.source)
    }

    @Test
    fun `harga impas tidak diketahui tetap breakeven saat SL di atas entry`() {
        val be = resolve(activeSl = 103.0, bePx = Double.NaN)
        assertEquals(103.0, be.price, 1e-9)
        assertEquals(PaperHandBackSource.BREAKEVEN, be.source)
    }

    @Test
    fun `trailing terkunci tidak melebar mundur ke lantai friksi`() {
        // entry 100, SL awal 94, peak 101, trail 3% → activeStopLoss 97,97.
        // Serah-terima naif (PaperStopResolver saja) akan memakai lantai friksi 94.
        val stop = resolve(storedSl = 94.0, activeSl = 97.97, floorPct = 6.0)
        assertEquals(97.97, stop.price, 1e-9)
        assertEquals(PaperHandBackSource.ACTIVE_STOP, stop.source)
    }

    @Test
    fun `SL aktif yang lebih tinggi dari SL tersimpan tetap dipakai`() {
        val stop = resolve(storedSl = 92.0, activeSl = 93.0, floorPct = 6.0)
        assertEquals(93.0, stop.price, 1e-9)
        assertEquals(PaperHandBackSource.ACTIVE_STOP, stop.source)
    }

    @Test
    fun `SL tersimpan dipakai bila tidak ada SL aktif`() {
        val stop = resolve(storedSl = 94.0, activeSl = Double.NaN, floorPct = 0.0)
        assertEquals(94.0, stop.price, 1e-9)
        assertEquals(PaperHandBackSource.SIGNAL, stop.source)
    }

    @Test
    fun `SL aktif sama dengan SL tersimpan dianggap setara`() {
        val stop = resolve(storedSl = 95.0, activeSl = 95.0, floorPct = 0.0)
        assertEquals(95.0, stop.price, 1e-9)
        assertEquals(PaperHandBackSource.SIGNAL, stop.source)
    }

    @Test
    fun `lantai friksi dipakai bila SL tersimpan tidak valid`() {
        val stop = resolve(storedSl = 0.0, activeSl = Double.NaN, floorPct = 6.0)
        assertEquals(94.0, stop.price, 1e-9)
        assertEquals(PaperHandBackSource.FRICTION_FLOOR, stop.source)
    }

    @Test
    fun `SL tersimpan di atas entry ditolak bila lantai friksi mati`() {
        val stop = resolve(storedSl = 101.0, activeSl = Double.NaN, floorPct = 0.0)
        assertFalse(stop.usable)
        assertEquals(PaperHandBackSource.NONE, stop.source)
        assertEquals(0.0, stop.price, 1e-9)
    }

    @Test
    fun `tanpa kandidat valid hasilnya NONE`() {
        val stop = resolve(storedSl = 0.0, activeSl = Double.NaN, floorPct = 0.0)
        assertFalse(stop.usable)
        assertEquals(PaperHandBackSource.NONE, stop.source)
    }

    @Test
    fun `entry tidak valid hasilnya NONE`() {
        assertFalse(resolve(entry = 0.0, activeSl = 97.0).usable)
        assertFalse(resolve(entry = Double.NaN, activeSl = 97.0).usable)
    }

    @Test
    fun `hanya garis SL aktif yang dipertahankan bila lantai mati`() {
        val stop = resolve(storedSl = 0.0, activeSl = 97.5, floorPct = 0.0)
        assertEquals(97.5, stop.price, 1e-9)
        assertEquals(PaperHandBackSource.ACTIVE_STOP, stop.source)
    }
}
