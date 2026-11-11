package agu.analys.util

import agu.analys.model.MarketTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MarketTickMergeTest {

    private fun tick(sym: String, price: Double, ts: Long) =
        MarketTick(sym, price, price, price, 100.0, 0.0, ts)

    @Test
    fun newerTick_replacesOlder() {
        val base = mapOf("BTCIDR" to tick("BTCIDR", 100.0, 1_000L))
        val merged = MarketTickMerge.newest(base, mapOf("BTCIDR" to tick("BTCIDR", 120.0, 2_000L)))
        assertEquals(120.0, merged["BTCIDR"]!!.price, 0.0)
    }

    @Test
    fun olderTick_doesNotOverwriteNewer() {
        val base = mapOf("BTCIDR" to tick("BTCIDR", 100.0, 5_000L))
        val merged = MarketTickMerge.newest(base, mapOf("BTCIDR" to tick("BTCIDR", 90.0, 1_000L)))
        assertEquals(100.0, merged["BTCIDR"]!!.price, 0.0)
    }

    @Test
    fun newSymbol_isAdded() {
        val base = mapOf("BTCIDR" to tick("BTCIDR", 100.0, 1_000L))
        val merged = MarketTickMerge.newest(base, mapOf("ETHIDR" to tick("ETHIDR", 50.0, 1_000L)))
        assertEquals(2, merged.size)
        assertEquals(50.0, merged["ETHIDR"]!!.price, 0.0)
    }

    @Test
    fun emptyIncoming_returnsSameInstance() {
        val base = mapOf("BTCIDR" to tick("BTCIDR", 100.0, 1_000L))
        assertSame(base, MarketTickMerge.newest(base, emptyMap()))
    }

    @Test
    fun unchangedEntries_returnsSameInstance() {
        val t = tick("BTCIDR", 100.0, 1_000L)
        val base = mapOf("BTCIDR" to t)
        assertSame(base, MarketTickMerge.newest(base, mapOf("BTCIDR" to t)))
    }
}
