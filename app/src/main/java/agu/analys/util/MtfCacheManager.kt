package agu.analys.util

import agu.analys.model.CandleBar
import agu.analys.model.Timeframe
import agu.analys.service.TokocryptoMarketService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MtfStatus {
    SYNCING, READY, UPDATING, ERROR
}

/**
 * Multi-Timeframe Cache Manager:
 * Mengelola prefetch dan cache in-memory untuk kline (M1, M15, H1, H4) secara terisolasi 100% per exchange
 * dari API Tokocrypto tanpa unscoped fallback.
 */
object MtfCacheManager {
    // In-memory cache for fast lookup. Map<ExchangeScopedKey, Map<Timeframe, List<CandleBar>>>
    // ConcurrentHashMap: dibaca thread UI/tick sementara ditulis coroutine IO — tanpa ini
    // berisiko ConcurrentModificationException / lost update.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<Timeframe, List<CandleBar>>>()
    private val cacheLock = Any()

    // Observable status for UI
    private val _mtfState = MutableStateFlow<Map<String, Map<Timeframe, MtfStatus>>>(emptyMap())
    val mtfState: StateFlow<Map<String, Map<Timeframe, MtfStatus>>> = _mtfState

    private val rateLimitMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var activeTier1Symbol: String? = null
    var activeExchange: String = "TOKOCRYPTO"
        private set
    private var tier1Job: Job? = null
    private var backgroundJob: Job? = null

    private var watchlist = listOf<String>()
    private var historyList = listOf<String>()

    fun updateExchange(exchange: String) {
        val ex = exchange.trim().uppercase()
        if (activeExchange != ex) {
            activeExchange = ex
            clear()
        }
    }

    private fun buildKey(symbol: String, exchange: String): String {
        return "${exchange.trim().uppercase()}_${symbol.trim().uppercase()}"
    }

    fun updateQueues(newWatchlist: List<String>, newHistory: List<String>, exchange: String? = null) {
        watchlist = newWatchlist
        historyList = newHistory
        if (exchange != null) {
            activeExchange = exchange.trim().uppercase()
        }
        startBackgroundWorkerIfNeeded()
    }

    /**
     * Set active symbol (Tier 1). Cancels any ongoing Tier 1 fetch for a different symbol.
     */
    fun setActiveSymbol(symbol: String, exchange: String? = null) {
        if (symbol.isBlank()) return
        val normalized = symbol.trim().uppercase()
        val ex = exchange?.trim()?.uppercase() ?: activeExchange
        activeExchange = ex
        val scopedKey = buildKey(normalized, ex)
        if (activeTier1Symbol == scopedKey && tier1Job?.isActive == true) return
        activeTier1Symbol = scopedKey

        // Segera beri status SYNCING/READY untuk semua timeframe agar UI tidak error/stale
        val tfs = listOf(Timeframe.H1, Timeframe.M15, Timeframe.M5, Timeframe.M1, Timeframe.H4)
        tfs.forEach { tf ->
            val cached = getCachedCandles(normalized, tf, ex)
            if (!isCacheValid(tf, cached)) {
                updateStatus(normalized, tf, if (cached?.isNotEmpty() == true) MtfStatus.UPDATING else MtfStatus.SYNCING, ex)
            } else {
                updateStatus(normalized, tf, MtfStatus.READY, ex)
            }
        }

        tier1Job?.cancel()
        tier1Job = scope.launch {
            prefetchSymbol(normalized, ex, isTier1 = true)
        }
    }

    /**
     * Force retry all MTF timeframes for symbol (e.g. from Retry button).
     */
    fun retryAllTimeframes(symbol: String, exchange: String? = null) {
        if (symbol.isBlank()) return
        val normalized = symbol.trim().uppercase()
        val ex = exchange?.trim()?.uppercase() ?: activeExchange
        activeExchange = ex
        val scopedKey = buildKey(normalized, ex)
        activeTier1Symbol = scopedKey
        val tfs = listOf(Timeframe.H1, Timeframe.M15, Timeframe.M5, Timeframe.M1, Timeframe.H4)
        tfs.forEach { updateStatus(normalized, it, MtfStatus.SYNCING, ex) }
        tier1Job?.cancel()
        tier1Job = scope.launch {
            for (tf in tfs) {
                if (!isActive) break
                safeFetch(normalized, tf, ex, isTier1 = true)
            }
        }
    }

