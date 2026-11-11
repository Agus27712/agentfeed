package agu.analys.engine.global

import agu.analys.model.CandleBar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcPulseAnalyzerTest {

    private fun candle(t: Long, open: Double, close: Double): CandleBar = CandleBar(
        timestamp = t,
        open = open,
        high = maxOf(open, close) + 0.2,
        low = minOf(open, close) - 0.2,
        close = close,
        volume = 1000.0
    )

    private fun steadyUptrend(): List<CandleBar> = (0 until 40).map { i ->
        val close = 100.0 + i * 0.5
        candle(i * 300_000L, close - 0.1, close)
    }

    /** Dua candle terakhir turun tajam dan close di bawah EMA20. */
    private fun sharpDump(): List<CandleBar> {
        val candles = (0 until 37).map { i ->
            val close = 100.0 + i * 0.3
            candle(i * 300_000L, close - 0.1, close)
        }.toMutableList()
        candles.add(candle(37 * 300_000L, 111.0, 111.6))
        candles.add(candle(38 * 300_000L, 112.0, 111.0))
        candles.add(candle(39 * 300_000L, 111.0, 104.0))
        return candles
    }

    @Test
    fun steadyUptrend_doesNotVeto() {
        val result = BtcPulseAnalyzer.evaluate(steadyUptrend(), config = BtcPulseAnalyzer.Config())
        assertFalse(result.vetoActive)
        assertTrue(result.sufficientData)
        assertTrue(result.btcPrice > 0.0)
    }

    @Test
    fun sharpDump_belowEmaAndReturnThreshold_vetoes() {
        val result = BtcPulseAnalyzer.evaluate(sharpDump(), config = BtcPulseAnalyzer.Config())
        assertTrue(result.reason, result.vetoActive)
        assertTrue(result.status == "DUMP_RISK")
        assertTrue(result.btcPrice < result.ema20)
    }

    @Test
    fun disabledVeto_computesStatusButNeverBlocks() {
        val result = BtcPulseAnalyzer.evaluate(
            sharpDump(),
            config = BtcPulseAnalyzer.Config(enabled = false)
        )
        assertFalse(result.vetoActive)
        assertTrue(result.reason.isNullOrBlank().not())
    }

    @Test
    fun atrCondition_vetoesWithTwoBearishOfThreeEvenWhenReturnRuleIsRelaxed() {
        val result = BtcPulseAnalyzer.evaluate(
            sharpDump(),
            config = BtcPulseAnalyzer.Config(
                returnVetoPct = -99.0,
                atrRatioVeto = 0.5
            )
        )
        assertTrue(result.reason, result.vetoActive)
        assertTrue(result.bearishBars >= 2)
        assertTrue(result.atrRatio > 0.5)
    }

    @Test
    fun insufficientData_doesNotInventVeto() {
        val result = BtcPulseAnalyzer.evaluate(steadyUptrend().take(10))
        assertFalse(result.vetoActive)
        assertFalse(result.sufficientData)
        assertTrue(result.status == "INSUFFICIENT_DATA")
    }

    @Test
    fun btcPairDetection_handlesNormalizedSymbols() {
        assertTrue(BtcPulseAnalyzer.isBtcPair("BTCUSDT"))
        assertTrue(BtcPulseAnalyzer.isBtcPair("btc/idr"))
        assertTrue(BtcPulseAnalyzer.isBtcPair("BTC_IDR"))
        assertFalse(BtcPulseAnalyzer.isBtcPair("ETHBTC"))
        assertFalse(BtcPulseAnalyzer.isBtcPair("ADAUSDT"))
    }

    @Test
    fun globalContextManager_mergesPulseAndClearsVetoWhenSafeAgain() {
        GlobalContextManager.resetForTest()
        val vetoed = GlobalContextManager.updateBtcPulse(sharpDump())
        assertTrue(vetoed.vetoActive)
        assertTrue(GlobalContextManager.context.value.isVetoActive)

        val safe = GlobalContextManager.updateBtcPulse(steadyUptrend())
        assertFalse(safe.vetoActive)
        assertFalse("Veto lama tidak boleh menempel", GlobalContextManager.context.value.isVetoActive)
        assertNotNull(GlobalContextManager.lastBtcPulse())
        GlobalContextManager.resetForTest()
    }

    @Test
    fun globalContextManager_doesNotPutIdrBtcPriceIntoUsdtField() {
        GlobalContextManager.resetForTest()
        GlobalContextManager.updateBtcPulse(steadyUptrend(), priceIsUsdt = false)
        org.junit.Assert.assertEquals(
            0.0, GlobalContextManager.context.value.btcPriceUsdt, 0.0001
        )

        val usdt = GlobalContextManager.updateBtcPulse(steadyUptrend(), priceIsUsdt = true)
        org.junit.Assert.assertEquals(
            usdt.btcPrice, GlobalContextManager.context.value.btcPriceUsdt, 0.0001
        )
        GlobalContextManager.resetForTest()
    }
}
