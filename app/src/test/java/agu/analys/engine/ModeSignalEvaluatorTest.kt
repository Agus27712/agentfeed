package agu.analys.engine

import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.intraday.IntradayEvaluator
import agu.analys.engine.regime.MacroAnomalyDetector
import agu.analys.engine.swing.SwingEvaluator
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import agu.analys.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N6 + N17 — bukti bahwa skor dari seam tunggal **tidak bisa** lebih tinggi daripada skor engine.
 *
 * Sebelum perbaikan, `CandidateScanWorker` memanggil `SwingEvaluator.evaluate(...)` tanpa
 * `macroAnomalyResult`, lalu menulis hasilnya ke key `SYMBOL#SWING` yang sama dengan engine.
 * Notifikasi bisa menampilkan skor tanpa penalti anomali. Uji di bawah mengunci dua hal:
 *
 * 1. penalti anomali makro dihitung **di dalam** [ModeSignalEvaluator] (bukan oleh pemanggil);
 * 2. gerbang orderbook scalping tidak bisa dilewati — orderbook kosong ⇒ bukan BUY.
 *
 * Kontrol negatif: hapus `macroAnomalyResult = anomaly` di seam → uji SWING/INTRADAY GAGAL.
 */
class ModeSignalEvaluatorTest {

    // ── Fixture deterministik ────────────────────────────────────────────────

    /** Uptrend H1 dengan koreksi sehat + rejection candle (sama dengan SwingEvaluatorTest). */
    private fun qualifiedSwingHistory(): List<CandleBar> {
        val baseTime = 1_700_000_000_000L
        val history = mutableListOf<CandleBar>()
        var p = 800.0
        for (i in 0 until 150) {
            val open = p
            val close = p + 1.6
            history.add(CandleBar(baseTime + i * 3_600_000L, open, close + 0.5, open - 0.5, close, 2500.0))
            p = close
        }
        val peak = p
        val corrections = listOf(peak - 8.0, peak - 16.0, peak - 23.0, peak - 28.0)
        for ((idx, target) in corrections.withIndex()) {
            val open = p
            history.add(CandleBar(baseTime + (150 + idx) * 3_600_000L, open, open + 1.0, target - 1.0, target, 2000.0))
            p = target
        }
        val lastOpen = p
        val lastClose = p + 6.0
        history.add(CandleBar(baseTime + 154 * 3_600_000L, lastOpen, lastClose + 0.5, lastOpen - 2.0, lastClose, 12_000.0))
        return history
    }

    /** Uptrend H4 sederhana — cukup untuk membuat skor intraday > 0. */
    private fun intradayHistory(): List<CandleBar> {
        var p = 1000.0
        return (0 until 120).map { i ->
            val open = p
            val close = p + 1.2
            p = close
            CandleBar(1_700_000_000_000L + i * 14_400_000L, open, close + 1.0, open - 1.0, close, 1500.0)
        }
    }

    /**
     * D1 dengan pola *parabolic unwind*: baseline 600, puncak 2000 (pump 3,3×), lalu turun 19 hari.
     * `MacroAnomalyDetector` memberi penalti **-30** (cukup terlihat tanpa terpotong ke nol).
     */
    private fun parabolicDaily(): List<CandleBar> {
        val day = 86_400_000L
        val baseTime = 1_600_000_000_000L
        val out = mutableListOf<CandleBar>()
        for (i in 0 until 80) {
            out.add(CandleBar(baseTime + i * day, 600.0, 605.0, 595.0, 600.0, 1000.0))
        }
        out.add(CandleBar(baseTime + 80 * day, 500.0, 2000.0, 500.0, 1900.0, 5000.0))
        var p = 1900.0
        for (i in 1..19) {
            val next = p - 47.0
            out.add(CandleBar(baseTime + (80 + i) * day, p, p + 10.0, next - 10.0, next, 2000.0))
            p = next
        }
        return out
    }

