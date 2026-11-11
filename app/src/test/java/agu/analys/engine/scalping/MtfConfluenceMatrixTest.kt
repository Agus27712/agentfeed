package agu.analys.engine.scalping

import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MtfConfluenceMatrixTest {

    private fun candle(close: Double, t: Long) =
        CandleBar(t, close, close * 1.001, close * 0.999, close, 1000.0)

    private fun uptrend(n: Int, start: Double = 100.0): List<CandleBar> =
        (1..n).map { i ->
            val c = start + i * 0.8
            candle(c, i * 60_000L)
        }

    private fun downtrend(n: Int, start: Double = 100.0): List<CandleBar> =
        (1..n).map { i ->
            val c = start - i * 0.8
            candle(c, i * 60_000L)
        }

    @Test
    fun emptyBundle_allUnknown() {
        val m = MtfConfluenceMatrix.build(MtfConfluenceMatrix.CandleBundle())
        assertEquals(6, m.legs.size)
        assertEquals(0, m.knownCount)
        assertEquals(MtfConfluenceMatrix.TfBias.UNKNOWN, m.majorityBias)
        assertEquals("0/0", m.alignmentLabel)
    }

    @Test
    fun partialH1M15M1_uptrend_majorityBullish() {
        val up = uptrend(40)
        val m = MtfConfluenceMatrix.buildPartial(h1 = up, m15 = up, m1 = up)
        assertTrue(m.knownCount >= 3)
        assertTrue(
            "Expected bullish majority, got ${m.majorityBias} ${m.alignmentLabel}",
            m.majorityBias == MtfConfluenceMatrix.TfBias.BULLISH || m.bullishCount >= m.bearishCount
        )
        assertTrue(m.alignmentLabel.contains("bullish") || m.bullishCount > 0)
    }

    @Test
    fun downtrend_majorityBearishOrMixed() {
        val down = downtrend(40)
        val m = MtfConfluenceMatrix.buildPartial(h1 = down, m15 = down, m1 = down)
        assertTrue(m.knownCount >= 3)
        assertTrue(
            "Got ${m.majorityBias} ${m.alignmentLabel}",
            m.majorityBias == MtfConfluenceMatrix.TfBias.BEARISH ||
                m.bearishCount >= m.bullishCount
        )
    }

    @Test
    fun fromLegs_counts() {
        val legs = listOf(
            MtfConfluenceMatrix.TfLeg("1H", MtfConfluenceMatrix.TfBias.BULLISH),
            MtfConfluenceMatrix.TfLeg("15M", MtfConfluenceMatrix.TfBias.BULLISH),
            MtfConfluenceMatrix.TfLeg("1M", MtfConfluenceMatrix.TfBias.NEUTRAL),
            MtfConfluenceMatrix.TfLeg("4H", MtfConfluenceMatrix.TfBias.UNKNOWN)
        )
        val m = MtfConfluenceMatrix.fromLegs(legs)
        assertEquals(2, m.bullishCount)
        assertEquals(1, m.neutralCount)
        assertEquals(3, m.knownCount)
        assertEquals(MtfConfluenceMatrix.TfBias.BULLISH, m.majorityBias)
        assertEquals("2/3 bullish", m.alignmentLabel)
    }
}
