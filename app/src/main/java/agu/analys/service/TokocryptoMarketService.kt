package agu.analys.service

import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.Timeframe
import agu.analys.model.TradeStreamItem
import agu.analys.network.NetworkClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Service REST API Resmi Tokocrypto dengan Routing Symbol Type 1 (MBX) & Type 3 (NextMe)
 *
 * Endpoint Resmi:
 * - General / Symbols: https://www.tokocrypto.com
 * - Type 1 Market Data: https://www.tokocrypto.site/api/v3
 * - Type 3 Market Data: https://cloudme-toko.2meta.app/api/v1 & /open/v1/market/trades
 */
object TokocryptoMarketService {
    private val client get() = NetworkClientProvider.marketClient

    private val tradeTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Jakarta"))

    private val rateMutex = Mutex()
    private val lastRequestAt = AtomicLong(0L)
    private const val MIN_INTERVAL_MS = 100L
    private const val MAX_RETRIES = 2

    // Base URLs
    private const val TOKOCRYPTO_GENERAL_URL = "https://www.tokocrypto.com"
    private const val TOKOCRYPTO_TYPE1_MARKET_URL = "https://www.tokocrypto.site/api/v3"
    private const val TOKOCRYPTO_TYPE3_MARKET_URL = "https://cloudme-toko.2meta.app/api/v1"


    fun isBidrSymbol(sym: String): Boolean {
        val s = sym.uppercase().trim()
        if (s == "BIDR") return true
        if (s.startsWith("BIDR_") || s.startsWith("BIDRUSDT") || s.startsWith("BIDRIDR") || s.startsWith("BIDRBTC")) return true
        if (s.contains("_BIDR")) return true
        if (s.endsWith("BIDR")) {
            if (s == "BNBIDR" || s == "SHIBIDR") return false
            return true
        }
        return false
    }

    fun isIdrOrUsdtPair(sym: String): Boolean {
        if (isBidrSymbol(sym)) return false
        val s = sym.uppercase().trim().replace("_", "")
        return s.endsWith("IDR") || s.endsWith("USDT")
    }

    fun toTokocryptoPair(symbol: String): String {
        val s = symbol.trim().uppercase().replace("/", "").replace("-", "")
        if (s.contains("_")) {
            return s.replace("_BIDR", "_IDR").replace("BIDR_", "IDR_")
        }
        return when {
            s.endsWith("USDT") -> s.removeSuffix("USDT") + "_USDT"
            s == "BNBIDR" -> "BNB_IDR"
            s == "SHIBIDR" -> "SHIB_IDR"
            s.endsWith("BIDR") -> s.removeSuffix("BIDR") + "_IDR"
            s.endsWith("IDR") -> s.removeSuffix("IDR") + "_IDR"
            else -> "${s}_IDR"
        }
    }

    fun toTokocryptoSymbol(symbol: String): String {
        val s = symbol.trim().uppercase().replace("/", "").replace("-", "").replace("_", "")
        return when {
            s == "BNBIDR" || s == "SHIBIDR" -> s
            s.endsWith("BIDR") -> s.removeSuffix("BIDR") + "IDR"
            else -> s
        }
    }