    private fun series(n: Int, base: Double, drift: Double, amp: Double, freq: Double, step: Long): List<CandleBar> {
        var prev = base
        return (0 until n).map { i ->
            val c = base + i * drift + amp * kotlin.math.sin(i * freq)
            val o = prev
            prev = c
            CandleBar((i + 1) * step, o, maxOf(o, c) + base * 0.0015, minOf(o, c) - base * 0.0015, c, 1000.0)
        }
    }

    private fun bullishM1(): List<CandleBar> {
        val m1 = series(90, 1000.0, 0.3, 4.0, 0.8, 60_000L).toMutableList()
        val l = m1.last()
        m1.add(CandleBar(l.timestamp + 60_000, l.close, l.close + 4.0, l.close - 0.5, l.close + 3.5, 4500.0))
        return m1
    }

    private val bullishBids = listOf(OrderBookItem(price = 1000.0, amount = 30.0, total = 30_000.0, isBid = true))
    private val bullishAsks = listOf(OrderBookItem(price = 1001.0, amount = 10.0, total = 10_010.0, isBid = false))

    // ── N17: SWING ───────────────────────────────────────────────────────────

    @Test
    fun `SWING - penalti anomali makro dihitung di dalam seam`() {
        val h1 = qualifiedSwingHistory()
        val price = h1.last().close
        val d1 = parabolicDaily()
        val fees = TradingFeeConfig()

        val anomaly = MacroAnomalyDetector.evaluate(d1, price)
        assertEquals("fixture harus menghasilkan penalti -30", -30, anomaly.confidencePenalty)

        val viaSeam = ModeSignalEvaluator.evaluate(
            ModeSignalInput(
                mode = StrategyMode.SWING,
                symbol = "BTCIDR",
                price = price,
                nowMs = h1.last().timestamp,
                h1Candles = h1,
                d1Candles = d1,
                fees = fees
            )
        )
        assertNotNull(viaSeam)

        val engine = SwingEvaluator.evaluate(
            price = price,
            history = h1,
            fees = fees,
            macroAnomalyResult = anomaly,
            symbol = "BTCIDR"
        )
        val tanpaAnomali = SwingEvaluator.evaluate(
            price = price,
            history = h1,
            fees = fees,
            macroAnomalyResult = null,
            symbol = "BTCIDR"
        )

        assertEquals("seam harus identik dengan pemanggilan engine", engine.signal.confidence, viaSeam!!.signal.confidence)
        assertEquals(
            "N17: skor tanpa anomali harus lebih tinggi 30 poin dari skor ber-anomali",
            maxOf(0, tanpaAnomali.signal.confidence - 30),
            viaSeam.signal.confidence
        )
        assertTrue(
            "fixture harus punya skor cukup agar penalti terlihat (dapat ${tanpaAnomali.signal.confidence})",
            tanpaAnomali.signal.confidence >= 30
        )
    }

    // ── N17: INTRADAY ────────────────────────────────────────────────────────

    @Test
    fun `INTRADAY - parabolic unwind memaksa skor 0 dan HOLD`() {
        val h4 = intradayHistory()
        val price = h4.last().close
        val fees = TradingFeeConfig()

        val berAnomali = ModeSignalEvaluator.evaluate(
            ModeSignalInput(
                mode = StrategyMode.OFFICE_DAILY,
                symbol = "BTCIDR",
                price = price,
                nowMs = h4.last().timestamp,
                h4Candles = h4,
                d1Candles = parabolicDaily(),
                fees = fees
            )
        )
        val tanpaAnomali = ModeSignalEvaluator.evaluate(
            ModeSignalInput(
                mode = StrategyMode.OFFICE_DAILY,
                symbol = "BTCIDR",
                price = price,
                nowMs = h4.last().timestamp,
                h4Candles = h4,
                d1Candles = emptyList(),
                fees = fees
            )
        )

        assertNotNull(berAnomali)
        assertNotNull(tanpaAnomali)
        assertEquals("anomali makro harus memaksa confidence 0", 0, berAnomali!!.signal.confidence)
        assertEquals(SignalAction.HOLD, berAnomali.signal.action)
        assertTrue(
            "tanpa D1 penalti tidak ada, skor harus > 0 (dapat ${tanpaAnomali!!.signal.confidence})",
            tanpaAnomali.signal.confidence > 0
        )
        assertEquals(
            "paritas dengan evaluator intraday langsung",
            IntradayEvaluator.evaluate(
                price = price,
                history = h4,
                fees = fees,
                symbol = "BTCIDR"
            ).signal.confidence,
            tanpaAnomali.signal.confidence
        )
    }

