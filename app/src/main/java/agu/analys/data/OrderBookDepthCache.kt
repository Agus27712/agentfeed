package agu.analys.data

import agu.analys.model.OrderBookItem
import agu.analys.service.TokocryptoMarketService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache depth / orderbook real-time untuk menghitung rasio Orderbook Pressure (Bids vs Asks).
 * Terisolasi 100% per exchange tanpa unscoped fallback.
 */
object OrderBookDepthCache {

    private val cache = ConcurrentHashMap<String, Pair<List<OrderBookItem>, List<OrderBookItem>>>()
    private val lastFetchTime = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val _depthVersion = MutableStateFlow(0L)
    val depthVersion: StateFlow<Long> = _depthVersion.asStateFlow()

    private fun buildKey(symbol: String, exchange: String): String {
        return "${exchange.trim().uppercase()}_${normalizeSymbol(symbol)}"
    }

    fun clear() {
        cache.clear()
        lastFetchTime.clear()
        inFlight.clear()
        _depthVersion.value = System.currentTimeMillis()
    }

    fun getOrderBook(symbol: String, exchange: String = "TOKOCRYPTO"): Pair<List<OrderBookItem>, List<OrderBookItem>>? {
        val key = buildKey(symbol, exchange)
        return cache[key]
    }

    fun updateOrderBook(
        symbol: String,
        bids: List<OrderBookItem>,
        asks: List<OrderBookItem>,
        exchange: String = "TOKOCRYPTO"
    ) {
        if (bids.isEmpty() && asks.isEmpty()) return
        val key = buildKey(symbol, exchange)
        cache[key] = bids to asks
        lastFetchTime[key] = System.currentTimeMillis()
        _depthVersion.value = System.currentTimeMillis()
    }

    suspend fun fetchIfNeeded(symbol: String, exchange: String = "TOKOCRYPTO", force: Boolean = false) {
        val key = buildKey(symbol, exchange)
        val norm = normalizeSymbol(symbol)
        val now = System.currentTimeMillis()
        val last = lastFetchTime[key] ?: 0L

        if (!force && cache.containsKey(key) && (now - last < 30_000L)) {
            return
        }

        if (!inFlight.add(key)) return

        try {
            withContext(Dispatchers.IO) {
                val depth = TokocryptoMarketService.fetchOrderBook(norm, limit = 20)

                if (depth.first.isNotEmpty() || depth.second.isNotEmpty()) {
                    cache[key] = depth
                    lastFetchTime[key] = System.currentTimeMillis()
                    _depthVersion.value = System.currentTimeMillis()
                }
            }
        } catch (_: Exception) {
        } finally {
            inFlight.remove(key)
        }
    }

    fun calculatePressure(symbol: String, exchange: String = "TOKOCRYPTO"): Int? {
        val pair = getOrderBook(symbol, exchange) ?: return null
        val bids = pair.first
        val asks = pair.second
        if (bids.isEmpty() && asks.isEmpty()) return null

        val totalBids = bids.sumOf { it.amount }
        val totalAsks = asks.sumOf { it.amount }
        val sum = totalBids + totalAsks
        if (sum <= 0.0) return 0

        return (((totalBids - totalAsks) / sum) * 100.0).toInt().coerceIn(-100, 100)
    }

    private fun normalizeSymbol(symbol: String): String {
        return symbol.replace("/", "").replace("_", "").lowercase()
    }
}
