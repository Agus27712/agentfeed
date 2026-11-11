package agu.analys.engine.scalping

import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes jurnal + FSM paper trade (P2).
 *
 * Fokus:
 * - jurnal bisa bolak-balik JSON tanpa kehilangan angka (statistik harus stabil lintas restart),
 * - `isNewBar = false` tidak menambah hitungan bar (evaluasi per-tick tidak boleh memicu time-stop),
 * - harga exit yang dicatat sesuai level yang benar-benar terpicu.
 */
class PaperTradeJournalTest {

    private val buyFee = 0.4044
    private val sellFee = 0.4044
    private val slip = 0.08

    private fun bar(o: Double, h: Double, l: Double, c: Double, t: Long = 0L) =
        CandleBar(timestamp = t, open = o, high = h, low = l, close = c, volume = 100.0)

    private fun machine(
        mode: ScalpExitMode = ScalpExitMode.CLASSIC,
        maxHold: Int = 0,
        entry: Double = 100.0,
        sl: Double = 99.0,
        tp1: Double = 101.0,
        tp2: Double = 102.0,
        /**
         * Skenario di bawah bergerak kecil (+1…1,6% kotor), **sengaja** di bawah lantai
         * profit 2× friksi (≈1,94%). Lantai dimatikan eksplisit agar yang diuji adalah
         * logika mode exit murni; perilaku lantai sendiri diuji di [ScalpStrategyFixTest].
         */
        minProfitFrictionMultiple: Double = 0.0
    ) = PaperExitStateMachine(
        entry = entry,
        stopLoss = sl,
        tp1 = tp1,
        tp2 = tp2,
        buyFeePct = buyFee,
        sellFeePct = sellFee,
        slippagePct = slip,
        params = ScalpExitParams(
            mode = mode,
            maxHoldBarsM1 = maxHold,
            minProfitFrictionMultiple = minProfitFrictionMultiple
        )
    )

    // ── Jurnal ────────────────────────────────────────────────────────────────

    private fun sampleRecord() = PaperTradeRecord(
        id = 1_700_000_000_000L,
        symbol = "BTCIDR",
        exchange = "TOKOCRYPTO",
        mode = ScalpExitMode.FULL,
        reason = PaperExitReason.TP2,
        result = PaperExitResult.WIN,
        entry = 100.0,
        exitPrice = 102.0,
        stopLoss = 99.0,
        tp1 = 101.0,
        tp2 = 102.0,
        r = 1.5118,
        ambiguous = false,
        barsHeld = 7,
        openedAt = 1_699_999_000_000L,
        closedAt = 1_700_000_000_000L
    )

    @Test
    fun `jurnal bolak-balik JSON tanpa kehilangan angka`() {
        val original = listOf(sampleRecord())
        val restored = PaperTradeRecord.listFromJson(PaperTradeRecord.listToJson(original))

        assertEquals(1, restored.size)
        val r = restored.first()
        assertEquals(original.first().symbol, r.symbol)
        assertEquals(original.first().mode, r.mode)
        assertEquals(original.first().reason, r.reason)
        assertEquals(original.first().result, r.result)
        assertEquals(1.5118, r.r, 1e-9)
        assertEquals(102.0, r.exitPrice, 1e-9)
        assertEquals(99.0, r.stopLoss, 1e-9)
        assertEquals(7, r.barsHeld)
        assertEquals(1_699_999_000_000L, r.openedAt)
    }

    @Test
    fun `JSON rusak tidak membuat aplikasi gagal`() {
        assertEquals(emptyList<PaperTradeRecord>(), PaperTradeRecord.listFromJson("{bukan array"))
        assertEquals(emptyList<PaperTradeRecord>(), PaperTradeRecord.listFromJson(null))
        assertEquals(emptyList<PaperTradeRecord>(), PaperTradeRecord.listFromJson(""))
        // entri tanpa simbol diabaikan, bukan menghasilkan baris kosong
        assertEquals(emptyList<PaperTradeRecord>(), PaperTradeRecord.listFromJson("[{\"r\":1.0}]"))
    }

    @Test
    fun `alasan dan hasil tak dikenal jatuh ke nilai aman`() {
        assertEquals(PaperExitReason.TIMEOUT, PaperExitReason.fromKey("entah_apa"))
        assertEquals(PaperExitReason.EARLY_PROFIT, PaperExitReason.fromKey("early_profit"))
        assertEquals(PaperExitReason.BE_SL, PaperExitReason.fromKey("be_sl"))
        assertEquals(PaperExitResult.UNRESOLVED, PaperExitResult.fromKey("???"))
        assertEquals(PaperExitResult.WIN, PaperExitResult.fromKey("win"))
    }