    // ── N6: SCALPING tidak boleh melewati gerbang orderbook ──────────────────

    @Test
    fun `SCALPING - orderbook kosong tidak boleh menghasilkan BUY`() {
        val h1 = series(60, 900.0, 0.9, 12.0, 0.5, 3_600_000L)
        val m15 = series(60, 950.0, 0.6, 8.0, 0.7, 900_000L)
        // P0 — gate MTF 4 TF fail-closed tanpa M5; test ini menguji orderbook, bukan MTF.
        val m5 = series(60, 970.0, 0.5, 6.0, 0.6, 300_000L)
        val m1 = bullishM1()
        val price = m1.last().close

        fun input(bids: List<OrderBookItem>, asks: List<OrderBookItem>) = ModeSignalInput(
            mode = StrategyMode.SCALPING,
            symbol = "BTCIDR",
            price = price,
            nowMs = m1.last().timestamp,
            change24h = 2.0,
            h1Candles = h1,
            m15Candles = m15,
            m5Candles = m5,
            m1Candles = m1,
            formingVolume = 0.0,
            bids = bids,
            asks = asks
        )

        val denganOrderbook = ModeSignalEvaluator.evaluate(input(bullishBids, bullishAsks))
        assertNotNull(denganOrderbook)
        assertEquals(
            "fixture harus BUY saat orderbook nyata tersedia (audit: ${denganOrderbook!!.signal.reasoning})",
            SignalAction.BUY,
            denganOrderbook.signal.action
        )

        val tanpaOrderbook = ModeSignalEvaluator.evaluate(input(emptyList(), emptyList()))
        assertNotNull(tanpaOrderbook)
        assertFalse(
            "tanpa orderbook gerbang Step2 harus GAGAL — jangan hidupkan lagi jalur diagnostik",
            tanpaOrderbook!!.signal.action == SignalAction.BUY
        )
    }

    @Test
    fun `mode tanpa data candle mengembalikan null, bukan skor mode lain`() {
        assertNull(
            ModeSignalEvaluator.evaluate(
                ModeSignalInput(mode = StrategyMode.SWING, symbol = "BTCIDR", price = 1000.0, nowMs = 1L)
            )
        )
        assertNull(
            ModeSignalEvaluator.evaluate(
                ModeSignalInput(mode = StrategyMode.OFFICE_DAILY, symbol = "BTCIDR", price = 1000.0, nowMs = 1L)
            )
        )
        assertNull(
            ModeSignalEvaluator.evaluate(
                ModeSignalInput(mode = StrategyMode.SCALPING, symbol = "BTCIDR", price = 1000.0, nowMs = 1L)
            )
        )
    }

    @Test
    fun `nowMs tidak valid ditolak fail-closed, bukan ditutupi jam dinding`() {
        // Fallback `System.currentTimeMillis()` membuat hasil berubah menurut jam dinding
        // meski input sama (tidak deterministik). `nowMs <= 0` wajib null TANPA TERKECUALI —
        // termasuk saat data candle lengkap, karena di situlah fallback dulu menyelipkan hasil.
        val h1 = TestData.generateCandles(40, 1_400_000_000.0, 0.001)
        for (mode in StrategyMode.entries) {
            for (nowMs in listOf(0L, -1L)) {
                assertNull(
                    "nowMs=$nowMs pada $mode dengan data candle lengkap harus null " +
                        "(fail-closed deterministik)",
                    ModeSignalEvaluator.evaluate(
                        ModeSignalInput(
                            mode = mode,
                            symbol = "BTCIDR",
                            price = h1.last().close,
                            nowMs = nowMs,
                            h1Candles = h1,
                            h4Candles = h1
                        )
                    )
                )
            }
        }
    }
}
