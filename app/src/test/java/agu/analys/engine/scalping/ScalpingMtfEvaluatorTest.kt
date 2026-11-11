package agu.analys.engine.scalping

import agu.analys.engine.TestData
import agu.analys.model.CandleBar
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import org.junit.Assert.*
import org.junit.Test

class ScalpingMtfEvaluatorTest {

    @Test
    fun testEvaluateWithBullishTrend() {
        // Generate bullish trend (0.001 per candle)
        val h1 = TestData.generateCandles(100, 900.0, 0.001)
        val m15 = TestData.generateCandles(100, 950.0, 0.0005)
        val m1 = TestData.generateCandles(100, 980.0, 0.0002).toMutableList()
        // Konsolidasi sehat sebelum entry agar RSI tidak jenuh beli (overbought >= 80)
        val lastClose = m1.last().close
        val time = m1.last().timestamp
        m1.add(CandleBar(time + 60000, lastClose, lastClose + 1.0, lastClose - 2.0, lastClose - 1.0, 1500.0))
        m1.add(CandleBar(time + 120000, lastClose - 1.0, lastClose + 1.0, lastClose - 2.0, lastClose - 0.5, 1600.0))
        m1.add(CandleBar(time + 180000, lastClose - 0.5, lastClose + 4.0, lastClose - 1.0, lastClose + 3.0, 4000.0))
        val price = m1.last().close

        val result = ScalpingMtfEvaluator.evaluate(price, h1, m15, m1)
        
        assertNotNull("Result should not be null", result)
        assertTrue("Confidence should be positive in bullish trend (got ${result?.signal?.confidence})", (result?.signal?.confidence ?: 0) > 0)
    }

    @Test
    fun testEvaluateWithBearishTrend() {
        // Generate bearish trend (-0.001 per candle)
        val h1 = TestData.generateCandles(100, 1100.0, -0.001)
        val m15 = TestData.generateCandles(100, 1050.0, -0.0005)
        val m1 = TestData.generateCandles(100, 1010.0, -0.0001)
        val price = m1.last().close

        val result = ScalpingMtfEvaluator.evaluate(price, h1, m15, m1)
        
        assertNotNull("Result should not be null", result)
        assertEquals("Action should be HOLD in bearish trend", SignalAction.HOLD, result!!.signal.action)
        assertTrue("Confidence should be below 70 in bearish trend", result.signal.confidence < 70)
    }

    @Test
    fun testEvaluateWithInsufficientData() {
        val price = 1000.0
        val h1 = TestData.generateCandles(10, 1000.0)
        val m15 = TestData.generateCandles(10, 1000.0)
        val m1 = TestData.generateCandles(10, 1000.0)

        val result = ScalpingMtfEvaluator.evaluate(price, h1, m15, m1)
        assertNull("Result should be null with insufficient data", result)
    }

    // ── P1: akar EARLY_ENTRY ────────────────────────────────────────────────────────────────

    /** M1 benar-benar datar: tidak ada sweep, breakout, retest, maupun pullback. */
    private fun flat(count: Int, price: Double, startTime: Long): List<CandleBar> =
        List(count) { i ->
            CandleBar(
                timestamp = startTime + i * 60_000L,
                open = price,
                high = price * 1.0004,
                low = price * 0.9996,
                close = price,
                volume = 1000.0
            )
        }

    /** M15/H1 menurun landai: resistance jelas berada di atas harga, jadi ruang naik terbuka. */
    private fun declining(count: Int, from: Double, to: Double, startTime: Long): List<CandleBar> {
        val step = (to - from) / (count - 1)
        return List(count) { i ->
            val p = from + step * i
            CandleBar(
                timestamp = startTime + i * 60_000L,
                open = p,
                high = p * 1.001,
                low = p * 0.999,
                close = p,
                volume = 1000.0
            )
        }
    }

    /**
     * Invarian P1 di level engine: `ScalpingStage.EARLY_ENTRY` **wajib** disertai pola scalping
     * valid. Skenario di bawah adalah persis kondisi yang dulu salah dilabeli: tekanan orderbook
     * sudah memadai (`step2Ok`) tetapi belum ada pola apa pun (`setup == NONE`).
     *
     * Sebelum perbaikan, `early = !ready && !isDangerous && step2Ok` bernilai `true`, sehingga
     * engine memancarkan `EARLY_ENTRY` → `SignalLifecycleManager` menaikkannya ke `DETECTED` →
     * radar menampilkan "TERDETEKSI" sementara kartu setup menampilkan "NONE".
     */
    @Test
    fun testInvariant_EarlyEntryRequiresSetup() {
        val t0 = 1_700_000_000_000L
        val m1 = flat(100, 1000.0, t0)
        val m15 = declining(100, 1100.0, 1000.0, t0)
        val h1 = declining(100, 1150.0, 1000.0, t0)

        val result = ScalpingMtfEvaluator.evaluate(
            price = m1.last().close,
            h1Candles = h1,
            m15Candles = m15,
            m1Candles = m1,
            // Orderbook dianggap tersedia & memadai — inilah satu-satunya syarat yang dulu cukup
            // untuk memicu EARLY_ENTRY.
            diagnosticIgnoreOrderBookWhenUnavailable = true,
            // Test ini menguji invarian setup, bukan gerbang MTF/BTC. Matikan keduanya supaya
            // pasar datar tidak ditolak lebih awal oleh alasan yang berbeda.
            config = ScalpingRuntimeConfig(mtfMinBullish = 0, btcVetoEnabled = false)
        )

        assertNotNull("Evaluator harus mengembalikan hasil", result)
        val signal = result!!.signal

        assertEquals(
            "Pasar datar tanpa pola harus menghasilkan setup NONE",
            "NONE", signal.scalpingSetup
        )
        assertTrue(
            "Prasyarat skenario: tekanan orderbook harus lolos (step2Ok) agar test tidak hampa",
            signal.mtf.setupOk
        )
        assertNotEquals(
            "Tanpa pola, stage tidak boleh EARLY_ENTRY — ini akar bug radar vs setup",
            ScalpingStage.EARLY_ENTRY, signal.scalpingStage
        )
        assertEquals(
            "Orderbook mendukung tetapi pola belum ada → MEMANTAU (WATCH)",
            ScalpingStage.WATCH, signal.scalpingStage
        )
        assertEquals("Tanpa pola, aksi harus HOLD", SignalAction.HOLD, signal.action)
        assertEquals(
            "Judul status harus jujur menyebut setup belum ada",
            "ORDERBOOK OK (2/4) · SETUP BELUM ADA", signal.mtf.statusTitle
        )
    }
}