    @Test
    fun `statistik dari jurnal identik dengan statistik dari keputusan`() {
        val records = listOf(
            sampleRecord(),
            sampleRecord().copy(id = 2L, reason = PaperExitReason.SL, result = PaperExitResult.LOSS, r = -1.95),
            sampleRecord().copy(id = 3L, reason = PaperExitReason.TIMEOUT, result = PaperExitResult.UNRESOLVED, r = 0.0)
        )

        val fromRecords = PaperTradeStats.computeFromRecords(records)
        val fromDecisions = PaperTradeStats.compute(records.map { it.toDecision() })

        assertEquals(fromDecisions, fromRecords)
        assertEquals(3, fromRecords.totalTrades)
        assertEquals(2, fromRecords.resolvedTrades)
        assertEquals(1, fromRecords.wins)
        assertEquals(1, fromRecords.losses)
        assertEquals(1, fromRecords.timeouts)
        // timeout tidak masuk hitungan R
        assertEquals(1.5118 - 1.95, fromRecords.totalR, 1e-4)
        assertEquals(50.0, fromRecords.winRate, 1e-9)
    }

    // ── Harga exit ────────────────────────────────────────────────────────────

    @Test
    fun `SL keluar di level SL aktif bukan di harga low bar`() {
        val m = machine()
        val d = m.onBar(bar(100.0, 100.2, 98.5, 99.0), listOf(bar(100.0, 100.2, 98.5, 99.0)))
        assertNotNull(d)
        assertEquals(PaperExitReason.SL, d!!.reason)
        assertEquals(99.0, d.exitPrice, 1e-9)
    }

    @Test
    fun `TP2 keluar tepat di level TP2`() {
        val m = machine()
        val d = m.onBar(bar(100.0, 102.5, 100.0, 102.3), listOf(bar(100.0, 102.5, 100.0, 102.3)))
        assertNotNull(d)
        assertEquals(PaperExitReason.TP2, d!!.reason)
        assertEquals(102.0, d.exitPrice, 1e-9)
    }

    @Test
    fun `timeout keluar di harga close bar`() {
        val m = machine(maxHold = 1)
        val b = bar(100.0, 100.05, 99.95, 100.0)
        val d = m.onBar(b, listOf(b), isNewBar = true)
        assertNotNull(d)
        assertEquals(PaperExitReason.TIMEOUT, d!!.reason)
        assertEquals(100.0, d.exitPrice, 1e-9)
    }

    // ── Hitungan bar ──────────────────────────────────────────────────────────

    @Test
    fun `tick di tengah bar tidak menambah hitungan bar M1`() {
        val m = machine(maxHold = 2)
        val b = bar(100.0, 100.05, 99.95, 100.0)

        assertNull("tick pertama di menit yang sama tidak boleh menutup posisi", m.onBar(b, listOf(b), isNewBar = false))
        assertEquals(0, m.barsHeld)

        assertNull(m.onBar(b, listOf(b), isNewBar = true))
        assertEquals(1, m.barsHeld)

        val d = m.onBar(b, listOf(b), isNewBar = true)
        assertNotNull(d)
        assertEquals(PaperExitReason.TIMEOUT, d!!.reason)
        assertEquals(2, m.barsHeld)
    }

    @Test
    fun `level SL tetap dievaluasi walau bukan batas bar`() {
        val m = machine()
        val d = m.onBar(bar(100.0, 100.1, 98.0, 98.2), listOf(bar(100.0, 100.1, 98.0, 98.2)), isNewBar = false)
        assertNotNull("proteksi SL tidak boleh menunggu akhir bar", d)
        assertEquals(PaperExitReason.SL, d!!.reason)
    }

    @Test
    fun `early profit dilewati bila jendela momentum belum tersedia`() {
        val m = machine(mode = ScalpExitMode.EARLY)
        // Net sudah jauh di atas ambang early, tapi jendela M1 kosong.
        val b = bar(100.0, 101.6, 100.0, 101.5)
        assertNull("tanpa data momentum, engine harus menahan posisi", m.onBar(b, emptyList(), isNewBar = false))
        assertEquals(PaperExitPhase.BREAKEVEN, m.phase)
    }

    // ── Fase FSM ──────────────────────────────────────────────────────────────

    @Test
    fun `fase berubah HOLDING ke TP1_PARTIAL lalu TRAILING`() {
        val m = machine(mode = ScalpExitMode.FULL)

        assertEquals(PaperExitPhase.HOLDING, m.phase)

        // Bar menyentuh TP1 -> partial 50% dan SL naik ke BE net.
        val b1 = bar(100.0, 101.2, 100.0, 101.1)
        assertNull(m.onBar(b1, listOf(b1)))
        assertTrue(m.isPartialDone)
        assertEquals(PaperExitPhase.TP1_PARTIAL, m.phase)
        assertTrue("SL harus naik di atas SL awal", m.activeStopLoss > 99.0)

        // Bar berikutnya: high menyentuh trigger trailing, tetapi close TIDAK menyentuh
        // ambang early-profit (0,45%), jadi posisi masih ditahan dan fase naik ke TRAILING.
        val b2 = bar(101.1, 101.5, 101.05, 101.35)
        assertNull(m.onBar(b2, listOf(b1, b2)))
        assertEquals(PaperExitPhase.TRAILING, m.phase)
        assertTrue("SL trailing harus di atas BE", m.activeStopLoss > m.netBreakevenPrice)
    }

