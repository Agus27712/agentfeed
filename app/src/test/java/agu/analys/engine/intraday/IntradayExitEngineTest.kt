package agu.analys.engine.intraday

import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Penjaga **mesin exit intraday (paket E)** — murni, tanpa Android dan tanpa data jaringan.
 *
 * Yang dijaga:
 * 1. Mode bawaan `SESSION_CLOSE` **sama** dengan perilaku lama: TP1 menutup posisi, SL dan
 *    sesi Close Malam berlaku.
 * 2. `TP1_BREAKEVEN` menahan posisi setelah TP1 dan menaikkan stop ke harga impas net.
 * 3. `TRAIL_ATR` menaikkan stop mengikuti puncak dan **tidak pernah menurunkannya**.
 * 4. `TIME_STOP` keluar di close bar ke-N.
 * 5. Bar yang menyentuh SL dan TP bersamaan dinilai **SL** (konservatif), bukan TP.
 */
class IntradayExitEngineTest {

    private fun bar(open: Double, high: Double, low: Double, close: Double, ts: Long = 0L) =
        CandleBar(timestamp = ts, open = open, high = high, low = low, close = close, volume = 1000.0)

    private fun posisi(
        entry: Double = 100.0,
        sl: Double = 97.0,
        tp1: Double = 102.0,
        tp2: Double = 105.0,
        breakeven: Double = 100.4,
        atr: Double = 1.0
    ) = IntradayExitPosition(
        entryPrice = entry,
        initialStopLoss = sl,
        takeProfit1 = tp1,
        takeProfit2 = tp2,
        breakevenPrice = breakeven,
        atr = atr
    )

    @Test
    fun modeBawaan_mereproduksiPerilakuLama() {
        val p = posisi()
        var state = IntradayExitEngine.initialState(p)

        // TP1 tersentuh, TP2 belum → keluar penuh di TP1.
        val d = IntradayExitEngine.evaluate(
            bar = bar(100.0, 102.5, 99.5, 102.2),
            position = p,
            state = state,
            config = IntradayExitConfig.DEFAULT,
            sessionClose = false
        )
        assertTrue("TP1 harus menutup posisi pada mode bawaan", d is IntradayExitDecision.Exit)
        d as IntradayExitDecision.Exit
        assertEquals("TP1_HIT", d.reason)
        assertEquals(102.0, d.price, 1e-9)

        // Tanpa TP/SL → tahan, lalu sesi Close Malam menutup di close bar.
        state = IntradayExitEngine.initialState(p)
        val tahan = IntradayExitEngine.evaluate(
            bar = bar(100.0, 101.0, 99.6, 100.8),
            position = p,
            state = state,
            config = IntradayExitConfig.DEFAULT,
            sessionClose = false
        )
        assertTrue("Tanpa TP/SL/sesi posisi harus ditahan", tahan is IntradayExitDecision.Hold)
        val tutup = IntradayExitEngine.evaluate(
            bar = bar(100.8, 101.2, 100.5, 101.0),
            position = p,
            state = (tahan as IntradayExitDecision.Hold).state,
            config = IntradayExitConfig.DEFAULT,
            sessionClose = true
        )
        assertTrue("Sesi Close Malam harus menutup posisi", tutup is IntradayExitDecision.Exit)
        tutup as IntradayExitDecision.Exit
        assertEquals("CLOSE_MALAM_SESSION_EXIT", tutup.reason)
        assertEquals(101.0, tutup.price, 1e-9)
    }

    @Test
    fun tp1Breakeven_menahanSetelahTp1DanStopNaikKeImpas() {
        val p = posisi(breakeven = 100.4)
        val cfg = IntradayExitConfig(IntradayExitMode.TP1_BREAKEVEN)

        val tp1 = IntradayExitEngine.evaluate(
            bar = bar(100.0, 102.2, 99.9, 102.0),
            position = p,
            state = IntradayExitEngine.initialState(p),
            config = cfg,
            sessionClose = false
        )
        assertTrue("TP1 tidak boleh menutup posisi pada mode break-even", tp1 is IntradayExitDecision.Hold)
        tp1 as IntradayExitDecision.Hold
        assertTrue("TP1 harus tercatat tersentuh", tp1.state.tp1Hit)
        assertEquals("Stop harus naik ke harga impas net", 100.4, tp1.state.stopLoss, 1e-9)

        // Harga kembali ke bawah impas → keluar di stop yang sudah dinaikkan.
        val keluar = IntradayExitEngine.evaluate(
            bar = bar(102.0, 101.9, 100.2, 100.3),
            position = p,
            state = tp1.state,
            config = cfg,
            sessionClose = false
        )
        assertTrue("Stop impas harus menutup posisi", keluar is IntradayExitDecision.Exit)
        keluar as IntradayExitDecision.Exit
        assertEquals("TRAIL_STOP_HIT", keluar.reason)
        assertEquals(100.4, keluar.price, 1e-9)
    }

