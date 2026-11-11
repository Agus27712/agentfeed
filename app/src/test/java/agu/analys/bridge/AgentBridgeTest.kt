package agu.analys.bridge

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBridgeTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    @Test
    fun `serialisasi sinyal valid dan memuat field krusial`() {
        val signal = AgentSignalOutput(
            ts = 1730000000000L,
            exchange = "tokocrypto",
            symbol = "BTCUSDT",
            quote = "USDT",
            mode = "SCALPING",
            action = "BUY",
            confidence = 85.5f,
            price = 68500.0,
            tp1 = 69200.0,
            tp2 = 70000.0,
            sl = 67800.0,
            reason = "RSI oversold • Bullish orderbook imbalance",
            technical = TechnicalBreakdown(
                rsi = 28.5f,
                macd = "BULLISH_CROSS",
                volumeSpike = true,
                orderbookPressure = "BID_WALL"
            ),
            feeTruth = FeeTruthInfo(
                feeRate = 0.004044,
                netPrice = 68777.0,
                exchange = "tokocrypto"
            )
        )

        val encoded = json.encodeToString(signal)
        assertTrue(encoded.contains("\"symbol\":\"BTCUSDT\""))
        assertTrue(encoded.contains("\"confidence\":85.5"))
        assertTrue(encoded.contains("\"type\":\"signal\""))

        val decoded = json.decodeFromString<AgentSignalOutput>(encoded)
        assertEquals(signal.symbol, decoded.symbol)
        assertEquals(signal.price, decoded.price, 0.001)
        assertEquals(signal.confidence, decoded.confidence, 0.001f)
        assertNotNull(decoded.technical)
        assertEquals("BULLISH_CROSS", decoded.technical?.macd)
    }

    @Test
    fun `serialisasi portfolio valid dan memuat daftar aset`() {
        val portfolio = AgentPortfolioOutput(
            ts = 1730000000000L,
            exchange = "tokocrypto",
            totalIdrValue = 15000000.0,
            totalUsdtValue = 1000.0,
            assets = listOf(
                AgentAsset(
                    symbol = "BTC",
                    free = 0.01,
                    locked = 0.0,
                    avgBuyPrice = 67000.0,
                    currentPrice = 68500.0,
                    pnlPercent = 2.238,
                    pnlValue = 15.0
                )
            )
        )

        val encoded = json.encodeToString(portfolio)
        assertTrue(encoded.contains("\"totalIdrValue\":1.5E7") || encoded.contains("15000000"))
        assertTrue(encoded.contains("\"type\":\"portfolio\""))

        val decoded = json.decodeFromString<AgentPortfolioOutput>(encoded)
        assertEquals(1, decoded.assets.size)
        assertEquals("BTC", decoded.assets[0].symbol)
        assertEquals(2.238, decoded.assets[0].pnlPercent, 0.001)
    }

    @Test
    fun `AgentEventBus memancarkan sinyal ke subscriber flow`() = runTest {
        val signal = AgentSignalOutput(
            ts = System.currentTimeMillis(),
            exchange = "tokocrypto",
            symbol = "ETHIDR",
            quote = "IDR",
            mode = "INTRADAY",
            action = "BUY",
            confidence = 78f,
            price = 45000000.0,
            reason = "Test flow emission"
        )

        val job = launch {
            val received = AgentEventBus.signals.first { it.symbol == "ETHIDR" }
            assertEquals("ETHIDR", received.symbol)
            assertEquals("tokocrypto", received.exchange)
        }

        AgentEventBus.emitSignal(signal)
        job.join()
    }
}