    /**
     * Request specific timeframe retry.
     */
    fun retryTimeframe(symbol: String, tf: Timeframe, exchange: String? = null) {
        val normalized = symbol.trim().uppercase()
        val ex = exchange?.trim()?.uppercase() ?: activeExchange
        activeExchange = ex
        scope.launch {
            updateStatus(normalized, tf, MtfStatus.SYNCING, ex)
            safeFetch(normalized, tf, ex, isTier1 = true)
        }
    }

    fun getCachedCandles(symbol: String, timeframe: Timeframe, exchange: String? = null): List<CandleBar>? {
        val normalized = symbol.trim().uppercase()
        val ex = exchange?.trim()?.uppercase() ?: activeExchange
        val scopedKey = buildKey(normalized, ex)
        val compactSym = TokocryptoMarketService.toTokocryptoSymbol(normalized)
        val tokoPair = TokocryptoMarketService.toTokocryptoPair(normalized)
        val altPair = TokocryptoMarketService.toTokocryptoPair(normalized).uppercase()
        val altClean = TokocryptoMarketService.toTokocryptoSymbol(normalized).uppercase()

        return cache[scopedKey]?.get(timeframe)
            ?: cache[buildKey(compactSym, ex)]?.get(timeframe)
            ?: cache[buildKey(tokoPair, ex)]?.get(timeframe)
            ?: cache[buildKey(altPair, ex)]?.get(timeframe)
            ?: cache[buildKey(altClean, ex)]?.get(timeframe)
    }

    fun isCacheValid(timeframe: Timeframe, candles: List<CandleBar>?): Boolean {
        if (candles.isNullOrEmpty() || candles.size < 20) return false
        val rawTimestamp = candles.last().timestamp
        val lastTimestampMs = if (rawTimestamp < 10_000_000_000L) rawTimestamp * 1000L else rawTimestamp
        val ageMs = System.currentTimeMillis() - lastTimestampMs
        return when (timeframe) {
            Timeframe.M1 -> ageMs <= 180 * 1000L
            Timeframe.M5 -> ageMs <= 10 * 60 * 1000L
            Timeframe.M15 -> ageMs <= 30 * 60 * 1000L
            Timeframe.H1 -> ageMs <= 120 * 60 * 1000L
            Timeframe.H4 -> ageMs <= 480 * 60 * 1000L
            Timeframe.D1 -> ageMs <= 2880 * 60 * 1000L
        }
    }

    private fun startBackgroundWorkerIfNeeded() {
        if (backgroundJob?.isActive == true) return
        backgroundJob = scope.launch {
            while (isActive) {
                val candidate = findNextBackgroundCandidate()
                if (candidate != null) {
                    prefetchSymbol(candidate, activeExchange, isTier1 = false)
                }
                delay(1500) // Small breather between symbols
            }
        }
    }

    private fun findNextBackgroundCandidate(): String? {
        // Priority 1: Watchlist
        for (symbol in watchlist) {
            val norm = symbol.trim().uppercase()
            val scopedKey = buildKey(norm, activeExchange)
            if (scopedKey == activeTier1Symbol) continue
            if (needsRefresh(norm, activeExchange)) return norm
        }
        // Priority 2: History
        for (symbol in historyList) {
            val norm = symbol.trim().uppercase()
            val scopedKey = buildKey(norm, activeExchange)
            if (scopedKey == activeTier1Symbol) continue
            if (needsRefresh(norm, activeExchange)) return norm
        }
        return null
    }

    private fun needsRefresh(symbol: String, exchange: String): Boolean {
        val norm = symbol.trim().uppercase()
        val scopedKey = buildKey(norm, exchange)
        val symbolCache = cache[scopedKey] ?: return true
        val tfs = listOf(Timeframe.H4, Timeframe.H1, Timeframe.M15, Timeframe.M5, Timeframe.M1)
        for (tf in tfs) {
            if (!isCacheValid(tf, symbolCache[tf])) return true
        }
        return false
    }

    private suspend fun prefetchSymbol(symbol: String, exchange: String, isTier1: Boolean) {
        val norm = symbol.trim().uppercase()
        val tfs = listOf(Timeframe.H1, Timeframe.M15, Timeframe.M5, Timeframe.M1, Timeframe.H4)
        for (tf in tfs) {
            if (!scope.isActive) break
            val currentCandles = getCachedCandles(norm, tf, exchange)
            if (!isCacheValid(tf, currentCandles)) {
                updateStatus(norm, tf, if (currentCandles?.isNotEmpty() == true) MtfStatus.UPDATING else MtfStatus.SYNCING, exchange)
                safeFetch(norm, tf, exchange, isTier1)
            } else {
                updateStatus(norm, tf, MtfStatus.READY, exchange)
            }
        }
    }