    @Test
    fun `early profit didahulukan atas trailing pada mode full`() {
        val m = machine(mode = ScalpExitMode.FULL)
        val b1 = bar(100.0, 101.2, 100.0, 101.1)
        m.onBar(b1, listOf(b1))

        // Bar yang high DAN close-nya sama-sama di atas ambang early-profit.
        val b2 = bar(101.1, 101.6, 101.05, 101.5)
        val d = m.onBar(b2, listOf(b1, b2))

        assertNotNull(d)
        assertEquals(
            "prioritas mode FULL: SL > early > trail > TP2",
            PaperExitReason.EARLY_PROFIT,
            d!!.reason
        )
        assertEquals(101.5, d.exitPrice, 1e-9)
    }

    @Test
    fun `snapshot mencerminkan state machine`() {
        val m = machine(mode = ScalpExitMode.TP1_BE)
        val snap = PaperExitSnapshot.of(m, symbol = "BTCIDR", exchange = "TOKOCRYPTO", currentPrice = 100.5)

        assertEquals("BTCIDR", snap.symbol)
        assertEquals(ScalpExitMode.TP1_BE, snap.mode)
        assertEquals(PaperExitPhase.HOLDING, snap.phase)
        assertEquals(100.0, snap.entry, 1e-9)
        assertEquals(99.0, snap.initialStopLoss, 1e-9)
        assertEquals(99.0, snap.activeStopLoss, 1e-9)
        assertFalse("SL belum bergerak", snap.stopHasMoved)
        assertTrue("harga impas net harus di atas entry karena fee", snap.netBreakevenPrice > 100.0)
        assertEquals(0, snap.barsHeld)
        assertTrue(snap.distanceToStopPct > 0.0)
        assertTrue(snap.isRiskValid)
    }

    @Test
    fun `state machine sepakat dengan resolver pada deret bar yang sama`() {
        // Deret yang menyentuh TP1, lalu trailing, lalu TP2 — cukup kaya untuk memicu
        // perbedaan perilaku antar mode bila implementasinya menyimpang.
        val series = listOf(
            bar(100.0, 100.3, 99.9, 100.1, 0L),
            bar(100.1, 101.2, 100.0, 101.1, 60_000L),
            bar(101.1, 101.5, 101.05, 101.35, 120_000L),
            bar(101.35, 101.9, 101.3, 101.8, 180_000L),
            bar(101.8, 102.4, 101.7, 102.3, 240_000L)
        )

        for (mode in ScalpExitMode.entries) {
            // Lantai profit dimatikan agar resolver dan state machine memakai basis sama
            // pada skenario +2,3% yang kecil ini (lihat [machine]).
            val params = ScalpExitParams(mode = mode, minProfitFrictionMultiple = 0.0)

            val expected = PaperExitResolver.resolve(
                bars = series,
                entryIndex = 0,
                entry = 100.0,
                stopLoss = 99.0,
                tp1 = 101.0,
                tp2 = 102.0,
                buyFeePct = buyFee,
                sellFeePct = sellFee,
                slippagePct = slip,
                params = params
            )

            val m = machine(mode = mode)
            var actual: PaperExitDecision? = null
            for (j in 1 until series.size) {
                // Jendela momentum disamakan dengan `bars.subList(max(0, j - 14), j + 1)` di resolver.
                val window = series.subList(maxOf(0, j - 14), j + 1)
                actual = m.onBar(series[j], window, isNewBar = true)
                if (actual != null) break
            }

            assertNotNull("mode $mode: state machine harus menutup posisi", actual)
            assertEquals("mode $mode: alasan exit", expected.reason, actual!!.reason)
            assertEquals("mode $mode: R net", expected.r, actual.r, 1e-9)
            assertEquals("mode $mode: harga exit", expected.exitPrice, actual.exitPrice, 1e-9)
            assertEquals("mode $mode: jumlah bar", expected.exitIndex!!, m.barsHeld)
        }
    }

    @Test
    fun `snapshot menandai SL yang sudah bergerak`() {
        val m = machine(mode = ScalpExitMode.TP1_BE)
        m.onBar(bar(100.0, 101.2, 100.0, 101.1), listOf(bar(100.0, 101.2, 100.0, 101.1)))
        val snap = PaperExitSnapshot.of(m, "BTCIDR", "TOKOCRYPTO", 101.1)

        assertTrue(snap.stopHasMoved)
        assertTrue(snap.activeStopLoss > snap.initialStopLoss)
        assertEquals(PaperExitPhase.TP1_PARTIAL, snap.phase)
        assertTrue(snap.isPartialDone)
        assertEquals(1, snap.barsHeld)
    }
}
