package agu.analys.engine.scalping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 + P3 — lantai friksi untuk SL posisi paper, dan larangan SL ≥ entry.
 *
 * Resolver ini adalah satu-satunya penentu harga SL untuk posisi paper: dipakai jalur auto-buy,
 * beli manual, re-arm DCA, dan serah-terima ke jalur auto-sell lama saat rehidrasi.
 */
class PaperStopResolverTest {

    // Biaya Tokocrypto (taker, %) — sama dengan `FeeSchedule`.
    private val tkUsdtBuy = 0.4044
    private val tkUsdtSell = 0.4044
    private val slippage = 0.08
    private val multiple6 = 6.0

    private fun floorUsdt(): Double = PaperStopResolver.frictionFloorPct(
        buyFeePct = tkUsdtBuy,
        sellFeePct = tkUsdtSell,
        slippagePct = slippage,
        minFrictionMultiple = multiple6
    )

    // ── Lantai friksi ─────────────────────────────────────────────────────────

    @Test
    fun `lantai friksi memakai slippage dua sisi`() {
        // (0,4044 + 0,4044 + 2 × 0,08) × 6 = 5,8128%
        assertEquals(5.8128, floorUsdt(), 0.0001)
    }

    @Test
    fun `lantai nonaktif bila kelipatan nol atau negatif`() {
        assertEquals(0.0, PaperStopResolver.frictionFloorPct(tkUsdtBuy, tkUsdtSell, slippage, 0.0), 0.0)
        assertEquals(0.0, PaperStopResolver.frictionFloorPct(tkUsdtBuy, tkUsdtSell, slippage, -3.0), 0.0)
    }

    @Test
    fun `biaya nol menghasilkan lantai nol, bukan pembagian tak hingga`() {
        assertEquals(0.0, PaperStopResolver.frictionFloorPct(0.0, 0.0, 0.0, multiple6), 0.0)
    }

    @Test
    fun `kelipatan ekstrem tidak menghasilkan harga SL negatif`() {
        // 40× dari 0,9688% = 38,75% masih wajar; 200× = 193% sudah mustahil -> lantai dimatikan.
        assertEquals(38.752, PaperStopResolver.frictionFloorPct(tkUsdtBuy, tkUsdtSell, slippage, 40.0), 0.001)
        assertEquals(0.0, PaperStopResolver.frictionFloorPct(tkUsdtBuy, tkUsdtSell, slippage, 200.0), 0.0)
    }

    // ── P2 — SL sinyal yang terlalu rapat dilebarkan ──────────────────────────

    @Test
    fun `SL sinyal 2 persen dilebarkan ke lantai friksi`() {
        // Kasus sinyal SWING/INTRADAY: SL hanya 2% di bawah entry, jauh di bawah lantai 5,81%.
        val entry = 100.0
        val hasil = PaperStopResolver.resolve(signalSl = 98.0, entry = entry, floorPct = floorUsdt())

        assertEquals(PaperStopSource.FRICTION_FLOOR, hasil.source)
        assertEquals(entry * (1.0 - 5.8128 / 100.0), hasil.price, 0.0001)
        assertTrue("SL harus di bawah entry", hasil.price < entry)
    }

    @Test
    fun `SL sinyal yang sudah lebih lebar dari lantai dipertahankan`() {
        val entry = 100.0
        val hasil = PaperStopResolver.resolve(signalSl = 90.0, entry = entry, floorPct = floorUsdt())

        assertEquals(PaperStopSource.SIGNAL, hasil.source)
        assertEquals(90.0, hasil.price, 0.0)
    }

    @Test
    fun `SL tepat di lantai dianggap memenuhi`() {
        val entry = 100.0
        val diLantai = entry * (1.0 - 5.8128 / 100.0)
        val hasil = PaperStopResolver.resolve(signalSl = diLantai, entry = entry, floorPct = floorUsdt())

        assertEquals(PaperStopSource.SIGNAL, hasil.source)
        assertEquals(diLantai, hasil.price, 1e-9)
    }

    @Test
    fun `tanpa SL sinyal dan lantai aktif, dipakai harga lantai`() {
        val hasil = PaperStopResolver.resolve(signalSl = 0.0, entry = 100.0, floorPct = floorUsdt())

        assertEquals(PaperStopSource.FRICTION_FLOOR, hasil.source)
        assertTrue(hasil.usable)
        assertTrue(hasil.price < 100.0)
    }

    @Test
    fun `lantai nonaktif tetap memakai SL sinyal yang valid`() {
        val hasil = PaperStopResolver.resolve(signalSl = 97.0, entry = 100.0, floorPct = 0.0)

        assertEquals(PaperStopSource.SIGNAL, hasil.source)
        assertEquals(97.0, hasil.price, 0.0)
    }

    @Test
    fun `lantai nonaktif dan SL tidak valid berarti tidak ada mesin exit`() {
        val hasil = PaperStopResolver.resolve(signalSl = 0.0, entry = 100.0, floorPct = 0.0)

        assertEquals(PaperStopSource.NONE, hasil.source)
        assertFalse(hasil.usable)
    }

    // ── P3 — SL tidak boleh berada di atas (atau sama dengan) entry ───────────

