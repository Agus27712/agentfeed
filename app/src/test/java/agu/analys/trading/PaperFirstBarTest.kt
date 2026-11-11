package agu.analys.trading

import agu.analys.engine.scalping.PaperExitReason
import agu.analys.engine.scalping.PaperExitStateMachine
import agu.analys.engine.scalping.ScalpExitMode
import agu.analys.engine.scalping.ScalpExitParams
import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8 — `barsHeld` dihitung dua kali pada tick pertama sesudah rehidrasi.
 *
 * Alur yang dijaga (lihat `PaperFirstBarResolver`):
 *
 * 1. `PaperRehydrate.mergedBarsHeld` sudah memasukkan menit yang berjalan sejak posisi dibuka;
 * 2. `PaperAutoTradeRuntime.put(..., markFirstTickAsNewBar = true)` menandai tick pertama sebagai
 *    bar baru supaya time-stop langsung dievaluasi;
 * 3. tanpa pemisahan ini, tick yang sama **juga** menambah `barsHeld` → time-stop maju 1 menit.
 */
class PaperFirstBarTest {

    private fun bar(o: Double, h: Double, l: Double, c: Double, t: Long = 0L) =
        CandleBar(timestamp = t, open = o, high = h, low = l, close = c, volume = 100.0)

    private fun machine(maxHold: Int = 5) = PaperExitStateMachine(
        entry = 100.0,
        stopLoss = 99.0,
        tp1 = 101.0,
        tp2 = 102.0,
        buyFeePct = 0.4044,
        sellFeePct = 0.4044,
        slippagePct = 0.08,
        params = ScalpExitParams(mode = ScalpExitMode.CLASSIC, maxHoldBarsM1 = maxHold)
    )

    // ── Resolver murni ───────────────────────────────────────────────────────

    @Test
    fun `bar baru biasa tetap menambah hitungan`() {
        assertTrue(PaperFirstBarResolver.countsAsNewBar(isNewBar = true, firstTickAfterRehydrate = false))
    }

    @Test
    fun `tick di menit yang sama bukan bar baru`() {
        assertFalse(PaperFirstBarResolver.countsAsNewBar(isNewBar = false, firstTickAfterRehydrate = false))
    }

    @Test
    fun `tick pertama sesudah rehidrasi tidak menambah hitungan`() {
        // inti P8: menitnya sudah dihitung mergedBarsHeld
        assertFalse(PaperFirstBarResolver.countsAsNewBar(isNewBar = true, firstTickAfterRehydrate = true))
    }

    @Test
    fun `penanda rehidrasi tanpa bar baru tetap tidak menambah`() {
        // kombinasi mustahil dari runtime, tetapi resolver tidak boleh mengarang true
        assertFalse(PaperFirstBarResolver.countsAsNewBar(isNewBar = false, firstTickAfterRehydrate = true))
    }

    // ── Integrasi dengan mesin exit ──────────────────────────────────────────

    /**
     * Kontrol utama P8: `barsHeld` hasil rehidrasi = 4 dari batas 5. Tick pertama sesudah
     * rehidrasi **tidak** boleh menaikkannya menjadi 5 — kalau naik, time-stop keluar satu bar
     * lebih awal daripada `AUDIT-2026-11-08.md`.
     */
    @Test
    fun `tick pertama sesudah rehidrasi tidak menaikkan barsHeld`() {
        val m = machine(maxHold = 5)
        val restored = m.snapshot().copy(barsHeld = 4)
        assertTrue(m.restore(restored))
        assertEquals(4, m.barsHeld)

        val d = m.onBar(
            bar = bar(100.0, 100.2, 99.8, 100.1),
            window14 = emptyList(),
            isNewBar = true,
            countAsNewBar = PaperFirstBarResolver.countsAsNewBar(
                isNewBar = true, firstTickAfterRehydrate = true
            )
        )

        assertEquals("hitungan tidak boleh maju untuk menit yang sudah dihitung", 4, m.barsHeld)
        assertNull("batas 5 bar belum tercapai, posisi harus ditahan", d)
    }

    /**
     * Pasangan dari tes di atas: bila menit yang sama sudah membuat `barsHeld` mencapai batas,
     * time-stop **harus** tetap terpicu pada tick pertama itu — kalau tidak, posisi yang sudah
     * kehabisan waktu saat app tertutup baru ditutup satu menit kemudian.
     */
    @Test
    fun `time-stop tetap terpicu pada tick pertama sesudah rehidrasi`() {
        val m = machine(maxHold = 5)
        assertTrue(m.restore(m.snapshot().copy(barsHeld = 5)))

        val d = m.onBar(
            bar = bar(100.0, 100.2, 99.8, 100.1),
            window14 = emptyList(),
            isNewBar = true,
            countAsNewBar = PaperFirstBarResolver.countsAsNewBar(
                isNewBar = true, firstTickAfterRehydrate = true
            )
        )

        assertNotNull("waktu tahan sudah habis, posisi harus ditutup sekarang", d)
        assertEquals(PaperExitReason.TIMEOUT, d!!.reason)
        assertEquals("tidak ada penambahan walau exit terjadi", 5, m.barsHeld)
    }

    /** Jalur normal tidak boleh ikut teredam: bar M1 baru berikutnya tetap menambah hitungan. */
    @Test
    fun `bar M1 berikutnya menambah hitungan seperti biasa`() {
        val m = machine(maxHold = 5)
        assertTrue(m.restore(m.snapshot().copy(barsHeld = 4)))

        val d = m.onBar(
            bar = bar(100.0, 100.2, 99.8, 100.1),
            window14 = emptyList(),
            isNewBar = true,
            countAsNewBar = PaperFirstBarResolver.countsAsNewBar(
                isNewBar = true, firstTickAfterRehydrate = false
            )
        )

        assertEquals(5, m.barsHeld)
        assertNotNull("barsHeld menyentuh batas -> time-stop", d)
        assertEquals(PaperExitReason.TIMEOUT, d!!.reason)
    }
}
