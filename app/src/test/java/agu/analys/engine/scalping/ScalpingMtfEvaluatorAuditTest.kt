package agu.analys.engine.scalping

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import agu.analys.model.SignalAction
import org.junit.Assert.*
import org.junit.Test

/**
 * FASE 2 & 7 + P1.4 — Unit test kasus edge evaluator scalping (pipeline KriptoYoi):
 *  1. Orderbook kosong -> ditahan sebagai HOLD (STEP2_ORDERBOOK_EMPTY), bukan lolos semu.
 *  2. Orderbook stale (>30s) -> ditolak sebagai HOLD (STEP2_ORDERBOOK_STALE).
 *  3. Momentum T0 vs T1 -> T0 ditahan (menunggu orderbook), T1 terpicu BUY setelah konfirmasi.
 *  4. Checkpoint 4 (Net R:R) -> terbukti secara aljabar mencapai Net R:R >= 1.05 dengan fee default.
 *  5. P1.4: tanpa setup valid -> WAIT (STEP3_NO_SETUP); audit membawa skor, setup, dan arah.
 */
class ScalpingMtfEvaluatorAuditTest {

    // ---------------------------------------------------------------------
    // Data deterministik (BUKAN random) supaya hasil test selalu sama setiap run.
    // ---------------------------------------------------------------------

    private fun flatCandles(count: Int, price: Double): List<CandleBar> {
        var t = 0L
        return (0 until count).map {
            t += 60_000L
            CandleBar(t, price, price + 2.0, price - 2.0, price, 1000.0)
        }
    }

    /**
     * Skenario tren naik deterministik (BUKAN random): drift + gelombang sinus, dengan candle
     * breakout bervolume besar di akhir. Memberi struktur M15 (>= 40 candle) sehingga pipeline
     * mendeteksi setup valid, skor >= 60, dan risk plan dengan Net R:R >= 1.15 (Tokocrypto).
     */
    private fun series(
        n: Int, base: Double, drift: Double, amp: Double, freq: Double, step: Long
    ): List<CandleBar> {
        var prev = base
        return (0 until n).map { i ->
            val c = base + i * drift + amp * kotlin.math.sin(i * freq)
            val o = prev
            prev = c
            CandleBar((i + 1) * step, o, maxOf(o, c) + base * 0.0015, minOf(o, c) - base * 0.0015, c, 1000.0)
        }
    }

    private fun bullishM1Candles(): List<CandleBar> {
        val m1 = series(90, 1000.0, 0.3, 4.0, 0.8, 60_000L).toMutableList()
        val l = m1.last()
        m1.add(CandleBar(l.timestamp + 60_000, l.close, l.close + 4.0, l.close - 0.5, l.close + 3.5, 4500.0))
        return m1
    }

    private val h1 = series(60, 900.0, 0.9, 12.0, 0.5, 3_600_000L)
    private val m15 = series(60, 950.0, 0.6, 8.0, 0.7, 900_000L)
    // P0 — pipeline scalping sekarang memakai 4 TF. M5 harus ada, kalau tidak gerbang MTF
    // fail-closed dan test checkpoint lain kehilangan makna.
    private val m5 = series(60, 970.0, 0.5, 6.0, 0.6, 300_000L)
    private val flatH1 = flatCandles(20, 1000.0)
    private val flatM15 = flatCandles(20, 1000.0)
    private val bullishBids = listOf(OrderBookItem(price = 1000.0, amount = 30.0, total = 30.0 * 1000.0, isBid = true))
    private val bullishAsks = listOf(OrderBookItem(price = 1001.0, amount = 10.0, total = 10.0 * 1001.0, isBid = false))

    // ---------------------------------------------------------------------
    // VERIFIKASI FASE 6: Checkpoint 4 (Net R:R >= 1.05) tercapai dengan fee default
    // ---------------------------------------------------------------------

