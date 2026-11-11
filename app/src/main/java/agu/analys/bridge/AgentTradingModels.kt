package agu.analys.bridge

import kotlinx.serialization.Serializable

/**
 * Model data serialisasi untuk Agent-Assisted Trading (Agent Bridge V3).
 */

@Serializable
data class AgentTradeRequest(
    val symbol: String,
    val side: String, // "BUY" atau "SELL"
    val quoteAmount: Double? = null, // Nominal beli dalam mata uang kuotasi (contoh: USDT atau IDR)
    val quantity: Double? = null, // Jumlah koin (khususnya untuk SELL atau BUY berbasis koin)
    val price: Double? = null, // Harga limit (0.0 atau null = pakai harga pasar terkini)
    val type: String? = null, // "LIMIT" atau "MARKET"
    val autoLimitTp1: Double? = null,
    val autoLimitTp2: Double? = null,
    val isSimulation: Boolean = false,
    val exchange: String = "TOKOCRYPTO"
)

@Serializable
data class AgentTradeResponse(
    val success: Boolean,
    val message: String,
    val orderId: String? = null,
    val clientOrderId: String? = null,
    val exchange: String = "TOKOCRYPTO",
    val symbol: String,
    val side: String,
    val price: Double = 0.0,
    val quantity: Double = 0.0,
    val status: String = "UNKNOWN", // "NEW", "FILLED", "PARTIALLY_FILLED", "REJECTED", "SIMULATED"
    val executedQty: Double = 0.0,
    /**
     * Harga acuan yang dikirim ke bursa saat order dibuat (2026-11-06).
     * `price` di atas kini harga acuan yang sama, tapi untuk order riil keduanya berbeda jika
     * record fill memuat harga ketikan pemanggil; dipisah supaya agen bisa membedakan
     * "harga yang diminta" vs "harga acuan model aplikasi".
     */
    val requestedPrice: Double? = null,
    val isSimulation: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class AgentCancelRequest(
    val orderId: String,
    val symbol: String,
    val isSimulation: Boolean = false,
    val exchange: String = "TOKOCRYPTO"
)

@Serializable
data class AgentCancelResponse(
    val success: Boolean,
    val message: String,
    val orderId: String,
    val symbol: String,
    val exchange: String = "TOKOCRYPTO",
    val isSimulation: Boolean = false
)

@Serializable
data class AgentOrderStatusResponse(
    val success: Boolean,
    val message: String,
    val orderId: String,
    val clientOrderId: String? = null,
    val exchange: String = "TOKOCRYPTO",
    val symbol: String,
    val side: String = "",
    val price: Double = 0.0,
    val origQty: Double = 0.0,
    val executedQty: Double = 0.0,
    val status: String = "UNKNOWN",
    val time: Long = 0L,
    val isSimulation: Boolean = false
)

@Serializable
data class AgentTradingStatusResponse(
    val isBridgeRunning: Boolean,
    val host: String,
    val port: Int,
    val exchange: String,
    val isRealBuyEnabled: Boolean,
    val isPinAuthenticated: Boolean,
    val isSimulationAvailable: Boolean,
    val publicIp: String? = null,
    val freeBalances: Map<String, Double> = emptyMap(),
    val totalBalances: Map<String, Double> = emptyMap(),
    /** Versi kontrak API; mirror `AgentMarketDataProvider.API_VERSION`. */
    val apiVersion: String = "2026-11-08",
    val quoteAssets: List<String> = listOf("USDT", "IDR"),
    val supportedIntervals: List<String> = listOf("1m", "5m", "15m", "1h", "4h", "1d"),
    val capabilitiesUrl: String = "/api/v1/capabilities",
    val marketDataUrl: String = "/api/v1/market/context?symbol=",
    val serverTime: Long = System.currentTimeMillis()
)
