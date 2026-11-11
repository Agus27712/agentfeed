package agu.analys.util

import agu.analys.config.MarketDataSource
import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.model.MarketTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRankingTest {

    private fun tick(sym: String, price: Double, vol: Double) =
        MarketTick(sym, price, price, price, vol, 0.0)

    @Test
    fun usdtVolume_isConvertedToIdr() {
        assertEquals(16_000_000.0, DashboardRanking.volumeInIdr("BTCUSDT", 1000.0, 16000.0), 0.001)
        assertEquals(5_000.0, DashboardRanking.volumeInIdr("BTCIDR", 5000.0, 16000.0), 0.001)
        assertEquals(16_000_000.0, DashboardRanking.volumeInIdr("btc_usdt", 1000.0, 16000.0), 0.001)
    }

    @Test
    fun invalidRate_returnsZero_noHardcodedFallback() {
        // Tanpa kurs valid hasilnya 0 (bukan tebakan 16.000)
        assertEquals(0.0, DashboardRanking.safeRate(0.0), 0.0)
        assertEquals(0.0, DashboardRanking.safeRate(10.0), 0.0)
        assertEquals(16_100.0, DashboardRanking.safeRate(16_100.0), 0.0)
    }

    @Test
    fun rank_mixesQuotesByIdrVolume() {
        val ticks = listOf(
            tick("AAAIDR", 1000.0, 2_000_000_000.0),   // 2 miliar IDR
            tick("BBBUSDT", 0.5, 500_000.0),           // 500rb USDT = 8 miliar IDR
            tick("CCCIDR", 1000.0, 100_000_000.0)
        )
        val ranked = DashboardRanking.rankByVolume(ticks, 16000.0).map { it.symbol }
        assertEquals(listOf("BBBUSDT", "AAAIDR", "CCCIDR"), ranked)
    }

    @Test
    fun rank_removesDuplicateSymbols() {
        val ticks = listOf(tick("BTCUSDT", 1.0, 10.0), tick("BTC_USDT", 1.0, 10.0))
        assertEquals(1, DashboardRanking.rankByVolume(ticks, 16000.0).size)
    }

    @Test
    fun priceFloor_onlyForIdr() {
        assertTrue(DashboardRanking.passesPriceFloor(tick("DOGEUSDT", 0.12, 1.0)))
        assertFalse(DashboardRanking.passesPriceFloor(tick("XYZIDR", 3.0, 1.0)))
        assertTrue(DashboardRanking.passesPriceFloor(tick("XYZIDR", 300.0, 1.0)))
        assertFalse(DashboardRanking.passesPriceFloor(tick("ABCUSDT", 0.0, 1.0)))
    }

    @Test
    fun toko_unknownSymbol_notRankable_failClosed() {
        // TONUSDT lolos threshold harga/volume USDT, tapi tak dikenal discovery -> gugur.
        assertFalse(
            DashboardRanking.isRankable(
                MarketDataSource.TOKOCRYPTO, tick("TONUSDT", 10.0, 50_000.0)
            )
        )
    }

    @Test
    fun toko_knownActiveSymbol_rankable() {
        // BTCIDR ada di metadata bawaan dengan spotTradingEnable=true.
        assertTrue(
            DashboardRanking.isRankable(
                MarketDataSource.TOKOCRYPTO, tick("BTCIDR", 1_700_000_000.0, 5_000_000_000.0)
            )
        )
    }

    @Test
    fun toko_disabledSymbol_notRankable() {
        assertTrue(TokocryptoSymbolRepository.setSpotTradingForTest("BTCIDR", false))
        try {
            assertFalse(
                DashboardRanking.isRankable(
                    MarketDataSource.TOKOCRYPTO, tick("BTCIDR", 1_700_000_000.0, 5_000_000_000.0)
                )
            )
        } finally {
            assertTrue(TokocryptoSymbolRepository.setSpotTradingForTest("BTCIDR", true))
        }
    }
}