    @Test
    fun `SL tersimpan di atas rata-rata DCA tidak dipakai apa adanya`() {
        // Skenario P3: posisi dibuka di 100 dengan SL 95, lalu DCA di harga rendah menurunkan
        // rata-rata ke 94. SL lama (95) kini berada DI ATAS entry baru (94).
        val entrySetelahDca = 94.0
        val slLama = 95.0
        assertTrue("prasyarat skenario: SL lama harus di atas entry baru", slLama >= entrySetelahDca)

        val hasil = PaperStopResolver.resolve(slLama, entrySetelahDca, floorUsdt())

        assertEquals(PaperStopSource.FRICTION_FLOOR, hasil.source)
        assertTrue("SL hasil harus di bawah entry", hasil.price < entrySetelahDca)
        assertTrue("SL hasil harus bernilai positif", hasil.price > 0.0)
    }

    @Test
    fun `resolve tidak pernah menghasilkan SL di atas atau sama dengan entry`() {
        val floor = floorUsdt()
        // Sweep: entry, dan SL dari yang mustahil sampai yang sangat lebar.
        val entries = listOf(0.0001, 1.0, 94.0, 100.0, 1_500_000_000.0)
        val stops = listOf(-5.0, 0.0, 50.0, 94.0, 100.0, 200.0, 1_500_000_000.0)

        for (entry in entries) {
            for (sl in stops) {
                val hasil = PaperStopResolver.resolve(sl, entry, floor)
                if (hasil.usable) {
                    assertTrue(
                        "SL ${hasil.price} harus < entry $entry (signalSl=$sl)",
                        hasil.price < entry
                    )
                    assertTrue("SL harus > 0 (entry=$entry, signalSl=$sl)", hasil.price > 0.0)
                } else {
                    assertEquals("NONE harus berharga 0.0", 0.0, hasil.price, 0.0)
                }
            }
        }
    }

    @Test
    fun `entry tidak valid tidak menghasilkan SL`() {
        assertEquals(PaperStopSource.NONE, PaperStopResolver.resolve(90.0, 0.0, floorUsdt()).source)
        assertEquals(PaperStopSource.NONE, PaperStopResolver.resolve(90.0, -1.0, floorUsdt()).source)
        assertEquals(PaperStopSource.NONE, PaperStopResolver.resolve(90.0, Double.NaN, floorUsdt()).source)
    }

    // ── P4 — ATR% ─────────────────────────────────────────────────────────────

    @Test
    fun `ATR persen dihitung dari harga acuan`() {
        assertEquals(2.0, PaperAtr.of(atr = 2.0, referencePrice = 100.0), 1e-9)
        assertEquals(0.5, PaperAtr.of(atr = 500.0, referencePrice = 100_000.0), 1e-9)
    }

    @Test
    fun `ATR yang belum tersedia menghasilkan nol, bukan NaN`() {
        assertEquals(0.0, PaperAtr.of(0.0, 100.0), 0.0)
        assertEquals(0.0, PaperAtr.of(-1.0, 100.0), 0.0)
        assertEquals(0.0, PaperAtr.of(Double.NaN, 100.0), 0.0)
        assertEquals(0.0, PaperAtr.of(2.0, 0.0), 0.0)
        assertEquals(0.0, PaperAtr.of(2.0, Double.NaN), 0.0)
    }

    // ── Rencana SL terpadu untuk satu entry ────────────────────────────────────

    @Test
    fun `resolveForEntry menghitung lantai dan SL dalam satu langkah`() {
        val plan = PaperStopResolver.resolveForEntry(
            signalSl = 98.0,
            entry = 100.0,
            buyFeePct = tkUsdtBuy,
            sellFeePct = tkUsdtSell,
            slippagePct = slippage,
            minFrictionMultiple = multiple6
        )

        assertEquals(floorUsdt(), plan.floorPct, 0.0001)
        assertEquals(PaperStopSource.FRICTION_FLOOR, plan.stop.source)
        assertEquals(100.0 * (1.0 - floorUsdt() / 100.0), plan.stop.price, 0.0001)
        assertTrue(plan.stop.usable)
    }

    @Test
    fun `resolveForEntry mempertahankan SL sinyal yang sudah lebih lebar`() {
        val plan = PaperStopResolver.resolveForEntry(
            signalSl = 90.0,
            entry = 100.0,
            buyFeePct = tkUsdtBuy,
            sellFeePct = tkUsdtSell,
            slippagePct = slippage,
            minFrictionMultiple = multiple6
        )

        assertEquals(floorUsdt(), plan.floorPct, 0.0001)
        assertEquals(PaperStopSource.SIGNAL, plan.stop.source)
        assertEquals(90.0, plan.stop.price, 0.0)
    }

    @Test
    fun `resolveForEntry entry tidak valid menghasilkan SL tidak usable`() {
        val plan = PaperStopResolver.resolveForEntry(
            signalSl = 90.0,
            entry = 0.0,
            buyFeePct = tkUsdtBuy,
            sellFeePct = tkUsdtSell,
            slippagePct = slippage,
            minFrictionMultiple = multiple6
        )

        assertEquals(PaperStopSource.NONE, plan.stop.source)
        assertFalse(plan.stop.usable)
        assertEquals(0.0, plan.stop.price, 0.0)
    }

    // ── Invarian yang tidak boleh dilonggarkan ────────────────────────────────

    @Test
    fun `MIN_FRICTION_MULTIPLE default tidak boleh turun di bawah 6`() {
        assertTrue(
            "AGENTS.md mewajibkan MIN_FRICTION_MULTIPLE >= 6,0; nilai sekarang " +
                "${ScalpingConfig.MIN_FRICTION_MULTIPLE}",
            ScalpingConfig.MIN_FRICTION_MULTIPLE >= 6.0
        )
    }
}
