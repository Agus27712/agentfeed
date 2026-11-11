package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.model.CandleBar
import agu.analys.model.ChartStyle
import agu.analys.model.CoinBadge
import agu.analys.model.MarketConnectionState
import agu.analys.model.MarketTick
import agu.analys.model.Timeframe
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.service.TokocryptoMarketService
import agu.analys.util.AppPreferences
import agu.analys.util.MarketDataCache
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

class MarketViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val marketCache = MarketDataCache(application)

    private val _selectedPair = MutableStateFlow(TradingPair.popularPairsForSource(prefs.marketDataSource).first())
    val selectedPair: StateFlow<TradingPair> = _selectedPair.asStateFlow()

    private val _selectedTimeframe = MutableStateFlow(Timeframe.H4)
    val selectedTimeframe: StateFlow<Timeframe> = _selectedTimeframe.asStateFlow()

    private val _connectionState = MutableStateFlow<MarketConnectionState>(MarketConnectionState.Loading)
    val connectionState: StateFlow<MarketConnectionState> = _connectionState.asStateFlow()

    private val _isShowingCachedData = MutableStateFlow(false)
    val isShowingCachedData: StateFlow<Boolean> = _isShowingCachedData.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _dashboardTicks = MutableStateFlow<Map<String, MarketTick>>(emptyMap())
    val dashboardTicks: StateFlow<Map<String, MarketTick>> = _dashboardTicks.asStateFlow()

    private val _worthCoins = MutableStateFlow<List<WorthCoinInfo>>(emptyList())
    val worthCoins: StateFlow<List<WorthCoinInfo>> = _worthCoins.asStateFlow()

    private val _hotCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val hotCoins: StateFlow<List<MarketTick>> = _hotCoins.asStateFlow()

    private val _gainersCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val gainersCoins: StateFlow<List<MarketTick>> = _gainersCoins.asStateFlow()

    private val _losersCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val losersCoins: StateFlow<List<MarketTick>> = _losersCoins.asStateFlow()

    private val _topVolumeCoins = MutableStateFlow<List<MarketTick>>(emptyList())
    val topVolumeCoins: StateFlow<List<MarketTick>> = _topVolumeCoins.asStateFlow()

    private val _coinBadges = MutableStateFlow<Map<String, List<CoinBadge>>>(emptyMap())
    val coinBadges: StateFlow<Map<String, List<CoinBadge>>> = _coinBadges.asStateFlow()

    // Rate USDT/IDR: SSOT ada di ExchangeRateManager (selalu dari data exchange, tanpa hardcode).
    val usdtIdrRate: StateFlow<Double> = agu.analys.util.ExchangeRateManager.usdtIdrRate

    private val _useSimpleChart = MutableStateFlow(false)
    val useSimpleChart: StateFlow<Boolean> = _useSimpleChart.asStateFlow()

    private val _selectedChartStyle = MutableStateFlow(ChartStyle.CANDLES)
    val selectedChartStyle: StateFlow<ChartStyle> = _selectedChartStyle.asStateFlow()

    private val _isChartExpanded = MutableStateFlow(false)
    val isChartExpanded: StateFlow<Boolean> = _isChartExpanded.asStateFlow()

    private val _uiPriceThrottleMs = MutableStateFlow(prefs.priceFeedThrottleMs)
    val uiPriceThrottleMs: StateFlow<Long> = _uiPriceThrottleMs.asStateFlow()

    private val _dashboardAllLimit = MutableStateFlow(agu.analys.util.DashboardRanking.PAGE_SIZE)
    val dashboardAllLimit: StateFlow<Int> = _dashboardAllLimit.asStateFlow()

    fun loadMoreDashboardPairs() {
        _dashboardAllLimit.value += agu.analys.util.DashboardRanking.PAGE_SIZE
        // Evaluasi 15 pair berikutnya dari data yang sudah ada (tanpa request jaringan baru)
        if (latestTicks.isNotEmpty()) evaluateVisibleWorth()
    }

    fun resetDashboardPagination() {
        _dashboardAllLimit.value = agu.analys.util.DashboardRanking.PAGE_SIZE
    }

    private var dashboardPollJob: Job? = null
    private var lastLiveTickAt = 0L

    // Snapshot ticker terakhir untuk evaluasi bertahap (15 pair per halaman)
    private var latestTicks: Map<String, MarketTick> = emptyMap()
    private var lastWatchlist: Set<String> = emptySet()
    private var lastFavorites: Set<String> = emptySet()
    private var lastStrategy: StrategyMode = StrategyMode.SCALPING

    init {
        restoreFromCache(prefs.marketDataSource)
        viewModelScope.launch(Dispatchers.IO) {
            agu.analys.data.TokocryptoSymbolRepository.ensureSymbolsLoaded(false)
            agu.analys.data.TokocryptoSymbolRepository.ensureSymbolsLoaded(false)
        }
    }

    fun setMarketDataSource(source: MarketDataSource) {
        prefs.marketDataSource = source
        viewModelScope.launch(Dispatchers.IO) {
            if (source == MarketDataSource.TOKOCRYPTO) {
                agu.analys.data.TokocryptoSymbolRepository.ensureSymbolsLoaded(false)
            } else {
                // Discovery dinamis pair Tokocrypto — tanpa ini daftar pair terbatas 16 bawaan.
                agu.analys.data.TokocryptoSymbolRepository.ensureSymbolsLoaded(false)
            }
            val pairs = TradingPair.popularPairsForSource(source)
            if (pairs.isNotEmpty()) {
                _selectedPair.value = pairs.first()
            }
            restoreFromCache(source)
            resetDashboardPagination()
            refreshWorthCoinsFromMarket()
        }
    }

    fun restoreFromCache(source: MarketDataSource) {
        val cacheAge = marketCache.dashboardCacheAgeMs(source)
        if (cacheAge < 0L || cacheAge > MarketDataCache.MAX_DASHBOARD_CACHE_AGE_MS) return
        val cached = marketCache.loadDashboardTicks(source)
        if (cached.isNotEmpty()) {
            _dashboardTicks.value = cached
            _isShowingCachedData.value = true
            // Fail-closed: cache lama bisa menyimpan koin yang kini delisted — saring dulu.
            val valid = cached.values.filter {
                it.price > 0 && agu.analys.util.DashboardRanking.isRankable(source, it)
            }
            val gainers = valid.filter { it.change24h > 0 }.sortedByDescending { it.change24h }
            val losers = valid.filter { it.change24h < 0 }.sortedBy { it.change24h }
            // Kurs murni dari exchange (ExchangeRateManager); tanpa tebakan angka.
            val rate = agu.analys.util.ExchangeRateManager.currentRate()
            val topVol = valid.sortedByDescending { tick ->
                agu.analys.util.DashboardRanking.volumeInIdr(tick.symbol, tick.volume24h, rate)
            }
            if (gainers.isNotEmpty()) {
                _gainersCoins.value = gainers
                _hotCoins.value = gainers
            }
            if (losers.isNotEmpty()) _losersCoins.value = losers
            if (topVol.isNotEmpty()) _topVolumeCoins.value = topVol
        }
    }

    fun clearAllState() {
        _dashboardTicks.value = emptyMap()
        _hotCoins.value = emptyList()
        _gainersCoins.value = emptyList()
        _losersCoins.value = emptyList()
        _topVolumeCoins.value = emptyList()
        _worthCoins.value = emptyList()
        _coinBadges.value = emptyMap()
        _isShowingCachedData.value = false
        resetDashboardPagination()
        _connectionState.value = MarketConnectionState.Loading
    }

    fun selectPair(pair: TradingPair) {
        _selectedPair.value = pair
    }

    fun selectTimeframe(timeframe: Timeframe) {
        _selectedTimeframe.value = timeframe
    }

    fun selectCustomSymbol(rawSymbol: String): TradingPair? {
        val trimmed = rawSymbol.trim()
        if (trimmed.isNotBlank()) {
            val pair = TradingPair.fromCustomSymbol(trimmed, "IDR")
            selectPair(pair)
            return pair
        }
        return null
    }

    fun toggleSimpleChart() {
        _useSimpleChart.value = !_useSimpleChart.value
    }

    fun selectChartStyle(style: ChartStyle) {
        _selectedChartStyle.value = style
    }

    fun toggleChartExpanded() {
        _isChartExpanded.value = !_isChartExpanded.value
    }

    fun setUiPriceThrottleMs(ms: Long) {
        val coerced = ms.coerceAtLeast(0L)
        prefs.priceFeedThrottleMs = coerced
        _uiPriceThrottleMs.value = coerced
    }

    fun updateDashboardTicks(ticks: Map<String, MarketTick>) {
        _dashboardTicks.value = agu.analys.util.MarketTickMerge.newest(_dashboardTicks.value, ticks)
    }

    fun getH1Candles(symbol: String): List<CandleBar> {
        val (_, candles) = marketCache.loadPairSnapshot(symbol, Timeframe.H1, source = prefs.marketDataSource)
        return candles
    }

    fun ensureH1Candles(symbol: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentSource = prefs.marketDataSource
            val (_, cached) = marketCache.loadPairSnapshot(symbol, Timeframe.H1, source = currentSource)
            // Ambil ulang bila belum ada, atau candle terakhir lebih tua dari 2 jam (sparkline basi)
            val lastCandleAt = cached.lastOrNull()?.timestamp ?: 0L
            val isStale = cached.isEmpty() || System.currentTimeMillis() - lastCandleAt > 2L * 60L * 60L * 1000L
            if (isStale) {
                try {
                    val isToko = currentSource == MarketDataSource.TOKOCRYPTO
                    val pairObj = TradingPair.fromCustomSymbol(symbol, currentSource.defaultQuoteAsset)
                    val candles = if (isToko) {
                        TokocryptoMarketService.fetchCandles(pairObj.effectiveTokocryptoPair(), Timeframe.H1, 100)
                    } else {
                        TokocryptoMarketService.fetchCandles(pairObj.effectiveAltPair(), Timeframe.H1, 100)
                    }
                    if (candles.isNotEmpty()) {
                        marketCache.savePairSnapshot(symbol, Timeframe.H1, null, candles, source = currentSource)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun startDashboardPolling(watchlistSymbols: Set<String> = emptySet(), favoritesSymbols: Set<String> = emptySet(), activeStrategy: StrategyMode = StrategyMode.SCALPING) {
        dashboardPollJob?.cancel()
        dashboardPollJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000L)
                refreshWorthCoinsFromMarket(watchlistSymbols, favoritesSymbols, activeStrategy, resetPagination = false)
            }
        }
    }

    fun stopDashboardPolling() {
        dashboardPollJob?.cancel()
        dashboardPollJob = null
    }

    fun markMarketOffline(reason: String) {
        _connectionState.value = MarketConnectionState.ConnectionLost(reason = reason)
        _isShowingCachedData.value = true
    }

    fun refreshWorthCoinsFromMarket(
        watchlistSymbols: Set<String> = emptySet(),
        favoritesSymbols: Set<String> = emptySet(),
        activeStrategy: StrategyMode = StrategyMode.SCALPING,
        resetPagination: Boolean = false
    ) {
        if (resetPagination) {
            resetDashboardPagination()
        }
        viewModelScope.launch {
            _isRefreshing.value = true
            val currentSource = prefs.marketDataSource
            val isToko = currentSource == MarketDataSource.TOKOCRYPTO
            val defaultQuote = currentSource.defaultQuoteAsset
            try {
                val (gainers, losers, topVol, allScanned, combinedTicks) = if (isToko) {
                    val rankings = TokocryptoMarketService.fetchMarketRankings(35)
                    val scanned = (rankings.gainers + rankings.losers + rankings.topVolume).distinctBy { it.symbol }
                    Tuple5(rankings.gainers, rankings.losers, rankings.topVolume, scanned, rankings.allTicks)
                } else {
                    val rankings = TokocryptoMarketService.fetchMarketRankings(35)
                    val scanned = (rankings.gainers + rankings.losers + rankings.topVolume).distinctBy { it.symbol }
                    Tuple5(rankings.gainers, rankings.losers, rankings.topVolume, scanned, rankings.allTicks)
                }

                if (gainers.isNotEmpty()) {
                    _gainersCoins.value = gainers
                    _hotCoins.value = gainers
                }
                if (losers.isNotEmpty()) {
                    _losersCoins.value = losers
                }
                if (topVol.isNotEmpty()) {
                    _topVolumeCoins.value = topVol
                }

                if (combinedTicks.isEmpty()) {
                    if (_dashboardTicks.value.isEmpty() && _hotCoins.value.isEmpty()) {
                        markMarketOffline("Tidak ada respons market dari ${currentSource.label}.")
                    } else {
                        _isShowingCachedData.value = true
                    }
                    return@launch
                }

                _dashboardTicks.value = combinedTicks

                try {
                    val btcTick = combinedTicks["BTCIDR"] ?: combinedTicks["BTCUSDT"] ?: combinedTicks["btc_idr"] ?: combinedTicks["BTC"]
                    val usdtTick = combinedTicks["USDTIDR"] ?: combinedTicks["usdt_idr"] ?: combinedTicks["USDT"]
                    if (btcTick != null && btcTick.price > 0) {
                        agu.analys.engine.global.GlobalContextManager.updateFallbackFromTicker(
                            priceIdr = btcTick.price,
                            changePct = btcTick.change24h,
                            usdtRate = usdtTick?.price ?: agu.analys.util.ExchangeRateManager.currentRate()
                        )
                    }
                } catch (_: Exception) {}

                try {
                    val priceMap = combinedTicks.mapValues { it.value.price }
                    // N16 — bursa dioper eksplisit supaya service tidak membangun `AppPreferences`
                    // di setiap tick dashboard.
                    agu.analys.service.TradingForegroundService.updatePrices(
                        getApplication(), priceMap, exchange = currentSource.name
                    )
                } catch (_: Exception) {}

                lastLiveTickAt = System.currentTimeMillis()
                _connectionState.value = MarketConnectionState.Connected
                _isShowingCachedData.value = false
                marketCache.saveDashboardTicks(currentSource, combinedTicks)

                latestTicks = combinedTicks
                lastWatchlist = watchlistSymbols
                lastFavorites = favoritesSymbols
                lastStrategy = activeStrategy
                // Hanya 15 pair pertama (+15 tiap scroll) yang dievaluasi, bukan seluruh pasar sekaligus.
                evaluateVisibleWorth()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Hitung worth score hanya untuk pair yang terlihat: halaman dashboard aktif (15 per halaman),
     * top gainer/volume, watchlist dan favorit. Murni CPU dari snapshot ticker, tanpa request jaringan.
     */
    private fun evaluateVisibleWorth() {
        viewModelScope.launch(Dispatchers.Default) {
            val ticks = latestTicks
            if (ticks.isEmpty()) return@launch
            val source = prefs.marketDataSource
            val isToko = source == MarketDataSource.TOKOCRYPTO
            val defaultQuote = source.defaultQuoteAsset
            val scalpingMode = lastStrategy == StrategyMode.SCALPING
            val watchlistSymbols = lastWatchlist
            val favoritesSymbols = lastFavorites
            val pageSize = agu.analys.util.DashboardRanking.PAGE_SIZE
            val rate = agu.analys.util.ExchangeRateManager.currentRate()

            val ranked = agu.analys.util.DashboardRanking.rankByVolume(ticks.values, rate) {
                agu.analys.util.DashboardRanking.isRankable(source, it)
            }
            val symbols = LinkedHashSet<String>()
            ranked.take(_dashboardAllLimit.value).forEach { symbols.add(it.symbol) }
            _gainersCoins.value.take(pageSize).forEach { symbols.add(it.symbol) }
            _topVolumeCoins.value.take(pageSize).forEach { symbols.add(it.symbol) }
            symbols.addAll(watchlistSymbols)
            symbols.addAll(favoritesSymbols)

            val evaluatedPairs = symbols.map { TradingPair.fromCustomSymbol(it, defaultQuote) }.distinctBy { it.symbol }
            val worth = evaluatedPairs.mapNotNull { pair ->
                val tick = ticks[pair.symbol]
                    ?: ticks[pair.effectiveTokocryptoPair()]
                    ?: ticks[pair.effectiveAltPair()]
                    ?: return@mapNotNull null
                val isUserExplicit = favoritesSymbols.contains(pair.symbol) || watchlistSymbols.contains(pair.symbol)
                val isIdrPair = pair.quoteAsset.equals("IDR", ignoreCase = true)
                val isSafe = if (isToko) {
                    TokocryptoMarketService.isSafeTradableAsset(
                        price = tick.price, volume24h = tick.volume24h,
                        high24h = tick.high24h, low24h = tick.low24h,
                        isIdrPair = isIdrPair, isExplicitlyFavored = isUserExplicit
                    )
                } else {
                    TokocryptoMarketService.isSafeTradableAsset(
                        price = tick.price, volume24h = tick.volume24h,
                        high24h = tick.high24h, low24h = tick.low24h,
                        isIdrPair = isIdrPair, isExplicitlyFavored = isUserExplicit
                    )
                }
                if (!isSafe) return@mapNotNull null

                val rangePct = if (tick.low24h > 0) ((tick.high24h - tick.low24h) / tick.low24h) * 100.0 else 0.0
                // Volume dinormalisasi ke IDR agar pair USDT dinilai setara dengan pair IDR
                val volIdr = agu.analys.util.DashboardRanking.volumeInIdr(tick.symbol, tick.volume24h, rate)
                val volScore = when {
                    volIdr >= 100_000_000_000 -> 30
                    volIdr >= 10_000_000_000 -> 22
                    volIdr >= 1_000_000_000 -> 14
                    else -> 6
                }
                val change24h = tick.change24h.takeIf { it.isFinite() } ?: 0.0
                val momentumScore = when {
                    change24h >= 8 -> 40; change24h >= 3 -> 32; change24h > 0 -> 25
                    change24h >= -3 -> 12; change24h >= -8 -> 6; else -> 2
                }
                val score = (volScore + momentumScore + min(20, (rangePct * 1.5).toInt())).coerceIn(1, 99)
                val rec = when {
                    change24h >= 5.0 -> "PUMP / MOMENTUM NAIK"
                    change24h > 0.0 -> "BERGERAK NAIK"
                    change24h >= -2.0 -> "LAYAK DIPANTAU"
                    change24h <= -8.0 -> "TEKANAN JUAL"
                    else -> "NETRAL / VOLATIL"
                }
                WorthCoinInfo(
                    pair = pair, worthScore = score,
                    isWorthIt = score >= 50 && change24h > 0,
                    recommendation = rec, potentialProfitPct = abs(change24h),
                    aiRationale = "${PriceFormatter.formatPrice(tick.price, quoteAsset = pair.quoteAsset)} · Vol ${PriceFormatter.formatVolume(tick.volume24h, quoteAsset = pair.quoteAsset)}"
                )
            }.sortedWith(
                if (scalpingMode) compareByDescending<WorthCoinInfo> {
                    ticks[it.pair.symbol]?.change24h?.takeIf { c -> c.isFinite() } ?: -999.0
                }.thenByDescending { it.worthScore }
                else compareByDescending { it.worthScore }
            )
            _worthCoins.value = worth
            marketCache.saveWorthCoins(source, worth)
            recalculateDashboardBadges(watchlistSymbols, favoritesSymbols, lastStrategy)
        }
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)


    fun recalculateDashboardBadges(watchlistSymbols: Set<String> = emptySet(), favoritesSymbols: Set<String> = emptySet(), activeStrategy: StrategyMode = StrategyMode.SCALPING) {
        viewModelScope.launch(Dispatchers.Default) {
            val defaultQuote = prefs.marketDataSource.defaultQuoteAsset
            val basePairs = TradingPair.popularPairsForSource(prefs.marketDataSource)
            val watchPairs = watchlistSymbols.map { TradingPair.fromCustomSymbol(it, defaultQuote) }
            val favPairs = favoritesSymbols.map { TradingPair.fromCustomSymbol(it, defaultQuote) }
            val marketPairs = (_gainersCoins.value + _hotCoins.value + _topVolumeCoins.value)
                .map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
            val ticks = _dashboardTicks.value
            val source = prefs.marketDataSource
            val rate = agu.analys.util.ExchangeRateManager.currentRate()
            val limit = _dashboardAllLimit.value
            val visiblePairs = agu.analys.util.DashboardRanking.rankByVolume(ticks.values, rate) {
                agu.analys.util.DashboardRanking.isRankable(source, it)
            }.take(limit).map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
            val allPairs = (visiblePairs + watchPairs + favPairs + marketPairs +
                basePairs.take(agu.analys.util.DashboardRanking.PAGE_SIZE))
                .distinctBy { it.symbol }
                .take(maxOf(40, limit + 25))

            val resultMap = mutableMapOf<String, List<CoinBadge>>()
            for (pair in allPairs) {
                val tick = ticks[pair.symbol]
                if (tick != null) {
                    val badges = agu.analys.engine.badge.CoinBadgeEvaluator.evaluateBadges(
                        pair = pair,
                        tick = tick,
                        activeStrategy = activeStrategy,
                        maxBadges = 1
                    )
                    if (badges.isNotEmpty()) {
                        resultMap[pair.symbol] = badges
                    }
                }
            }
            _coinBadges.value = resultMap
        }
    }
}