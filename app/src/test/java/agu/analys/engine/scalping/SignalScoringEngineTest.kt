package agu.analys.engine.scalping

import agu.analys.model.CandleBar
import agu.analys.model.MarketRegime
import agu.analys.model.RegimeSnapshot
import agu.analys.model.ScalpSetupType
import agu.analys.model.ScoreBreakdown
import agu.analys.model.StructureBias
import agu.analys.model.StructureSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalScoringEngineTest {

    /** Uptrend M1 ending near [price]; skala [scale] untuk uji IDR vs USDT. */
    private fun uptrend(price: Double, n: Int = 40): List<CandleBar> =
        (1..n).map { i ->
            val close = price * (0.97 + i * 0.001)
            CandleBar(
                timestamp = i * 60_000L,
                open = close * 0.999,
                high = close * 1.001,
                low = close * 0.997,
                close = close,
                volume = 1000.0
            )
        }

    private fun trendingRegime(atrPct: Double = 0.8) = RegimeSnapshot(
        regime = MarketRegime.TRENDING_UP,
        adx = 28.0,
        chop = 34.0,
        atrPct = atrPct,
        emaAlignment = "bullish"
    )

    private fun bullishStructure() = StructureSnapshot(
        bias = StructureBias.BULLISH,
        pattern = "HH_HL",
        bos = true,
        strength = 80
    )

    private fun strongInput(price: Double = 1_000_000.0) = SignalScoringEngine.Input(
        price = price,
        structure = bullishStructure(),
        regime = trendingRegime(),
        setup = ScalpSetupType.BREAKOUT_RETEST,
        rvol = 2.0,
        buyPressure = 1.5,
        orderImbalance = 0.4,
        mtfAligned = 3,
        mtfTotal = 3,
        m1Candles = uptrend(price)
    )

    private fun weakInput() = SignalScoringEngine.Input(
        price = 1_000_000.0,
        structure = StructureSnapshot(bias = StructureBias.BEARISH, pattern = "LH_LL", strength = 20),
        regime = RegimeSnapshot(MarketRegime.RANGING, adx = 12.0, chop = 70.0, atrPct = 0.9),
        setup = ScalpSetupType.NONE,
        rvol = 0.5,
        buyPressure = 0.8,
        orderImbalance = -0.4,
        mtfAligned = 0,
        mtfTotal = 3
    )

    @Test
    fun total_equalsSumOfComponents() {
        val s = SignalScoringEngine.score(strongInput())
        val sum = s.structure + s.mtf + s.priceAction + s.volume +
            s.momentum + s.orderFlow + s.volatility
        assertEquals(sum.coerceIn(0, 100), s.total)
    }

    @Test
    fun category_thresholds() {
        // 39 → NO_TRADE
        assertEquals("NO_TRADE", ScoreBreakdown(structure = 25, mtf = 14).category)
        // 40 → WEAK
        assertEquals("WEAK", ScoreBreakdown(structure = 25, mtf = 15).category)
        // 59 → WEAK
        assertEquals("WEAK", ScoreBreakdown(structure = 25, mtf = 15, priceAction = 19).category)
        // 60 → WATCH
        assertEquals("WATCH", ScoreBreakdown(structure = 25, mtf = 15, priceAction = 20).category)
        // 74 → WATCH
        assertEquals(
            "WATCH",
            ScoreBreakdown(structure = 25, mtf = 15, priceAction = 20, volume = 14).category
        )
        // 75 → STRONG
        assertEquals(
            "STRONG",
            ScoreBreakdown(structure = 25, mtf = 15, priceAction = 20, volume = 15).category
        )
        // 89 → STRONG
        assertEquals(
            "STRONG",
            ScoreBreakdown(
                structure = 25, mtf = 15, priceAction = 20, volume = 15,
                momentum = 10, orderFlow = 4
            ).category
        )
        // 90 → VERY_STRONG
        assertEquals(
            "VERY_STRONG",
            ScoreBreakdown(
                structure = 25, mtf = 15, priceAction = 20, volume = 15,
                momentum = 10, orderFlow = 5
            ).category
        )
    }

    @Test
    fun strongSetup_scoresHigh() {
        val s = SignalScoringEngine.score(strongInput())
        assertTrue("total=${s.total}", s.total >= 75)
        assertTrue(s.category == "STRONG" || s.category == "VERY_STRONG")
    }

    @Test
    fun weakInput_isNoTrade() {
        val s = SignalScoringEngine.score(weakInput())
        assertTrue("total=${s.total}", s.total < 40)
        assertEquals("NO_TRADE", s.category)
    }

    @Test
    fun components_neverExceedMax() {
        val extreme = strongInput().copy(
            structure = bullishStructure().copy(
                strength = 500, choch = true, liquiditySweepDetected = true
            ),
            rvol = 50.0,
            buyPressure = 99.0,
            orderImbalance = 5.0,
            mtfAligned = 10,
            mtfTotal = 3
        )
        val s = SignalScoringEngine.score(extreme)
        assertTrue(s.structure in 0..SignalScoringEngine.MAX_STRUCTURE)
        assertTrue(s.mtf in 0..SignalScoringEngine.MAX_MTF)
        assertTrue(s.priceAction in 0..SignalScoringEngine.MAX_PRICE_ACTION)
        assertTrue(s.volume in 0..SignalScoringEngine.MAX_VOLUME)
        assertTrue(s.momentum in 0..SignalScoringEngine.MAX_MOMENTUM)
        assertTrue(s.orderFlow in 0..SignalScoringEngine.MAX_ORDER_FLOW)
        assertTrue(s.volatility in 0..SignalScoringEngine.MAX_VOLATILITY)
        assertTrue(s.total <= 100)
    }

    @Test
    fun emptyCandles_noCrash_momentumZero() {
        val s = SignalScoringEngine.score(strongInput().copy(m1Candles = emptyList()))
        assertEquals(0, s.momentum)
        assertTrue(s.reasons.any { it.contains("momentum", ignoreCase = true) })
    }

    @Test
    fun noOrderBook_orderFlowZero() {
        val s = SignalScoringEngine.score(strongInput().copy(hasOrderBook = false))
        assertEquals(0, s.orderFlow)
        assertTrue(s.reasons.any { it.contains("Order book kosong") })
    }

    @Test
    fun mtfUnavailable_mtfZero() {
        val s = SignalScoringEngine.score(strongInput().copy(mtfAligned = 0, mtfTotal = 0))
        assertEquals(0, s.mtf)
    }

    @Test
    fun invalidPrice_returnsZeroScore() {
        val s = SignalScoringEngine.score(strongInput().copy(price = 0.0))
        assertEquals(0, s.total)
        assertEquals("NO_TRADE", s.category)
    }

    @Test
    fun idrAndUsdtScale_sameScore() {
        val idr = SignalScoringEngine.score(strongInput(price = 1_000_000_000.0))
        val usdt = SignalScoringEngine.score(strongInput(price = 0.5))
        assertEquals(idr.total, usdt.total)
        assertEquals(idr.momentum, usdt.momentum)
        assertEquals(idr.priceAction, usdt.priceAction)
    }

    @Test
    fun reasons_inIndonesian_andNotEmpty() {
        val s = SignalScoringEngine.score(strongInput())
        assertEquals(7, s.reasons.size)
        assertTrue(s.reasons.any { it.startsWith("Struktur") })
        assertTrue(s.reasons.any { it.startsWith("Volume") })
        assertTrue(s.reasons.any { it.startsWith("Volatilitas") })
    }
}