    @Test
    fun `FASE 6 - Checkpoint4 NetRR sekarang dapat tercapai secara aljabar dengan fee default`() {
        val fees = TradingFeeConfig()
        val price = 1_000_000.0
        val totalCostPct = (fees.buyTakerPct + fees.sellTakerPct) + (2 * 0.08)
        val targetNetRr = 1.25

        var stopPct = 0.5
        while (stopPct <= 3.0) {
            val sl = price * (1.0 - stopPct / 100.0)
            val requiredGrossRewardPct = (targetNetRr * (stopPct + totalCostPct) + totalCostPct)
            val tp2 = price * (1.0 + maxOf(1.5, requiredGrossRewardPct) / 100.0)
            val feeResult = FeeCalculator.roundTrip(price, sl, tp2, fees, false, 0.08)
            assertTrue("Net R:R harus >= 1.05 untuk stopPct $stopPct", feeResult.netRr >= 1.05)
            stopPct += 0.25
        }
    }

    @Test
    fun `full pipeline - setup sebagus mungkin (step1-4 lolos) menghasilkan sinyal BUY`() {
        val price = bullishM1Candles().last().close
        val result = ScalpingMtfEvaluator.evaluate(
            price = price,
            h1Candles = h1,
            m15Candles = m15,
            m1Candles = bullishM1Candles(), m5Candles = m5,
            bids = bullishBids,
            asks = bullishAsks,
            symbol = "BTCIDR"
        )

        assertNotNull(result)
        val audit = result!!.audit
        assertTrue("step1 (danger/room to grow) seharusnya lolos", audit.step1Ok)
        assertTrue("step2 (buy pressure orderbook nyata) seharusnya lolos", audit.step2Ok)
        assertTrue("step3 (VWAP/VSA) seharusnya lolos", audit.step3Ok)
        assertTrue("step4 (RR) sekarang LOLOS berkat rumus aljabar presisi", audit.step4Ok)
        assertEquals(SignalAction.BUY, result.signal.action)
        assertNull(audit.rejectionReason)
    }

    // ---------------------------------------------------------------------
    // Kasus edge #1: Orderbook kosong
    // ---------------------------------------------------------------------

    @Test
    fun `FASE 6 - orderbook kosong ditahan rapi di Checkpoint2 sebagai HOLD, bukan lolos semu`() {
        val price = bullishM1Candles().last().close
        val result = ScalpingMtfEvaluator.evaluate(
            price = price,
            h1Candles = h1,
            m15Candles = m15,
            m1Candles = bullishM1Candles(), m5Candles = m5,
            bids = emptyList(),
            asks = emptyList(),
            symbol = "BTCIDR"
        )

        assertNotNull(result)
        val audit = result!!.audit
        assertTrue(audit.isOrderBookEmpty)
        assertFalse("step2 harus GAGAL saat orderbook kosong", audit.step2Ok)
        assertEquals("STEP2_ORDERBOOK_EMPTY", audit.rejectionReason)
        assertEquals(SignalAction.HOLD, result.signal.action)
    }

    // ---------------------------------------------------------------------
    // Kasus edge #2: Orderbook stale (basi)
    // ---------------------------------------------------------------------