    private suspend fun safeFetch(symbol: String, tf: Timeframe, exchange: String, isTier1: Boolean) {
        val norm = symbol.trim().uppercase()
        val scopedKey = buildKey(norm, exchange)

        // Rate limiting throttle
        rateLimitMutex.withLock {
            delay(100L) // Ensure 100ms spacing between MTF API calls
        }

        val limit = when (tf) {
            Timeframe.H4 -> 100
            Timeframe.H1 -> 150
            Timeframe.M15 -> 200
            Timeframe.M5 -> 200
            Timeframe.M1 -> 250
            else -> 100
        }

        // Ambil kline dari layanan pasar Tokocrypto
        val res = TokocryptoMarketService.fetchCandles(norm, tf, limit = limit)
        var fetched = if (res.isEmpty() && (norm.contains("_") || norm.contains("/"))) {
            TokocryptoMarketService.fetchCandles(norm.replace("/", "").replace("_", ""), tf, limit = limit)
        } else res

        // Coba ulang cepat bila hasil masih kosong
        if (fetched.isEmpty()) {
            delay(200L)
            fetched = TokocryptoMarketService.fetchCandles(norm, tf, limit = limit)
        }

        if (fetched.isNotEmpty()) {
            // Satu batch atomik: cegah pembaca melihat alias terisi sebagian.
            synchronized(cacheLock) {
                val symbolMap = cache.getOrPut(scopedKey) { java.util.concurrent.ConcurrentHashMap() }
                symbolMap[tf] = fetched

                // Also alias under clean compact Tokocrypto symbol and pair for fast retrieval
                val clean = norm.replace("_", "").replace("/", "").replace("-", "")
                if (clean != norm) {
                    cache.getOrPut(buildKey(clean, exchange)) { java.util.concurrent.ConcurrentHashMap() }[tf] = fetched
                }
                val compactSym = TokocryptoMarketService.toTokocryptoSymbol(norm)
                if (compactSym != norm && compactSym != clean) {
                    cache.getOrPut(buildKey(compactSym, exchange)) { java.util.concurrent.ConcurrentHashMap() }[tf] = fetched
                }
                val tokoPair = TokocryptoMarketService.toTokocryptoPair(norm)
                if (tokoPair != norm && tokoPair != clean) {
                    cache.getOrPut(buildKey(tokoPair, exchange)) { java.util.concurrent.ConcurrentHashMap() }[tf] = fetched
                }
                val altClean = TokocryptoMarketService.toTokocryptoSymbol(norm).uppercase()
                if (altClean != norm && altClean != clean) {
                    cache.getOrPut(buildKey(altClean, exchange)) { java.util.concurrent.ConcurrentHashMap() }[tf] = fetched
                }
            }
            updateStatus(norm, tf, if (isCacheValid(tf, fetched)) MtfStatus.READY else MtfStatus.SYNCING, exchange)
        } else {
            val existing = getCachedCandles(norm, tf, exchange)
            if (existing.isNullOrEmpty()) {
                updateStatus(norm, tf, MtfStatus.ERROR, exchange)
            }
        }
    }

    fun clear() {
        tier1Job?.cancel()
        tier1Job = null
        backgroundJob?.cancel()
        backgroundJob = null
        activeTier1Symbol = null
        synchronized(cacheLock) { cache.clear() }
        _mtfState.value = emptyMap()
    }

    private fun updateStatus(symbol: String, tf: Timeframe, status: MtfStatus, exchange: String = activeExchange) {
        val norm = symbol.trim().uppercase()
        val current = _mtfState.value.toMutableMap()
        
        fun applyStatus(key: String) {
            val symbolStatuses = current.getOrPut(key) { mutableMapOf() }.toMutableMap()
            symbolStatuses[tf] = status
            current[key] = symbolStatuses
        }

        applyStatus(norm)
        val clean = norm.replace("_", "").replace("/", "").replace("-", "")
        if (clean != norm) applyStatus(clean)
        val compact = TokocryptoMarketService.toTokocryptoSymbol(norm)
        if (compact != norm && compact != clean) applyStatus(compact)
        val tokoPair = TokocryptoMarketService.toTokocryptoPair(norm)
        if (tokoPair != norm && tokoPair != clean) applyStatus(tokoPair)
        val indoPair = TokocryptoMarketService.toTokocryptoPair(norm).uppercase()
        if (indoPair != norm && indoPair != clean) applyStatus(indoPair)
        val indoClean = TokocryptoMarketService.toTokocryptoSymbol(norm).uppercase()
        if (indoClean != norm && indoClean != clean && indoClean != indoPair) applyStatus(indoClean)

        _mtfState.value = current
    }
}
