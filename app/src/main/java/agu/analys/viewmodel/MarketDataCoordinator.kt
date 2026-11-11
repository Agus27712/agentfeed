package agu.analys.viewmodel

import agu.analys.config.MarketDataSource
import agu.analys.data.OrderBookDepthCache
import agu.analys.engine.LearningTradingEngine
import agu.analys.model.CandleBar
import agu.analys.model.MarketConnectionState
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.Timeframe
import agu.analys.model.TradeStreamItem
import agu.analys.model.TradingPair
import agu.analys.service.TokocryptoMarketService
import agu.analys.service.TokocryptoMarketWebSocket
import agu.analys.util.AppPreferences
import agu.analys.util.MarketDataCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MarketDataCoordinator(
    private val scope: CoroutineScope,
    private val prefs: AppPreferences,
    private val marketCache: MarketDataCache,
    private val engine: LearningTradingEngine,
    private val simCoordinator: SimulationCoordinator,
    /**
     * @param recentCandles bar terbaru (sudah termasuk candle yang sedang berjalan) —
     *   dipakai konsumen untuk evaluasi momentum paper exit P2.
     */
    private val onPriceUpdate: (String, Double, Double?, List<CandleBar>) -> Unit
) {
    private val _connectionState = MutableStateFlow<MarketConnectionState>(MarketConnectionState.Loading)
    val connectionState: StateFlow<MarketConnectionState> = _connectionState.asStateFlow()

    private val _currentTick = MutableStateFlow<MarketTick?>(null)
    val currentTick: StateFlow<MarketTick?> = _currentTick.asStateFlow()

    private val _recentPrices = MutableStateFlow<List<Double>>(emptyList())
    val recentPrices: StateFlow<List<Double>> = _recentPrices.asStateFlow()

    private val _recentCandles = MutableStateFlow<List<CandleBar>>(emptyList())
    val recentCandles: StateFlow<List<CandleBar>> = _recentCandles.asStateFlow()

    private val _orderBookBids = MutableStateFlow<List<OrderBookItem>>(emptyList())
    val orderBookBids: StateFlow<List<OrderBookItem>> = _orderBookBids.asStateFlow()

    private val _orderBookAsks = MutableStateFlow<List<OrderBookItem>>(emptyList())
    val orderBookAsks: StateFlow<List<OrderBookItem>> = _orderBookAsks.asStateFlow()

    private val _tradeStream = MutableStateFlow<List<TradeStreamItem>>(emptyList())
    val tradeStream: StateFlow<List<TradeStreamItem>> = _tradeStream.asStateFlow()

    private val _dashboardTicks = MutableStateFlow<Map<String, MarketTick>>(emptyMap())
    val dashboardTicks: StateFlow<Map<String, MarketTick>> = _dashboardTicks.asStateFlow()

    private val _isShowingCachedData = MutableStateFlow(false)
    val isShowingCachedData: StateFlow<Boolean> = _isShowingCachedData.asStateFlow()

    private val _uiPriceThrottleMs = MutableStateFlow(prefs.priceFeedThrottleMs)
    val uiPriceThrottleMs: StateFlow<Long> = _uiPriceThrottleMs.asStateFlow()

    private val uiPriceThrottler = agu.analys.util.PriceFeedThrottler(
        scope = scope,
        initialThrottleIntervalMs = prefs.priceFeedThrottleMs,
        onEmit = { throttledTick ->
            dispatchThrottledTick(throttledTick)
        }
    )

    fun setPriceFeedThrottleMs(ms: Long) {
        val coerced = ms.coerceAtLeast(0L)
        prefs.priceFeedThrottleMs = coerced
        uiPriceThrottler.throttleIntervalMs = coerced
        _uiPriceThrottleMs.value = coerced
    }

    private var currentActivePair: TradingPair? = null
    private var currentActiveTimeframe: Timeframe = Timeframe.M15
    private var lastLiveTickAt = 0L
    // Status live WebSocket Tokocrypto (satu-satunya bursa yang didukung).
    private var wsLiveToko = false
    private var lastCandleRefresh = 0L
    private var lastDepthRefresh = 0L
    private var marketPollJob: Job? = null
    private var dashboardPollJob: Job? = null



    private val tokocryptoWebSocket = TokocryptoMarketWebSocket(
        scope = scope,
        onTick = { handleWebSocketTick(it) },
        onCandle = { candle ->
            engine.currentFormingVolume = candle.volume
            engine.onCandleUpdate(candle)
        },
        onTrade = { trade ->
            val cur = _tradeStream.value
            _tradeStream.value = (listOf(trade) + cur).take(50)
        },
        onDepth = { bids, asks ->
            if (bids.isNotEmpty() || asks.isNotEmpty()) {
                _orderBookBids.value = bids
                _orderBookAsks.value = asks
                val active = currentActivePair?.symbol
                if (!active.isNullOrBlank()) {
                    OrderBookDepthCache.updateOrderBook(active, bids, asks, "TOKOCRYPTO")
                }
            }
        },
        onConnected = {
            wsLiveToko = true
            lastLiveTickAt = System.currentTimeMillis()
            _connectionState.value = MarketConnectionState.Connected
            _isShowingCachedData.value = false
            agu.analys.util.AppLogManager.market("TokocryptoWS", "✅ WebSocket tersambung ke server Tokocrypto untuk ${currentActivePair?.symbol}")
        },
        onDisconnected = {
            wsLiveToko = false
            val recentRest = System.currentTimeMillis() - lastLiveTickAt < 12_000L
            if (!recentRest && _currentTick.value == null) {
                _connectionState.value = MarketConnectionState.ConnectionLost("Realtime Tokocrypto terputus. REST fallback...")
            }
            agu.analys.util.AppLogManager.warn("TokocryptoWS", "⚠️ WebSocket Tokocrypto terputus untuk ${currentActivePair?.symbol}.")
        }
    )

    /** Menandai status live WebSocket Tokocrypto. */
    private fun setWsLive(live: Boolean) {
        wsLiveToko = live
    }

    /** Status live bursa yang sedang dipakai. */
    private fun activeWsLive(): Boolean = wsLiveToko

    private fun handleWebSocketTick(tick: MarketTick) {
        val currentPair = currentActivePair ?: return
        val selected = currentPair.symbol
        val cleanTick = tick.symbol.replace("/", "").replace("_", "").trim()
        val cleanSelected = selected.replace("/", "").replace("_", "").trim()
        val cleanAlt = currentPair.effectiveAltPair().replace("/", "").replace("_", "").trim()
        val cleanTokocrypto = currentPair.effectiveTokocryptoPair().replace("/", "").replace("_", "").trim()
        val cleanCompact = currentPair.effectiveCompactSymbol().replace("/", "").replace("_", "").trim()

        if (!cleanTick.equals(cleanSelected, true) && 
            !cleanTick.equals(cleanAlt, true) &&
            !cleanTick.equals(cleanTokocrypto, true) &&
            !cleanTick.equals(cleanCompact, true)) return

        lastLiveTickAt = System.currentTimeMillis()
        // Tick yang lolos filter di atas milik pair bursa aktif.
        setWsLive(true)
        if (_connectionState.value !is MarketConnectionState.Connected) {
            _connectionState.value = MarketConnectionState.Connected
            _isShowingCachedData.value = false
        }
        val previous = _currentTick.value
        val normalized = if (tick.volume24h > 0.0) {
            // WS Tokocrypto membawa statistik 24 jam asli: pakai langsung (high/low minimal mencakup harga kini)
            tick.copy(
                symbol = selected,
                high24h = if (tick.high24h > 0.0) maxOf(tick.high24h, tick.price) else maxOf(previous?.high24h ?: 0.0, tick.price),
                low24h = if (tick.low24h > 0.0) minOf(tick.low24h, tick.price) else previous?.low24h?.takeIf { it > 0.0 }?.let { minOf(it, tick.price) } ?: tick.price,
                change24h = if (tick.change24h.isFinite()) tick.change24h else (previous?.change24h ?: 0.0)
            )
        } else {
            // WS tanpa statistik 24 jam: high/low diperluas oleh harga, persen ubah digeser dari basis
            val prevChange = previous?.change24h
            val openRef = if (previous != null && previous.price > 0.0 && prevChange != null && prevChange.isFinite() && prevChange > -99.0) {
                previous.price / (1.0 + prevChange / 100.0)
            } else 0.0
            tick.copy(
                symbol = selected,
                high24h = maxOf(previous?.high24h ?: 0.0, tick.price),
                low24h = previous?.low24h?.takeIf { it > 0.0 }?.let { minOf(it, tick.price) } ?: tick.price,
                volume24h = previous?.volume24h ?: 0.0,
                change24h = if (openRef > 0.0) ((tick.price / openRef) - 1.0) * 100.0 else (prevChange ?: 0.0)
            )
        }
        // Pass through configurable UI throttler to prevent main thread bottlenecks during volatility spikes
        uiPriceThrottler.submit(normalized)
    }

    private fun dispatchThrottledTick(tick: MarketTick) {
        _currentTick.value = tick
        mergeLiveTickIntoDashboard(tick)
        val ex = prefs.marketDataSource.name
        agu.analys.engine.sell.TickHistoryTracker.recordTick(tick.symbol, tick.price, tick.timestamp, exchange = ex)
        if (_recentCandles.value.isNotEmpty()) {
            _recentCandles.value = agu.analys.util.CandleTimeUtil.synthesizeRealtimeCandles(
                _recentCandles.value,
                tick,
                currentActiveTimeframe
            )
        }
        engine.onTickUpdate(tick)
        updateRecentPrices(tick.price)
        simCoordinator.onPriceTick(tick.symbol, tick.price, tick.high24h, tick.low24h)
        onPriceUpdate(
            tick.symbol,
            tick.price,
            engine.indicators.value.rsi14.takeIf { it.isFinite() },
            _recentCandles.value
        )
    }

    /**
     * Tick live (WebSocket) hanya membawa harga. Volume, high, low, dan basis perubahan 24 jam
     * berasal dari REST, jadi digabung ke entri dashboard yang sudah ada (tidak menimpa dengan 0).
     * Bila pair belum ada di dashboard, tidak ditulis.
     */
    private fun mergeLiveTickIntoDashboard(tick: MarketTick) {
        val existing = _dashboardTicks.value[tick.symbol] ?: return
        if (existing.volume24h <= 0.0 || tick.price <= 0.0) return
        val openRef = if (existing.price > 0.0 && existing.change24h.isFinite() && existing.change24h > -99.0) {
            existing.price / (1.0 + existing.change24h / 100.0)
        } else 0.0
        val newChange = if (openRef > 0.0) ((tick.price - openRef) / openRef) * 100.0 else existing.change24h
        val merged = existing.copy(
            price = tick.price,
            high24h = maxOf(existing.high24h, tick.price),
            low24h = if (existing.low24h > 0.0) minOf(existing.low24h, tick.price) else tick.price,
            change24h = newChange,
            timestamp = tick.timestamp
        )
        _dashboardTicks.value = _dashboardTicks.value + (tick.symbol to merged)
    }

    private fun updateRecentPrices(price: Double) {
        val prices = _recentPrices.value.toMutableList().apply { add(price) }
        if (prices.size > 50) prices.removeAt(0)
        _recentPrices.value = prices
    }

    fun restoreFromCache(source: MarketDataSource) {
        val age = marketCache.dashboardCacheAgeMs(source)
        if (age < 0L || age > MarketDataCache.MAX_DASHBOARD_CACHE_AGE_MS) return
        val ticks = marketCache.loadDashboardTicks(source)
        if (ticks.isNotEmpty()) {
            _dashboardTicks.value = ticks
            _isShowingCachedData.value = true
        }
    }

    fun loadPairCache(symbol: String, timeframe: Timeframe): Boolean {
        val (cachedTick, cachedCandles) = marketCache.loadPairSnapshot(symbol, timeframe, prefs.marketDataSource)
        if (cachedTick != null || cachedCandles.isNotEmpty()) {
            // Hanya isi _currentTick jika belum ada live ticker untuk pair ini
            if (cachedTick != null && (_currentTick.value == null || _currentTick.value?.symbol != symbol)) {
                _currentTick.value = cachedTick
            }
            if (cachedCandles.isNotEmpty()) {
                _recentCandles.value = cachedCandles
                engine.resetForOffline()
                _currentTick.value?.let { engine.onTickUpdate(it) }
            }
            _isShowingCachedData.value = true
            return true
        }
        return false
    }

    fun startMarketPolling(pair: TradingPair, timeframe: Timeframe) {
        currentActivePair = pair
        currentActiveTimeframe = timeframe
        marketPollJob?.cancel()
        uiPriceThrottler.reset()
        agu.analys.util.AppLogManager.market("MarketFeed", "Mulai streaming feed data untuk ${pair.symbol} [${timeframe.label}] via ${prefs.marketDataSource.label}")

        // 1. Prime harga instan dari dashboard cache jika ada
        val primeTick = _dashboardTicks.value[pair.symbol] 
            ?: _dashboardTicks.value[pair.effectiveAltPair()]
            ?: _dashboardTicks.value[pair.effectiveTokocryptoPair()]
            ?: _dashboardTicks.value[pair.effectiveCompactSymbol()]
            ?: _dashboardTicks.value[pair.symbol.uppercase()]
        if (primeTick != null && (_currentTick.value == null || _currentTick.value?.symbol != pair.symbol)) {
            val primed = primeTick.copy(symbol = pair.symbol)
            _connectionState.value = MarketConnectionState.Connected
            uiPriceThrottler.emitImmediate(primed)
        }

        tokocryptoWebSocket.start(pair.effectiveTokocryptoPair())

        marketPollJob = scope.launch {
            if (_currentTick.value == null) _connectionState.value = MarketConnectionState.Loading
            var failCount = 0
            lastCandleRefresh = 0L
            lastDepthRefresh = 0L

            // 2. Immediate Parallel Bootstrap (REST Ticker & Candles Langsung dieksekusi detik pertama)
            launch {
                val prev = _currentTick.value?.price ?: 0.0
                val tick = TokocryptoMarketService.fetchTicker(pair.effectiveTokocryptoPair(), prevPrice = prev)
                if (tick != null && tick.price > 0 && currentActivePair?.symbol == pair.symbol) {
                    lastLiveTickAt = System.currentTimeMillis()
                    _connectionState.value = MarketConnectionState.Connected
                    _isShowingCachedData.value = false
                    val normalizedTick = tick.copy(symbol = pair.symbol)
                    _dashboardTicks.value = _dashboardTicks.value.toMutableMap().apply { put(pair.symbol, normalizedTick) }
                    uiPriceThrottler.emitImmediate(normalizedTick)
                }
            }

            launch {
                val candles = TokocryptoMarketService.fetchCandles(pair.effectiveTokocryptoPair(), timeframe, 300)
                if (candles.isNotEmpty() && currentActivePair?.symbol == pair.symbol) {
                    _recentCandles.value = candles
                    engine.resetForOffline(preserveState = true)
                    _currentTick.value?.let { engine.onTickUpdate(it) }
                    lastCandleRefresh = System.currentTimeMillis()
                    marketCache.savePairSnapshot(pair.symbol, timeframe, _currentTick.value, candles, prefs.marketDataSource)
                }
            }

            while (isActive) {
                // Sambung ulang hanya bila socket benar-benar usang, dan lewat reconnectNow()
                // supaya candle menit berjalan tidak ikut terbuang (K7).
                tokocryptoWebSocket.reconnectIfStale()

                val now = System.currentTimeMillis()
                val wsFresh = activeWsLive() && (now - lastLiveTickAt < 8_000L)

                // REST ticker hanya sebagai fallback jika WS tidak fresh
                if (!wsFresh) {
                    val prev = _currentTick.value?.price ?: 0.0
                    val tick = TokocryptoMarketService.fetchTicker(pair.effectiveTokocryptoPair(), prevPrice = prev)
                    if (tick != null && tick.price > 0 && currentActivePair?.symbol == pair.symbol) {
                        failCount = 0
                        lastLiveTickAt = now
                        _connectionState.value = MarketConnectionState.Connected
                        _isShowingCachedData.value = false
                        val normalizedTick = tick.copy(symbol = pair.symbol)
                        _dashboardTicks.value = _dashboardTicks.value.toMutableMap().apply { put(pair.symbol, normalizedTick) }
                        uiPriceThrottler.submit(normalizedTick)
                    } else {
                        failCount++
                        if (failCount >= 4 && now - lastLiveTickAt > 25_000L) {
                            _isShowingCachedData.value = true
                            _connectionState.value = MarketConnectionState.ConnectionLost("Koneksi ${prefs.marketDataSource.label} lemah. Pakai cache.")
                        }
                    }
                }

                // Candle: 30 detik (cukup untuk chart)
                if (now - lastCandleRefresh >= 30_000L) {
                    val candles = TokocryptoMarketService.fetchCandles(pair.effectiveTokocryptoPair(), timeframe, 300)
                    if (candles.size >= 10 && currentActivePair?.symbol == pair.symbol) {
                        _recentCandles.value = candles
                        engine.resetForOffline(preserveState = true)
                        _currentTick.value?.let { engine.onTickUpdate(it) }
                        lastCandleRefresh = now
                        marketCache.savePairSnapshot(pair.symbol, timeframe, _currentTick.value, candles, prefs.marketDataSource)
                    }
                }

                // Orderbook + trades: 20 detik
                if (now - lastDepthRefresh >= 20_000L) {
                    val depth = async {
                        TokocryptoMarketService.fetchOrderBook(pair.effectiveTokocryptoPair())
                    }
                    val trades = async {
                        TokocryptoMarketService.fetchRecentTrades(pair.effectiveTokocryptoPair())
                    }
                    val (bids, asks) = depth.await()
                    val newTrades = trades.await()
                    if (currentActivePair?.symbol == pair.symbol) {
                        if (bids.isNotEmpty()) _orderBookBids.value = bids
                        if (asks.isNotEmpty()) _orderBookAsks.value = asks
                        if (bids.isNotEmpty() || asks.isNotEmpty()) {
                            engine.onOrderBookUpdate(bids, asks)
                            val depthExchange = "TOKOCRYPTO"
                            agu.analys.data.OrderBookDepthCache.updateOrderBook(pair.symbol, bids, asks, depthExchange)
                        }
                        if (newTrades.isNotEmpty()) _tradeStream.value = newTrades
                        lastDepthRefresh = now
                    }
                }

                // Interval loop utama: 6–8 detik cukup
                delay(if (wsFresh) 8000L else 5000L)
            }
        }
    }

    fun switchTimeframe(pair: TradingPair, timeframe: Timeframe) {
        currentActivePair = pair
        currentActiveTimeframe = timeframe
        // 1. Muat candle snapshot dari cache untuk timeframe baru tanpa menyentuh live ticker
        val (_, cachedCandles) = marketCache.loadPairSnapshot(pair.symbol, timeframe, prefs.marketDataSource)
        if (cachedCandles.isNotEmpty()) {
            _recentCandles.value = cachedCandles
            engine.resetForOffline(preserveState = true)
            _currentTick.value?.let { engine.onTickUpdate(it) }
        }

        // 2. Fetch candle terbaru untuk timeframe baru secara asynchronous
        scope.launch {
            val candles = TokocryptoMarketService.fetchCandles(pair.effectiveTokocryptoPair(), timeframe, 300)
            if (candles.isNotEmpty() && currentActivePair?.symbol == pair.symbol) {
                _recentCandles.value = candles
                engine.resetForOffline(preserveState = true)
                _currentTick.value?.let { engine.onTickUpdate(it) }
                lastCandleRefresh = System.currentTimeMillis()
                // Update snapshot cache untuk timeframe ini dengan ticker aktif saat ini
                marketCache.savePairSnapshot(pair.symbol, timeframe, _currentTick.value, candles, prefs.marketDataSource)
            }
        }
    }

    fun stopPolling() {
        marketPollJob?.cancel()
        tokocryptoWebSocket.stop(false)
        uiPriceThrottler.reset()
    }

    /**
     * Memutus total seluruh koneksi (WebSocket & Polling) dan membersihkan seluruh cache pasar
     * saat pengguna mengganti exchange di Settings dan menekan tombol Simpan.
     */
    fun hardStopAndPurgeAll(source: MarketDataSource) {
        // 1. Hard stop seluruh job dan socket kedua bursa
        marketPollJob?.cancel()
        marketPollJob = null
        dashboardPollJob?.cancel()
        dashboardPollJob = null
        tokocryptoWebSocket.stop(false)
        uiPriceThrottler.reset()
        currentActivePair = null
        setWsLive(false)
        lastLiveTickAt = 0L

        // 2. Kosongkan semua data in-memory StateFlow
        _dashboardTicks.value = emptyMap()
        _currentTick.value = null
        _recentPrices.value = emptyList()
        _recentCandles.value = emptyList()
        _orderBookBids.value = emptyList()
        _orderBookAsks.value = emptyList()
        _tradeStream.value = emptyList()
        _connectionState.value = MarketConnectionState.Loading
        _isShowingCachedData.value = false

        // 3. Reset engine dan purge seluruh cache bursa
        engine.resetForOffline()
        agu.analys.data.OrderBookDepthCache.clear()
        agu.analys.util.MtfCacheManager.clear()
        agu.analys.engine.sell.TickHistoryTracker.clear()
        marketCache.clearCacheForSource(source)
        agu.analys.util.AppLogManager.market("HardStop", "🛑 HARD STOP: Seluruh koneksi diputus & cache dibersihkan untuk ${source.label}.")
    }

    fun startDashboardPolling(onDashboardUpdate: (Map<String, MarketTick>) -> Unit) {
        dashboardPollJob?.cancel()
        dashboardPollJob = scope.launch {
            while (isActive) {
                onDashboardUpdate(_dashboardTicks.value)
                delay(15_000L)
            }
        }
    }

    fun updateDashboardTicks(ticks: Map<String, MarketTick>) {
        _dashboardTicks.value = agu.analys.util.MarketTickMerge.newest(_dashboardTicks.value, ticks)
    }

    fun markOffline(reason: String) {
        marketPollJob?.cancel()
        val lastPrice = _currentTick.value?.price ?: 0.0
        val lastCandles = _recentCandles.value
        if (_dashboardTicks.value.isEmpty()) {
            _currentTick.value = null
            _recentPrices.value = emptyList()
            _recentCandles.value = emptyList()
            _orderBookBids.value = emptyList()
            _orderBookAsks.value = emptyList()
            _tradeStream.value = emptyList()
            engine.resetForOffline(false, lastPrice, lastCandles)
        } else engine.resetForOffline(false, lastPrice, lastCandles)
        _isShowingCachedData.value = _dashboardTicks.value.isNotEmpty() || _currentTick.value != null
        _connectionState.value = MarketConnectionState.ConnectionLost(reason)
    }

    fun clearPairData(symbolToPrime: String? = null) {
        uiPriceThrottler.reset()
        val prime = if (!symbolToPrime.isNullOrBlank()) {
            _dashboardTicks.value[symbolToPrime] ?: _dashboardTicks.value[symbolToPrime.uppercase()]
        } else null
        _currentTick.value = prime
        _recentPrices.value = if (prime != null) listOf(prime.price) else emptyList()
        _recentCandles.value = emptyList()
        _orderBookBids.value = emptyList()
        _orderBookAsks.value = emptyList()
        _tradeStream.value = emptyList()
    }
}
