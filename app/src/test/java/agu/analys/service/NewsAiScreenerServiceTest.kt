package agu.analys.service

import agu.analys.config.AiProvider
import agu.analys.model.MarketTick
import agu.analys.model.NewsArticle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NewsAiScreenerServiceTest {

    @Test
    fun testParseCoinPicksFiltersAgainstValidBases() = runBlocking {
        val articles = listOf(
            NewsArticle(
                title = "Solana surges on record DEX volume and institutional inflows",
                source = "Cointelegraph"
            ),
            NewsArticle(
                title = "Cardano and Sui see major DeFi ecosystem expansion",
                source = "CoinDesk"
            ),
            NewsArticle(
                title = "RandomCoinXYZ skyrockets 500% on foreign exchange",
                source = "CryptoNews"
            )
        )

        val validBases = setOf("SOL", "ADA", "SUI", "BTC", "ETH")
        val liveTicks = mapOf(
            "SOLIDR" to MarketTick(symbol = "SOLIDR", price = 2400000.0, high24h = 2500000.0, low24h = 2300000.0, volume24h = 10000000.0, change24h = 4.5, timestamp = 0L)
        )

        val result = NewsAiScreenerService.screenCoinsFromNews(
            articles,
            validBases,
            liveTicks,
            AiProvider.GEMINI,
            ""
        )

        // Hanya koin yang masuk daftar valid yang boleh kembali
        assertTrue(result.picks.isNotEmpty())
        for (pick in result.picks) {
            assertTrue("Coin ${pick.baseSymbol} must be in the valid whitelist", validBases.contains(pick.baseSymbol))
            assertFalse("Foreign non-listing coin RandomCoinXYZ must NOT be included", pick.baseSymbol == "RANDOMCOINXYZ")
        }
    }
}