    @Test
    fun `FASE 6 - orderbook stale 40 detik ditolak di Checkpoint2, orderbook fresh lolos`() {
        val price = bullishM1Candles().last().close

        val fresh = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = h1, m15Candles = m15, m1Candles = bullishM1Candles(), m5Candles = m5,
            bids = bullishBids, asks = bullishAsks, symbol = "BTCIDR", orderBookAgeMs = 0L
        )
        val stale = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = h1, m15Candles = m15, m1Candles = bullishM1Candles(), m5Candles = m5,
            bids = bullishBids, asks = bullishAsks, symbol = "BTCIDR", orderBookAgeMs = 40_000L
        )

        assertNotNull(fresh); assertNotNull(stale)
        assertEquals(0L, fresh!!.audit.orderBookAgeMs)
        assertEquals(40_000L, stale!!.audit.orderBookAgeMs)
        assertTrue("Orderbook fresh (<30s) harus lolos step2", fresh.audit.step2Ok)
        assertFalse("Orderbook stale (>30s) harus ditolak di step2", stale.audit.step2Ok)
        assertEquals("STEP2_ORDERBOOK_STALE", stale.audit.rejectionReason)
        assertEquals(SignalAction.BUY, fresh.signal.action)
        assertEquals(SignalAction.HOLD, stale.signal.action)
    }

    // ---------------------------------------------------------------------
    // Kasus edge #3: Momentum datang sebelum orderbook tersedia
    // ---------------------------------------------------------------------

    @Test
    fun `FASE 6 - breakout T0 tanpa orderbook dibedakan dengan jelas dari T1 dengan orderbook`() {
        val price = bullishM1Candles().last().close

        // T0: harga breakout, orderbook BELUM tersedia sama sekali
        val t0 = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = h1, m15Candles = m15, m1Candles = bullishM1Candles(), m5Candles = m5,
            bids = emptyList(), asks = emptyList(), symbol = "BTCIDR"
        )
        // T1: 1 detik kemudian, orderbook sudah tersedia dan bullish
        val t1 = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = h1, m15Candles = m15, m1Candles = bullishM1Candles(), m5Candles = m5,
            bids = bullishBids, asks = bullishAsks, symbol = "BTCIDR", orderBookAgeMs = 1000L
        )

        assertNotNull(t0); assertNotNull(t1)
        assertFalse("T0 tanpa orderbook harus ditahan di step2", t0!!.audit.step2Ok)
        assertTrue("T1 dengan orderbook bullish harus lolos step2", t1!!.audit.step2Ok)
        assertEquals(SignalAction.HOLD, t0.signal.action)
        assertEquals(SignalAction.BUY, t1.signal.action)
    }

    // ---------------------------------------------------------------------
    // P1.4 — pipeline: setup, skor, arah, risk plan
    // ---------------------------------------------------------------------

    @Test
    fun `P1_4 - tanpa struktur M15 dan setup, arah WAIT dengan alasan STEP3_NO_SETUP`() {
        val price = bullishM1Candles().last().close
        val result = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = flatH1, m15Candles = flatM15, m1Candles = bullishM1Candles(), m5Candles = m5,
            bids = bullishBids, asks = bullishAsks, symbol = "BTCIDR"
        )

        assertNotNull(result)
        assertEquals(SignalAction.HOLD, result!!.signal.action)
        assertEquals("STEP3_NO_SETUP", result.audit.rejectionReason)
        assertEquals("NONE", result.audit.setup)
        assertEquals("WAIT", result.audit.direction)
        assertFalse(result.audit.step3Ok)
    }

    @Test
    fun `P1_4 - audit dan scalping signal membawa skor, setup, dan arah LONG`() {
        val price = bullishM1Candles().last().close
        val result = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = h1, m15Candles = m15, m1Candles = bullishM1Candles(), m5Candles = m5,
            bids = bullishBids, asks = bullishAsks, symbol = "BTCIDR"
        )

        assertNotNull(result)
        val r = result!!
        assertEquals(SignalAction.BUY, r.signal.action)
        assertEquals("LONG", r.audit.direction)
        assertTrue("skor >= ${ScalpingMtfEvaluator.MIN_SCORE_LONG}", r.audit.score >= ScalpingMtfEvaluator.MIN_SCORE_LONG)
        assertNotEquals("NONE", r.audit.setup)
        assertEquals(agu.analys.model.SignalDirection.LONG, r.scalping.direction)
        assertNotNull(r.scalping.risk)
        assertEquals(r.audit.score, r.scalping.score.total)
        assertEquals(r.audit.score, r.signal.confidence)
        assertTrue(r.scalping.reasoning.isNotEmpty())
    }

    @Test
    fun `P1_4 - SL di bawah harga, TP1 dan TP2 di atas harga, Net RR minimal 1_15`() {
        val price = bullishM1Candles().last().close
        val r = ScalpingMtfEvaluator.evaluate(
            price = price, h1Candles = h1, m15Candles = m15, m1Candles = bullishM1Candles(), m5Candles = m5,
            bids = bullishBids, asks = bullishAsks, symbol = "BTCIDR"
        )!!

        assertTrue(r.signal.stopLoss < price)
        assertTrue(r.signal.targetPrice1 > price)
        assertTrue(r.signal.targetPrice2 > r.signal.targetPrice1)
        assertTrue("netRr=${r.audit.rr}", r.audit.rr >= 1.15)
    }

    @Test
    fun `P1_4 - data M1 kurang dari 20 candle tidak crash dan hasilnya null`() {
        val result = ScalpingMtfEvaluator.evaluate(
            price = 1000.0, h1Candles = h1, m15Candles = m15, m1Candles = bullishM1Candles().take(10)
        )
        assertNull(result)
    }
}