    @Test
    fun trailAtr_stopHanyaNaik_mengikutiPuncak() {
        val p = posisi(atr = 2.0)
        val cfg = IntradayExitConfig(IntradayExitMode.TRAIL_ATR, trailAtrMult = 1.0)

        // Bar pertama menyentuh TP1 → stop naik ke impas (100,4), trailing belum aktif.
        val tp1 = IntradayExitEngine.evaluate(
            bar = bar(100.0, 102.3, 99.9, 102.0),
            position = p,
            state = IntradayExitEngine.initialState(p),
            config = cfg,
            sessionClose = false
        ) as IntradayExitDecision.Hold
        val stopSetelahTp1 = tp1.state.stopLoss

        // Puncak baru 103,5 → trail = 103,5 − 1,0×2,0 = 101,5 > stop sebelumnya.
        val naik = IntradayExitEngine.evaluate(
            bar = bar(102.0, 103.5, 101.8, 103.0),
            position = p,
            state = tp1.state,
            config = cfg,
            sessionClose = false
        ) as IntradayExitDecision.Hold
        assertEquals(101.5, naik.state.stopLoss, 1e-9)
        assertTrue("Stop trailing hanya boleh naik", naik.state.stopLoss > stopSetelahTp1)

        // Harga turun tetapi tidak menyentuh stop 101,5 → tahan, stop tidak boleh turun.
        val turun = IntradayExitEngine.evaluate(
            bar = bar(103.0, 103.2, 101.9, 102.0),
            position = p,
            state = naik.state,
            config = cfg,
            sessionClose = false
        ) as IntradayExitDecision.Hold
        assertEquals("Stop tidak boleh turun", 101.5, turun.state.stopLoss, 1e-9)
    }

    @Test
    fun timeStop_keluarDiCloseBarKeN() {
        val p = posisi()
        val cfg = IntradayExitConfig(IntradayExitMode.TIME_STOP, timeStopBars = 3)
        var state = IntradayExitEngine.initialState(p)

        for (i in 1..2) {
            val d = IntradayExitEngine.evaluate(
                bar = bar(100.0, 100.9, 99.8, 100.5, ts = i * 3_600_000L),
                position = p,
                state = state,
                config = cfg,
                sessionClose = false
            )
            assertTrue("Bar ke-$i belum boleh kena time-stop", d is IntradayExitDecision.Hold)
            state = (d as IntradayExitDecision.Hold).state
        }

        val d3 = IntradayExitEngine.evaluate(
            bar = bar(100.5, 100.8, 100.1, 100.6, ts = 3 * 3_600_000L),
            position = p,
            state = state,
            config = cfg,
            sessionClose = false
        )
        assertTrue("Bar ke-3 harus kena time-stop", d3 is IntradayExitDecision.Exit)
        d3 as IntradayExitDecision.Exit
        assertEquals("TIME_STOP", d3.reason)
        assertEquals(100.6, d3.price, 1e-9)
    }

    @Test
    fun slDanTpBarSama_dinilaiSlKonservatif() {
        val p = posisi()
        val d = IntradayExitEngine.evaluate(
            bar = bar(100.0, 102.5, 96.5, 101.0),
            position = p,
            state = IntradayExitEngine.initialState(p),
            config = IntradayExitConfig.DEFAULT,
            sessionClose = false
        )
        assertTrue("Bar ambigu harus keluar", d is IntradayExitDecision.Exit)
        d as IntradayExitDecision.Exit
        assertTrue("Bar ambigu harus ditandai", d.ambiguousSameBar)
        assertEquals("AMBIGUOUS_SAME_BAR", d.reason)
        assertEquals("Ambigu dinilai di SL, bukan TP", 97.0, d.price, 1e-9)
    }

    @Test
    fun breakevenPrice_memakaiFeeDanSlippageDuaSisi() {
        val fees = agu.analys.config.FeeSchedule.configFor("TOKOCRYPTO", "USDT")
        val be = IntradayExitEngine.breakevenPrice(
            entry = 100.0,
            fees = fees,
            useMaker = false,
            slippagePct = 0.08
        )
        // Impas net Tokocrypto USDT ≈ 0,9735% di atas entry.
        assertEquals(100.9735, be, 0.01)
        assertTrue("Impas net harus di atas entry", be > 100.0)
    }
}