    private suspend fun throttle() {
        rateMutex.withLock {
            val now = System.currentTimeMillis()
            val wait = MIN_INTERVAL_MS - (now - lastRequestAt.get())
            if (wait > 0) delay(wait)
            lastRequestAt.set(System.currentTimeMillis())
        }
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                throttle()
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TokoClient/3.5")
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                client.newCall(req).execute().use { response ->
                    val code = response.code
                    val body = response.body?.string()
                    when {
                        response.isSuccessful -> return@withContext body
                        code == 429 || code in 500..599 -> {
                            val backoff = (200L * (1 shl (attempt - 1))).coerceAtMost(1000L)
                            delay(backoff)
                        }
                        else -> {
                            return@withContext null
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val backoff = (150L * (1 shl (attempt - 1))).coerceAtMost(800L)
                delay(backoff)
            }
        }
        null
    }

    private suspend fun getWithFallback(primaryUrl: String, fallbackPaths: List<String>): String? {
        val primaryRes = get(primaryUrl)
        if (!primaryRes.isNullOrBlank()) {
            return primaryRes
        }

        // Fallback ke relative paths atau absolute URLs
        for (path in fallbackPaths) {
            val url = when {
                path.startsWith("http://") || path.startsWith("https://") -> path
                path.startsWith("/") -> "$TOKOCRYPTO_TYPE1_MARKET_URL$path".replace("/api/v3/api/v3", "/api/v3")
                else -> "$TOKOCRYPTO_TYPE1_MARKET_URL/$path".replace("/api/v3/api/v3", "/api/v3")
            }
            val fb = get(url)
            if (!fb.isNullOrBlank()) {
                return fb
            }
        }
        return null
    }

    /**
     * Fetch Server Time dari Tokocrypto
     */
    suspend fun fetchServerTime(): Long = withContext(Dispatchers.IO) {
        try {
            val res = get("$TOKOCRYPTO_GENERAL_URL/open/v1/common/time")
            if (!res.isNullOrBlank()) {
                val root = JSONObject(res)
                val serverTime = root.optLong("serverTime", 0L)
                if (serverTime > 0) return@withContext serverTime
                val data = root.optJSONObject("data")
                if (data != null) {
                    val st = data.optLong("serverTime", 0L)
                    if (st > 0) return@withContext st
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        System.currentTimeMillis()
    }

    /**
     * Fetch Ticker 24 Jam dengan pemisahan symbolType (Type 1 vs Type 3)
     */
    suspend fun fetchTicker(symbol: String, prevPrice: Double = 0.0): MarketTick? = withContext(Dispatchers.IO) {
        val marketSym = toTokocryptoSymbol(symbol)
        val tokoPair = toTokocryptoPair(symbol)
        val symbolType = TokocryptoSymbolRepository.getSymbolType(marketSym)

        try {
            // 1. Primary: Ticker sesuai Type
            val primaryUrl = if (symbolType == 3) {
                "$TOKOCRYPTO_TYPE3_MARKET_URL/ticker/24hr?symbol=$marketSym"
            } else {
                "$TOKOCRYPTO_TYPE1_MARKET_URL/ticker/24hr?symbol=$marketSym"
            }

            val fallbackPaths = listOf(
                "/api/v3/ticker/24hr?symbol=$marketSym",
                "/open/v1/market/ticker?symbol=$tokoPair"
            )

            val jsonStr = getWithFallback(primaryUrl, fallbackPaths)
            if (!jsonStr.isNullOrBlank()) {
                val obj = if (jsonStr.trim().startsWith("{")) {
                    val root = JSONObject(jsonStr)
                    root.optJSONObject("data") ?: root
                } else null

                if (obj != null) {
                    val lastPrice = obj.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                    if (lastPrice > 0.0) {
                        val high = obj.optString("highPrice", "0").toDoubleOrNull() ?: lastPrice
                        val low = obj.optString("lowPrice", "0").toDoubleOrNull() ?: lastPrice
                        val quoteVol = obj.optString("quoteVolume", "0").toDoubleOrNull() ?: 0.0
                        val changePct = obj.optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0
                        return@withContext MarketTick(
                            symbol = marketSym,
                            price = lastPrice,
                            high24h = high,
                            low24h = low,
                            volume24h = quoteVol,
                            change24h = changePct,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        null
    }

    /**
     * Fetch multiple Tickers dengan routing Symbol Type 1 vs Type 3
     */
    suspend fun fetchTickers(symbols: List<String>): List<MarketTick> = withContext(Dispatchers.IO) {
        try {
            val symbolSet = symbols.map { toTokocryptoSymbol(it) }.toSet()
            val results = mutableListOf<MarketTick>()
            val foundSymbols = mutableSetOf<String>()

            // 1. Fetch Tickers Type 1 (MBX Broker / Cloud)
            val primaryUrl1 = "$TOKOCRYPTO_TYPE1_MARKET_URL/ticker/24hr"
            val fallbackPaths1 = listOf("/api/v3/ticker/24hr")
            val jsonStr1 = getWithFallback(primaryUrl1, fallbackPaths1)
            if (!jsonStr1.isNullOrBlank()) {
                val array1 = if (jsonStr1.trim().startsWith("[")) JSONArray(jsonStr1) else JSONArray()
                for (i in 0 until array1.length()) {
                    val item = array1.optJSONObject(i) ?: continue
                    val sym = item.optString("symbol", "").uppercase()
                    if (symbolSet.contains(sym)) {
                        val last = item.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                        if (last > 0) {
                            results.add(
                                MarketTick(
                                    symbol = sym,
                                    price = last,
                                    high24h = item.optString("highPrice", "0").toDoubleOrNull() ?: last,
                                    low24h = item.optString("lowPrice", "0").toDoubleOrNull() ?: last,
                                    volume24h = item.optString("quoteVolume", "0").toDoubleOrNull() ?: 0.0,
                                    change24h = item.optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                            foundSymbols.add(sym)
                        }
                    }
                }
            }

            // 2. Fetch Tickers Type 3 (NextMe) jika ada simbol yang belum ditemukan atau bertipe 3
            val remainingSymbols = symbolSet.filterNot { foundSymbols.contains(it) }
            val hasType3 = remainingSymbols.any { TokocryptoSymbolRepository.getSymbolType(it) == 3 }
            if (hasType3 || remainingSymbols.isNotEmpty()) {
                val primaryUrl3 = "$TOKOCRYPTO_TYPE3_MARKET_URL/ticker/24hr"
                val fallbackPaths3 = listOf("/open/v1/ticker/24hr", "/api/v3/ticker/24hr")
                val jsonStr3 = getWithFallback(primaryUrl3, fallbackPaths3)
                if (!jsonStr3.isNullOrBlank()) {
                    val array3 = if (jsonStr3.trim().startsWith("[")) {
                        JSONArray(jsonStr3)
                    } else {
                        JSONObject(jsonStr3).optJSONArray("data") ?: JSONArray()
                    }
                    for (i in 0 until array3.length()) {
                        val item = array3.optJSONObject(i) ?: continue
                        val rawSym = item.optString("symbol", "").uppercase().replace("_", "")
                        if (symbolSet.contains(rawSym) && !foundSymbols.contains(rawSym)) {
                            val last = item.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                            if (last > 0) {
                                results.add(
                                    MarketTick(
                                        symbol = rawSym,
                                        price = last,
                                        high24h = item.optString("highPrice", "0").toDoubleOrNull() ?: last,
                                        low24h = item.optString("lowPrice", "0").toDoubleOrNull() ?: last,
                                        volume24h = item.optString("quoteVolume", "0").toDoubleOrNull() ?: 0.0,
                                        change24h = item.optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                                foundSymbols.add(rawSym)
                            }
                        }
                    }
                }
            }

            results
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Fetch Kline / Candlestick dengan routing Symbol Type 1 vs Type 3 (hanya server Tokocrypto, tanpa Binance)
     */
    suspend fun fetchCandles(symbol: String, timeframe: Timeframe, limit: Int = 300): List<CandleBar> = withContext(Dispatchers.IO) {
        val marketSym = toTokocryptoSymbol(symbol)
        val tokoPair = toTokocryptoPair(symbol)
        val symbolType = TokocryptoSymbolRepository.getSymbolType(marketSym)
        val interval = when (timeframe) {
            Timeframe.M1 -> "1m"
            Timeframe.M5 -> "5m"
            Timeframe.M15 -> "15m"
            Timeframe.H1 -> "1h"
            Timeframe.H4 -> "4h"
            Timeframe.D1 -> "1d"
        }
        val safeLimit = limit.coerceIn(10, 1000)

        val type1Url = "$TOKOCRYPTO_TYPE1_MARKET_URL/klines?symbol=$marketSym&interval=$interval&limit=$safeLimit"
        val type3Url = "$TOKOCRYPTO_TYPE3_MARKET_URL/klines?symbol=$marketSym&interval=$interval&limit=$safeLimit"
        val openApiUrl = "$TOKOCRYPTO_GENERAL_URL/open/v1/market/klines?symbol=$tokoPair&interval=$interval&limit=$safeLimit"

        val primaryUrl = if (symbolType == 3) type3Url else type1Url
        val fallbackPaths = if (symbolType == 3) {
            listOf(type1Url, openApiUrl)
        } else {
            listOf(type3Url, openApiUrl)
        }

        try {
            val jsonStr = getWithFallback(primaryUrl, fallbackPaths)
            if (!jsonStr.isNullOrBlank()) {
                val array = if (jsonStr.trim().startsWith("[")) {
                    JSONArray(jsonStr)
                } else {
                    val root = JSONObject(jsonStr)
                    root.optJSONArray("data") ?: JSONArray()
                }

                val candles = mutableListOf<CandleBar>()
                for (i in 0 until array.length()) {
                    val row = array.optJSONArray(i) ?: continue
                    val openTime = row.optLong(0, 0L)
                    val open = row.optString(1, "0").toDoubleOrNull() ?: 0.0
                    val high = row.optString(2, "0").toDoubleOrNull() ?: 0.0
                    val low = row.optString(3, "0").toDoubleOrNull() ?: 0.0
                    val close = row.optString(4, "0").toDoubleOrNull() ?: 0.0
                    val volume = row.optString(5, "0").toDoubleOrNull() ?: 0.0

                    if (openTime > 0 && close > 0) {
                        candles.add(
                            CandleBar(
                                timestamp = openTime,
                                open = open,
                                high = high,
                                low = low,
                                close = close,
                                volume = volume
                            )
                        )
                    }
                }
                // P0-1 — bar terakhir dari REST adalah candle yang masih berjalan; tandai terbuka.
                return@withContext agu.analys.util.CandleTimeUtil.markLastCandleOpenIfForming(candles, timeframe)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        emptyList()
    }

    /**
     * Fetch Order Book Depth dengan routing Type 1 vs Type 3
     */
    suspend fun fetchOrderBook(symbol: String, limit: Int = 20): Pair<List<OrderBookItem>, List<OrderBookItem>> = withContext(Dispatchers.IO) {
        val marketSym = toTokocryptoSymbol(symbol)
        val symbolType = TokocryptoSymbolRepository.getSymbolType(marketSym)
        val safeLimit = limit.coerceIn(5, 100)

        val primaryUrl = if (symbolType == 3) {
            "$TOKOCRYPTO_TYPE3_MARKET_URL/depth?symbol=$marketSym&limit=$safeLimit"
        } else {
            "$TOKOCRYPTO_TYPE1_MARKET_URL/depth?symbol=$marketSym&limit=$safeLimit"
        }

        val fallbackPaths = listOf(
            "/api/v3/depth?symbol=$marketSym&limit=$safeLimit"
        )

        try {
            val jsonStr = getWithFallback(primaryUrl, fallbackPaths)
            if (!jsonStr.isNullOrBlank()) {
                val obj = JSONObject(jsonStr)
                val rawBids = obj.optJSONArray("bids") ?: JSONArray()
                val rawAsks = obj.optJSONArray("asks") ?: JSONArray()

                var bidSum = 0.0
                val bids = mutableListOf<OrderBookItem>()
                for (i in 0 until rawBids.length()) {
                    val row = rawBids.optJSONArray(i) ?: continue
                    val p = row.optString(0, "0").toDoubleOrNull() ?: 0.0
                    val a = row.optString(1, "0").toDoubleOrNull() ?: 0.0
                    if (p > 0 && a > 0) {
                        bidSum += a
                        bids.add(OrderBookItem(price = p, amount = a, total = bidSum, isBid = true))
                    }
                }

                var askSum = 0.0
                val asks = mutableListOf<OrderBookItem>()
                for (i in 0 until rawAsks.length()) {
                    val row = rawAsks.optJSONArray(i) ?: continue
                    val p = row.optString(0, "0").toDoubleOrNull() ?: 0.0
                    val a = row.optString(1, "0").toDoubleOrNull() ?: 0.0
                    if (p > 0 && a > 0) {
                        askSum += a
                        asks.add(OrderBookItem(price = p, amount = a, total = askSum, isBid = false))
                    }
                }
                return@withContext Pair(bids, asks)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        Pair(emptyList(), emptyList())
    }

    /**
     * Fetch Recent Trades dengan routing Type 1 vs Type 3
     */
    suspend fun fetchRecentTrades(symbol: String, limit: Int = 25): List<TradeStreamItem> = withContext(Dispatchers.IO) {
        val marketSym = toTokocryptoSymbol(symbol)
        val symbolType = TokocryptoSymbolRepository.getSymbolType(marketSym)
        val safeLimit = limit.coerceIn(5, 100)

        val primaryUrl = if (symbolType == 3) {
            "$TOKOCRYPTO_GENERAL_URL/open/v1/market/trades?symbol=${toTokocryptoPair(symbol)}&limit=$safeLimit"
        } else {
            "$TOKOCRYPTO_TYPE1_MARKET_URL/trades?symbol=$marketSym&limit=$safeLimit"
        }

        val fallbackPaths = listOf(
            "/api/v3/trades?symbol=$marketSym&limit=$safeLimit"
        )

        try {
            val jsonStr = getWithFallback(primaryUrl, fallbackPaths)
            if (!jsonStr.isNullOrBlank()) {
                val array = if (jsonStr.trim().startsWith("[")) {
                    JSONArray(jsonStr)
                } else {
                    val root = JSONObject(jsonStr)
                    root.optJSONArray("data") ?: JSONArray()
                }

                val trades = mutableListOf<TradeStreamItem>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optLong("id", 0L).toString()
                    val p = obj.optString("price", "0").toDoubleOrNull() ?: 0.0
                    val q = obj.optString("qty", "0").toDoubleOrNull() ?: 0.0
                    val t = obj.optLong("time", System.currentTimeMillis())
                    val isBuyerMaker = obj.optBoolean("isBuyerMaker", false)
                    val isBuy = !isBuyerMaker

                    if (p > 0 && q > 0) {
                        trades.add(
                            TradeStreamItem(
                                id = id,
                                price = p,
                                amount = q,
                                timeFormatted = tradeTimeFormatter.format(Instant.ofEpochMilli(t)),
                                isBuy = isBuy
                            )
                        )
                    }
                }
                return@withContext trades.reversed()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        emptyList()
    }

    /**
     * Fetch Aggregate Trades (AggTrades)
     */
    suspend fun fetchAggTrades(symbol: String, limit: Int = 50): List<TradeStreamItem> = withContext(Dispatchers.IO) {
        val marketSym = toTokocryptoSymbol(symbol)
        val symbolType = TokocryptoSymbolRepository.getSymbolType(marketSym)
        val safeLimit = limit.coerceIn(5, 500)

        val primaryUrl = if (symbolType == 3) {
            "$TOKOCRYPTO_TYPE3_MARKET_URL/aggTrades?symbol=$marketSym&limit=$safeLimit"
        } else {
            "$TOKOCRYPTO_TYPE1_MARKET_URL/aggTrades?symbol=$marketSym&limit=$safeLimit"
        }

        val fallbackPaths = listOf(
            "/api/v3/aggTrades?symbol=$marketSym&limit=$safeLimit"
        )

        try {
            val jsonStr = getWithFallback(primaryUrl, fallbackPaths)
            if (!jsonStr.isNullOrBlank()) {
                val array = if (jsonStr.trim().startsWith("[")) JSONArray(jsonStr) else JSONArray()
                val trades = mutableListOf<TradeStreamItem>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optLong("a", 0L).toString()
                    val p = obj.optString("p", "0").toDoubleOrNull() ?: 0.0
                    val q = obj.optString("q", "0").toDoubleOrNull() ?: 0.0
                    val t = obj.optLong("T", System.currentTimeMillis())
                    val isBuyerMaker = obj.optBoolean("m", false)
                    val isBuy = !isBuyerMaker

                    if (p > 0 && q > 0) {
                        trades.add(
                            TradeStreamItem(
                                id = id,
                                price = p,
                                amount = q,
                                timeFormatted = tradeTimeFormatter.format(Instant.ofEpochMilli(t)),
                                isBuy = isBuy
                            )
                        )
                    }
                }
                return@withContext trades.reversed()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        emptyList()
    }

    data class TokoRankingsResult(
        val gainers: List<MarketTick> = emptyList(),
        val losers: List<MarketTick> = emptyList(),
        val topVolume: List<MarketTick> = emptyList(),
        val allTicks: Map<String, MarketTick> = emptyMap()
    )

    /** Ambil array ticker dari respons berformat `[...]` atau `{"data":[...]}`. */
    private fun parseTickerArray(json: String?): JSONArray {
        if (json.isNullOrBlank()) return JSONArray()
        return try {
            val t = json.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("data") ?: JSONArray()
        } catch (_: Exception) {
            JSONArray()
        }
    }

    /**
     * Fetch ranking dari seluruh pair aktif Tokocrypto.
     * Type 1 (MBX) dan Type 3 (NextMe) diambil paralel lalu digabung (Type 1 diprioritaskan).
     * Hanya server Tokocrypto yang dipakai.
     */
    suspend fun fetchMarketRankings(limit: Int = 35): TokoRankingsResult = withContext(Dispatchers.IO) {
        try {
            // Fail-closed: pastikan metadata listing terbaru sebelum memfilter.
            // Dijatah TTL 30 menit di repository, jadi murah bila sudah sinkron.
            TokocryptoSymbolRepository.ensureSymbolsLoaded(false)
            val (json1, json3) = coroutineScope {
                val a = async { get("$TOKOCRYPTO_TYPE1_MARKET_URL/ticker/24hr") }
                val b = async { get("$TOKOCRYPTO_TYPE3_MARKET_URL/ticker/24hr") }
                a.await() to b.await()
            }
            if (json1.isNullOrBlank() && json3.isNullOrBlank()) return@withContext TokoRankingsResult()

            val allTicks = mutableMapOf<String, MarketTick>()
            val candidates = mutableListOf<MarketTick>()

            for (array in listOf(parseTickerArray(json1), parseTickerArray(json3))) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val sym = toTokocryptoSymbol(obj.optString("symbol", ""))
                    if (sym.isEmpty() || allTicks.containsKey(sym)) continue
                    // Hanya pair IDR dan USDT, tanpa BIDR
                    if (!isIdrOrUsdtPair(sym)) continue
                    val isIdr = !sym.endsWith("USDT") && sym.endsWith("IDR")

                    val last = obj.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                    if (last <= 0.0) continue

                    val high = obj.optString("highPrice", "0").toDoubleOrNull() ?: last
                    val low = obj.optString("lowPrice", "0").toDoubleOrNull() ?: last
                    val quoteVol = obj.optString("quoteVolume", "0").toDoubleOrNull() ?: 0.0
                    val change = obj.optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0

                    val tick = MarketTick(
                        symbol = sym,
                        price = last,
                        high24h = high,
                        low24h = low,
                        volume24h = quoteVol,
                        change24h = change,
                        timestamp = System.currentTimeMillis()
                    )
                    allTicks[sym] = tick
                    if (agu.analys.util.DashboardRanking.isRankable(agu.analys.config.MarketDataSource.TOKOCRYPTO, tick)) {
                        candidates.add(tick)
                    }
                }
            }

            // Kurs hanya dari ExchangeRateManager (orderbook Tokocrypto). Bila belum ada, ambil dulu.
            if (!agu.analys.util.ExchangeRateManager.isRateAvailable()) {
                agu.analys.util.ExchangeRateManager.refresh()
            }
            val rate = agu.analys.util.ExchangeRateManager.currentRate()
            val gainers = candidates.filter { it.change24h > 0 }.sortedByDescending { it.change24h }.take(limit)
            val losers = candidates.filter { it.change24h < 0 }.sortedBy { it.change24h }.take(limit)
            val topVol = agu.analys.util.DashboardRanking.rankByVolume(candidates, rate).take(limit)

            TokoRankingsResult(
                gainers = gainers,
                losers = losers,
                topVolume = topVol,
                allTicks = allTicks
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Error fetchMarketRankings Tokocrypto")
            TokoRankingsResult()
        }
    }

    fun isSafeTradableAsset(
        price: Double,
        volume24h: Double,
        high24h: Double = 0.0,
        low24h: Double = 0.0,
        isIdrPair: Boolean = true,
        isExplicitlyFavored: Boolean = false
    ): Boolean {
        if (isExplicitlyFavored) return true
        if (!price.isFinite() || price <= 0.0) return false

        return if (isIdrPair) {
            if (price <= 5.0) return false
            if (volume24h < 1_000_000.0) return false
            true
        } else {
            volume24h >= 10_000.0 // ambang volume 24 jam; 100 USDT terlalu receh
        }
    }
}
