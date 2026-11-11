package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job

import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.bridge.TradingViewBridge
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.LearningTradingEngine
import agu.analys.model.*
import agu.analys.trading.*
import agu.analys.util.*
import agu.analys.database.AppDatabase
import agu.analys.database.SignalLogEntity
import agu.analys.database.SignalLogRepository
import agu.analys.database.TradeHistoryRecordEntity
import agu.analys.database.TradeHistoryRecorder
import agu.analys.ui.theme.*
import agu.analys.ui.animation.*

/**
 * TradingViewModel acts as the unified delegation Facade / Mediator.
 * It coordinates and forwards state flows and calls directly to the 7 specialized, UI-driven ViewModels:
 * SettingsViewModel, MarketViewModel, WatchlistViewModel, OrderViewModel, PortfolioViewModel, SignalLogViewModel, and AiNewsViewModel.
 *
 * This design achieves clean separation of concerns and reduces the main viewmodel class to under 400 lines,
 * while maintaining 100% backward compatibility with existing screen files.
 */
class TradingViewModel(application: Application) : AndroidViewModel(application) {

    // ═════════════════════════════════════════════════════════════════════════
    // 1. SUB-VIEWMODELS (UI-DRIVEN FEATURES)
    // ═════════════════════════════════════════════════════════════════════════
    val marketViewModel = MarketViewModel(application)
    val watchlistViewModel = WatchlistViewModel(application)
    val aiNewsViewModel = AiNewsViewModel(application)
    val settingsViewModel = SettingsViewModel(application)
    val portfolioViewModel = PortfolioViewModel(application)
    // J3 — bursa diteruskan sebagai **aliran**, bukan dibaca sekali. Kalau dibaca sekali di
    // dalam `SignalLogViewModel`, parameter Room terikat saat flow dibuat dan mengganti bursa
    // tidak pernah mengulang subscription (tabel log & reliabilitas tetap berisi bursa lama).
    val signalLogViewModel = SignalLogViewModel(application, settingsViewModel.marketDataSource)

    // ═════════════════════════════════════════════════════════════════════════
    // 2. CORE COORDINATORS & SHARED SERVICES
    // ═════════════════════════════════════════════════════════════════════════
    val bridge = TradingViewBridge(viewModelScope)
    internal val engine = LearningTradingEngine(viewModelScope)
    internal val prefs = AppPreferences(application)

    // Hydrate the engine from persisted values before market ticks can be evaluated.
    init {
        val savedScalpingConfig = prefs.scalpingRuntimeConfig
        engine.scalpingRuntimeConfig = savedScalpingConfig
        engine.strategyMode = prefs.strategyMode
        engine.isScalpingMode = prefs.isScalpingMode
        engine.tradingFees = prefs.tradingFees
    }
    internal val marketCache = MarketDataCache(application)
    internal val positionStore = SpotPositionStore(application)
    internal val alertStore = PriceAlertStore(application)
    internal val simulationStore = SimulationTradeStore(application)

    // ═════════════════════════════════════════════════════════════════════════
    // 2b. P2 — JURNAL & FSM PAPER TRADE
    //
    // Sengaja dideklarasikan SEBELUM coordinator di bawah: callback tick dapat
    // dipanggil dari init block, sehingga properti ini harus sudah siap lebih dulu.
    // ═════════════════════════════════════════════════════════════════════════
    internal val paperJournalStore = PaperTradeJournalStore(prefs)

    private val _paperTradeJournal =
        MutableStateFlow(paperJournalStore.load())
    val paperTradeJournal: StateFlow<List<agu.analys.engine.scalping.PaperTradeRecord>> =
        _paperTradeJournal.asStateFlow()

    private val _paperTradeStats =
        MutableStateFlow(agu.analys.engine.scalping.PaperTradeStats.computeFromRecords(_paperTradeJournal.value))
    val paperTradeStats: StateFlow<agu.analys.engine.scalping.PaperTradeStats> =
        _paperTradeStats.asStateFlow()

    /**
     * Potret FSM exit **per posisi** — P5.
     *
     * Dulu ini satu `MutableStateFlow` global, padahal `evaluatePaperAutoExit` dipanggil untuk
     * setiap simbol yang mendapat tick: setiap simbol tanpa mesin menimpa flow itu dengan `null`,
     * sehingga kartu FSM di layar simulasi berkedip hilang bila posisi paper-nya ada di pair
     * lain. Kuncinya `PaperAutoTradeRuntime.key(exchange, symbol)` — satu entri per posisi.
     */
    private val _paperExitSnapshots =
        MutableStateFlow<Map<String, agu.analys.engine.scalping.PaperExitSnapshot>>(emptyMap())
    val paperExitSnapshots: StateFlow<Map<String, agu.analys.engine.scalping.PaperExitSnapshot>> =
        _paperExitSnapshots.asStateFlow()

    /** Potret untuk satu posisi; `null` bila posisi itu tidak punya posisi paper terbuka. */
    internal fun paperSnapshotAt(exchange: String, symbol: String): agu.analys.engine.scalping.PaperExitSnapshot? =
        agu.analys.trading.PaperExitSnapshots.forPosition(
            current = _paperExitSnapshots.value,
            key = PaperAutoTradeRuntime.key(exchange, symbol)
        )

    internal fun publishPaperExitSnapshot(
        exchange: String,
        symbol: String,
        snapshot: agu.analys.engine.scalping.PaperExitSnapshot?
    ) {
        _paperExitSnapshots.value = agu.analys.trading.PaperExitSnapshots.publish(
            current = _paperExitSnapshots.value,
            key = PaperAutoTradeRuntime.key(exchange, symbol),
            snapshot = snapshot
        )
    }

    internal fun appendPaperTradeRecord(record: agu.analys.engine.scalping.PaperTradeRecord) {
        val updated = paperJournalStore.append(record)
        _paperTradeJournal.value = updated
        _paperTradeStats.value = agu.analys.engine.scalping.PaperTradeStats.computeFromRecords(updated)
    }

    /** Kosongkan jurnal paper trade (P2). Tidak menyentuh saldo simulasi. */
    fun resetPaperTradeJournal() {
        paperJournalStore.clear()
        _paperTradeJournal.value = emptyList()
        _paperTradeStats.value = agu.analys.engine.scalping.PaperTradeStats.EMPTY
    }

    internal val simCoordinator = SimulationCoordinator(
        store = simulationStore,
        onOrderFilled = { order -> syncSimulationTradeToPositionStore(order) },
        exchangeProvider = { prefs.marketDataSource.name }
    )
    internal val realCoordinator = RealTradeCoordinator(
        scope = viewModelScope,
        prefs = prefs,
        getLatestTick = { symbol -> marketDataCoordinator.dashboardTicks.value[symbol] },
        onBalanceAndAvgUpdated = { balances, avgPrices -> 
            this@TradingViewModel.syncRealBalancesToPositionStore(balances, avgPrices) 
        },
        onRealTradeExecuted = { pair, type, price, quantity, tp1, tp2 ->
            handleRealTradeExecution(pair, type, price, quantity, tp1, tp2)
        }
    )
    val orderViewModel = OrderViewModel(
        application = application,
        customSimCoordinator = simCoordinator,
        customRealCoordinator = realCoordinator
    )
    internal val updateCoordinator = AppUpdateCoordinator(viewModelScope)
    
    internal val signalLogRepository = SignalLogRepository(
        dao = AppDatabase.getInstance().signalLogDao(),
        scope = viewModelScope
    )
    internal val tradeHistoryRecorder = TradeHistoryRecorder(
        dao = AppDatabase.getInstance().tradeHistoryRecordDao(),
        scope = viewModelScope
    )

    internal val marketDataCoordinator = MarketDataCoordinator(
        scope = viewModelScope,
        prefs = prefs,
        marketCache = marketCache,
        engine = engine,
        simCoordinator = simCoordinator,
        onPriceUpdate = { symbol, price, rsi, recentCandles ->
            val currentEx = prefs.marketDataSource.name
            checkAlertsAndTrailing(symbol, price, rsi)
            // P2 — evaluasi exit paper trade (level check tiap tick, hitungan bar per menit).
            // Dibungkus runCatching agar kegagalan exit paper tidak pernah menghentikan tick harga.
            // P6 — jendela momentum tidak lagi dikirim pemanggil: `evaluatePaperAutoExit`
            // membacanya sendiri dari cache M1 supaya hasilnya sama dengan jalur latar.
            runCatching { evaluatePaperAutoExit(symbol, currentEx, price) }
            signalLogRepository.processPriceTick(symbol, price, currentEx)
            // P0-6 — teruskan ekstrem candle supaya Peak/Drawdown jurnal tidak understate.
            val lastCandle = recentCandles.lastOrNull()
            tradeHistoryRecorder.processPriceTick(
                symbol = symbol,
                currentPrice = price,
                exchange = currentEx,
                candleHigh = lastCandle?.high ?: 0.0,
                candleLow = lastCandle?.low ?: 0.0
            )
        }
    )

    internal val positionCoordinator = PositionCoordinator(
        positionStore = positionStore,
        alertStore = alertStore,
        isRealProvider = { isRealBuyMode.value },
        exchangeProvider = { prefs.marketDataSource.name },
        onPositionChanged = { updateForegroundServiceState() }
    )

    // ═════════════════════════════════════════════════════════════════════════
    // 3. STATE FLOWS (DELEGATED DIRECTLY TO SPECIALIZED VIEWMODELS)
    // ═════════════════════════════════════════════════════════════════════════
    
    // Preferences & Theme Delegation
    val isDarkTheme: StateFlow<Boolean> = settingsViewModel.isDarkTheme
    val themeStyle: StateFlow<ThemeStyle> = settingsViewModel.themeStyle
    val accentColorPreset: StateFlow<AccentColorPreset> = settingsViewModel.accentColorPreset
    val candleColorStyle: StateFlow<CandleColorStyle> = settingsViewModel.candleColorStyle
    val animationSpeed: StateFlow<AnimationSpeed> = settingsViewModel.animationSpeed
    val priceAnimationMode: StateFlow<PriceAnimationMode> = settingsViewModel.priceAnimationMode
    val isPriceTickPulseEnabled: StateFlow<Boolean> = settingsViewModel.isPriceTickPulseEnabled
    val isSmoothChartEnabled: StateFlow<Boolean> = settingsViewModel.isSmoothChartEnabled
    val isNotificationsEnabled: StateFlow<Boolean> = settingsViewModel.isNotificationsEnabled
    val isNotifyCandidateBuyEnabled: StateFlow<Boolean> = settingsViewModel.isNotifyCandidateBuyEnabled
    val isNotifyPriceAlertsEnabled: StateFlow<Boolean> = settingsViewModel.isNotifyPriceAlertsEnabled
    val isNotifyTrailingStopEnabled: StateFlow<Boolean> = settingsViewModel.isNotifyTrailingStopEnabled
    val isNotifyEmergencyExitEnabled: StateFlow<Boolean> = settingsViewModel.isNotifyEmergencyExitEnabled
    val isRealSimSyncEnabled: StateFlow<Boolean> = settingsViewModel.isRealSimSyncEnabled
    val marketDataSource: StateFlow<MarketDataSource> = settingsViewModel.marketDataSource

    internal val _selectedPair: StateFlow<TradingPair> get() = marketViewModel.selectedPair

    val spotPosition: StateFlow<SpotPosition> = positionCoordinator.spotPosition
    val positionVersion: StateFlow<Long> = positionCoordinator.positionVersion
    val priceAlerts: StateFlow<List<PriceAlert>> = positionCoordinator.priceAlerts
    val mtfState: StateFlow<Map<String, Map<Timeframe, MtfStatus>>> = agu.analys.util.MtfCacheManager.mtfState

    // Local settings states for quick engine integration
    private val _strategyMode = MutableStateFlow(prefs.strategyMode)
    val strategyMode: StateFlow<StrategyMode> = _strategyMode.asStateFlow()

    private val _isScalpingMode = MutableStateFlow(prefs.isScalpingMode)
    val isScalpingMode: StateFlow<Boolean> = _isScalpingMode.asStateFlow()

    private val _scalpingRuntimeConfig = MutableStateFlow(prefs.scalpingRuntimeConfig)
    val scalpingRuntimeConfig: StateFlow<agu.analys.engine.scalping.ScalpingRuntimeConfig> = _scalpingRuntimeConfig.asStateFlow()

    private val _scalpingSensitivityPreset = MutableStateFlow(prefs.scalpingSensitivityPreset)
    val scalpingSensitivityPreset: StateFlow<String> = _scalpingSensitivityPreset.asStateFlow()

    /** Konfigurasi slider mode INTRADAY — terpisah dari scalping (aturan §79.1). */
    private val _intradayRuntimeConfig = MutableStateFlow(prefs.intradayRuntimeConfig)
    val intradayRuntimeConfig: StateFlow<agu.analys.engine.intraday.IntradayConfig> =
        _intradayRuntimeConfig.asStateFlow()

    private val _intradaySensitivityPreset = MutableStateFlow(prefs.intradaySensitivityPreset)
    val intradaySensitivityPreset: StateFlow<String> = _intradaySensitivityPreset.asStateFlow()

    /** Konfigurasi slider mode SWING — terpisah dari scalping/intraday (aturan §79.1). */
    private val _swingRuntimeConfig = MutableStateFlow(prefs.swingRuntimeConfig)
    val swingRuntimeConfig: StateFlow<agu.analys.engine.swing.SwingConfig> =
        _swingRuntimeConfig.asStateFlow()

    private val _swingSensitivityPreset = MutableStateFlow(prefs.swingSensitivityPreset)
    val swingSensitivityPreset: StateFlow<String> = _swingSensitivityPreset.asStateFlow()

    /** Pengaturan auto-buy / auto-exit paper trade (P2). Simulasi saja. */
    private val _paperAutoTradeConfig = MutableStateFlow(prefs.paperAutoTradeConfig)
    val paperAutoTradeConfig: StateFlow<agu.analys.engine.scalping.PaperAutoTradeConfig> =
        _paperAutoTradeConfig.asStateFlow()

    private val _tradingFees = MutableStateFlow(prefs.tradingFees)
    val tradingFees: StateFlow<TradingFeeConfig> = _tradingFees.asStateFlow()

    val useSimpleChart: StateFlow<Boolean> = marketViewModel.useSimpleChart
    val selectedChartStyle: StateFlow<ChartStyle> = marketViewModel.selectedChartStyle

    // Navigation Delegation
    internal val _currentScreen = MutableStateFlow(AppScreen.DASHBOARD)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()
    internal val navigationStack = mutableListOf<AppScreen>()

    // Dashboard Quick Filter Tab Delegation (Preserved across back navigation)
    private val _dashboardQuickFilter = MutableStateFlow(agu.analys.ui.components.dashboard.DashboardQuickFilter.ALL)
    val dashboardQuickFilter: StateFlow<agu.analys.ui.components.dashboard.DashboardQuickFilter> = _dashboardQuickFilter.asStateFlow()

    val dashboardAllLimit: StateFlow<Int> = marketViewModel.dashboardAllLimit

    fun loadMoreDashboardPairs() {
        marketViewModel.loadMoreDashboardPairs()
    }

    fun resetDashboardPagination() {
        marketViewModel.resetDashboardPagination()
    }

    fun setDashboardQuickFilter(filter: agu.analys.ui.components.dashboard.DashboardQuickFilter) {
        _dashboardQuickFilter.value = filter
        if (filter == agu.analys.ui.components.dashboard.DashboardQuickFilter.ALL) {
            marketViewModel.resetDashboardPagination()
        }
    }

    // Market Ticker & Candlestick Delegation
    val selectedPair: StateFlow<TradingPair> = marketViewModel.selectedPair
    val selectedTimeframe: StateFlow<Timeframe> = marketViewModel.selectedTimeframe
    val connectionState: StateFlow<MarketConnectionState> = marketViewModel.connectionState
    val isShowingCachedData: StateFlow<Boolean> = marketViewModel.isShowingCachedData
    val isRefreshing: StateFlow<Boolean> = marketViewModel.isRefreshing
    val dashboardTicks: StateFlow<Map<String, MarketTick>> = marketViewModel.dashboardTicks
    val worthCoins: StateFlow<List<WorthCoinInfo>> = marketViewModel.worthCoins
    val hotCoins: StateFlow<List<MarketTick>> = marketViewModel.hotCoins
    val gainersCoins: StateFlow<List<MarketTick>> = marketViewModel.gainersCoins
    val losersCoins: StateFlow<List<MarketTick>> = marketViewModel.losersCoins
    val topVolumeCoins: StateFlow<List<MarketTick>> = marketViewModel.topVolumeCoins
    val usdtIdrRate: StateFlow<Double> = marketViewModel.usdtIdrRate
    val coinBadges: StateFlow<Map<String, List<CoinBadge>>> = marketViewModel.coinBadges

    // Market details and real-time streams
    val recentPrices: StateFlow<List<Double>> = marketDataCoordinator.recentPrices
    val recentCandles: StateFlow<List<CandleBar>> = marketDataCoordinator.recentCandles
    val isChartExpanded: StateFlow<Boolean> = marketViewModel.isChartExpanded
    val currentTick: StateFlow<MarketTick?> = marketDataCoordinator.currentTick
    val uiPriceThrottleMs: StateFlow<Long> = marketDataCoordinator.uiPriceThrottleMs
    val currentIndicators: StateFlow<TechnicalIndicators> = engine.indicators
    val aiSignalState: StateFlow<AISignalState> = engine.signalState
    val orderBookBids: StateFlow<List<OrderBookItem>> = marketDataCoordinator.orderBookBids
    val orderBookAsks: StateFlow<List<OrderBookItem>> = marketDataCoordinator.orderBookAsks
    val tradeStream: StateFlow<List<TradeStreamItem>> = marketDataCoordinator.tradeStream

    // Watchlist & Favorites Delegation
    val watchlist: StateFlow<Set<String>> = watchlistViewModel.watchlist
    val favorites: StateFlow<Set<String>> = watchlistViewModel.favorites

    // Simulation Portfolio Delegation
    val simulationWallet: StateFlow<SimulationWallet> = orderViewModel.simulationWallet
    val simulationOpenOrders: StateFlow<List<SimulationOrder>> = orderViewModel.simulationOpenOrders
    val simulationHistory: StateFlow<List<SimulationTradeHistoryItem>> = orderViewModel.simulationHistory
    val lastFilledSimulationOrder: StateFlow<SimulationOrder?> = orderViewModel.lastFilledSimulationOrder

    // Real Balance & Trades Delegation
    val isRealBuyMode: StateFlow<Boolean> = orderViewModel.isRealBuyMode
    val isPinUnlocked: StateFlow<Boolean> = orderViewModel.isPinUnlocked
    val realSpotBalance: StateFlow<Map<String, Double>> = orderViewModel.realSpotBalance
    val realFreeBalance: StateFlow<Map<String, Double>> = orderViewModel.realFreeBalance
    val realLockedBalance: StateFlow<Map<String, Double>> = orderViewModel.realLockedBalance
    val realOpenOrders: StateFlow<List<RealOpenOrderEntity>> = orderViewModel.realOpenOrders
    val realTrades: StateFlow<List<RealTradeEntity>> = orderViewModel.realTrades
    val realAvgBuyPrices: StateFlow<Map<String, Double>> = orderViewModel.realAvgBuyPrices
    val isFetchingRealBalance: StateFlow<Boolean> = orderViewModel.isFetchingRealBalance

    /** Rate konversi USDT/IDR live dari exchange (0.0 = belum tersedia). */
    val usdtIdrRateState: StateFlow<Double> = agu.analys.util.ExchangeRateManager.usdtIdrRate
    val realTradeStatus: StateFlow<String> = orderViewModel.realTradeStatus
    val userPublicIp: StateFlow<String?> = orderViewModel.userPublicIp
    val failedPinAttempts: StateFlow<Int> = orderViewModel.failedPinAttempts

    // AI states Delegation
    val auditReportText: StateFlow<String?> = aiNewsViewModel.auditReportText
    val isAuditLoading: StateFlow<Boolean> = aiNewsViewModel.isAuditLoading
    val geminiSummaryText: StateFlow<String?> = aiNewsViewModel.geminiSummaryText
    val isGeminiLoading: StateFlow<Boolean> = aiNewsViewModel.isGeminiLoading

    internal val _signalHistory = MutableStateFlow<List<AISignalState>>(emptyList())
    val signalHistory: StateFlow<List<AISignalState>> = _signalHistory.asStateFlow()

    // History Records & AI summaries
    val allSignalLogs: StateFlow<List<SignalLogEntity>> = signalLogViewModel.allSignalLogs
    val signalReliabilitySummary: StateFlow<SignalReliabilitySummary> = signalLogViewModel.signalReliabilitySummary
    val tradeHistoryRecords: StateFlow<List<TradeHistoryRecordEntity>> = portfolioViewModel.tradeHistoryRecords
    val newsScreenerState: StateFlow<NewsScreenerUiState> = aiNewsViewModel.newsScreenerState

    // Global Market and contextual states
    val globalContext: StateFlow<agu.analys.engine.global.GlobalMarketContext> = agu.analys.engine.global.GlobalContextManager.context
    
    val holdingStatuses: StateFlow<Map<String, CoinHoldingStatus>> = kotlinx.coroutines.flow.combine(
        watchlist, favorites, positionCoordinator.spotPosition, isRealBuyMode, positionCoordinator.positionVersion
    ) { w, f, pos, isReal, _ ->
        val currentEx = prefs.marketDataSource.name
        val simWallet = simCoordinator.wallet.value
        val storedSymbols = positionStore.getAllStoredSymbols(isReal, currentEx).toMutableList()
        if (!isReal) {
            simWallet.coinBalances.forEach { (coin, qty) ->
                if (qty > 0.00000001 && !coin.equals("IDR", true) && !coin.equals("USDT", true)) {
                    val q = simWallet.quoteForCoin(coin).ifBlank { "IDR" }
                    val sym = "${coin.uppercase()}${q.uppercase()}"
                    if (!storedSymbols.contains(sym)) storedSymbols.add(sym)
                }
            }
        }
        (w + f + listOf(pos.symbol) + storedSymbols).distinct().associateWith { sym ->
            val p = positionStore.get(sym, isReal, currentEx)
            val baseUpper = baseFromSymbolOrPair(sym).uppercase()
            val simQty = if (!isReal) {
                (simWallet.coinBalances[baseUpper] ?: simWallet.coinBalances[baseUpper.lowercase()] ?: 0.0) +
                (simWallet.lockedCoinBalances[baseUpper] ?: simWallet.lockedCoinBalances[baseUpper.lowercase()] ?: 0.0)
            } else 0.0
            val coinQuote = if (!isReal) simWallet.quoteForCoin(baseUpper).ifBlank { "IDR" } else ""
            val pairQuote = if (sym.endsWith("USDT", true)) "USDT" else "IDR"
            val isSimQuoteMatch = !isReal && pairQuote.equals(coinQuote, true) && simQty > 0.00000001

            val isHolding = if (p.isHolding) true else isSimQuoteMatch
            val finalQty = if (p.quantity > 0.00000001) p.quantity else if (isSimQuoteMatch) simQty else 0.0
            val finalEntry = if (p.entryPrice > 0.0) p.entryPrice else if (isSimQuoteMatch) (simWallet.avgBuyPrices[baseUpper] ?: 0.0) else 0.0

            CoinHoldingStatus(
                isHolding = isHolding,
                quantity = finalQty,
                entryPrice = finalEntry,
                isReal = p.isReal,
                tp1Price = p.tp1Price,
                tp2Price = p.tp2Price,
                stopLossPrice = if (p.stopLossPrice > 0.0) p.stopLossPrice else if (finalEntry > 0.0) finalEntry * 0.99 else 0.0,
                isTrailingEnabled = p.isTrailingEnabled,
                isTrailingTriggered = p.isTrailingTriggered
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val positionContext: StateFlow<PositionContext> = kotlinx.coroutines.flow.combine(
        positionCoordinator.spotPosition, marketDataCoordinator.currentTick, _tradingFees
    ) { pos, tick, fees ->
        PositionContext.create(
            symbol = pos.symbol,
            spotPosition = pos,
            holdingStatus = null,
            currentPrice = tick?.price ?: pos.entryPrice,
            fees = fees,
            currentModeIsReal = pos.isReal
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PositionContext())

    val tradingWorkflow: StateFlow<TradingWorkflow> = positionContext.map { resolveWorkflow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TradingWorkflow.BUY)

    val sellSignalState: StateFlow<SellSignalState> = kotlinx.coroutines.flow.combine(
        positionContext, engine.indicators, _tradingFees, marketDataCoordinator.currentTick
    ) { ctx, ind, fees, tick ->
        // FeeTruth: pair IDR harus dinilai dengan tarif IDR (jual taker 0,4322%), bukan preset
        // USDT global — kalau tidak, ambang "balik modal" kartu jual salah untuk pair IDR.
        // Simbol milik posisi yang dievaluasi, bukan pair yang kebetulan terakhir dipilih.
        val feeForPair = ctx.symbol
            .takeIf { it.isNotBlank() }
            ?.let { prefs.feeConfigFor(it) }
            ?: fees
        agu.analys.engine.sell.SellSignalEvaluator.evaluate(
            context = ctx,
            indicators = ind,
            tradingFees = feeForPair,
            high24h = tick?.high24h ?: 0.0
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SellSignalState())

    /** Aggregated detail UI state — prefer this over 25+ individual collects. */

    // Update States
    val githubReleaseInfo: StateFlow<GitHubReleaseInfo?> = updateCoordinator.releaseInfo
    val updateCheckStatus: StateFlow<String?> = updateCoordinator.updateCheckStatus
    val isCheckingUpdate: StateFlow<Boolean> = updateCoordinator.isCheckingUpdate
    val updateDownloadProgress: StateFlow<Int?> = updateCoordinator.downloadProgress

    // Polling handles
    internal var trailingPollJob: Job? = null
    internal var lastSavedSignalTimestamp = 0L

    init {
        initSubscriptionsAndPolling()
        agu.analys.bridge.AgentTradingCommandHandler.register(
            real = realCoordinator,
            sim = simCoordinator,
            priceProvider = { symbol -> marketDataCoordinator.dashboardTicks.value[symbol]?.price }
        )
        viewModelScope.launch(Dispatchers.IO) {
            agu.analys.bridge.AgentTradingCommandHandler.publishPortfolioSnapshot(application)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. MAIN DELEGATED LOGIC METHODS
    // ═════════════════════════════════════════════════════════════════════════

    fun setMarketDataSource(source: MarketDataSource, forceHardStop: Boolean = true) {
        val previousSource = prefs.marketDataSource

        // 1. Eksekusi Hard Stop otomatis jika berganti bursa atau di-force
        if (forceHardStop || previousSource != source) {
            marketDataCoordinator.hardStopAndPurgeAll(previousSource)
            marketViewModel.clearAllState()
            engine.resetForOffline()
        }

        // 2. Simpan dan alihkan bursa
        settingsViewModel.setMarketDataSource(source)
        prefs.marketDataSource = source
        MtfCacheManager.updateExchange(source.name)
        watchlistViewModel.reloadForDataSource(source)

        // 3. Muat daftar simbol dinamis Tokocrypto
        viewModelScope.launch(Dispatchers.IO) {
            agu.analys.data.TokocryptoSymbolRepository.ensureSymbolsLoaded(false)
        }

        // 4. Pilih pair default bursa baru
        val popular = TradingPair.popularPairsForSource(source)
        val first = popular.firstOrNull() ?: TradingPair.fromCustomSymbol("BTC", source.defaultQuoteAsset)
        selectPair(first)

        // 5. Hubungkan kembali stream & polling secara bersih HANYA untuk bursa baru
        refreshWorthCoinsFromMarket()
        marketDataCoordinator.startMarketPolling(first, selectedTimeframe.value)

        // 6. Tarik saldo real sesuai bursa baru jika kredensial tersedia
        if (prefs.hasTokocryptoCredentials()) {
            fetchRealBalance()
        }
    }

    fun setStrategyMode(mode: StrategyMode) {
        val previousMode = _strategyMode.value
        _strategyMode.value = mode
        prefs.strategyMode = mode
        val scalpingEnabled = mode == StrategyMode.SCALPING
        _isScalpingMode.value = scalpingEnabled
        prefs.isScalpingMode = scalpingEnabled
        engine.strategyMode = mode
        engine.isScalpingMode = scalpingEnabled
        engine.tradingFees = prefs.tradingFees
        agu.analys.util.AppLogManager.service("StrategyConfig", "⚙️ Mode strategi sistem dialihkan ke ${mode.name} (Scalping=$scalpingEnabled)")

        // Default timeframe chart ikut mode (2026-10-26): hanya saat mode benar-benar
        // berganti, supaya simpan ulang pengaturan tidak memaksa chart lompat ke default.
        // Keputusan timeframe lewat resolver murni `resolveDefaultTimeframe` — jangan
        // hardcode pemetaan di sini. Polling di akhir fungsi otomatis memakai TF baru.
        if (mode != previousMode) {
            val defaultTf = resolveDefaultTimeframe(mode)
            if (marketViewModel.selectedTimeframe.value != defaultTf) {
                marketViewModel.selectTimeframe(defaultTf)
                val loaded = marketDataCoordinator.loadPairCache(selectedPair.value.symbol, defaultTf)
                if (!loaded) marketDataCoordinator.clearPairData(selectedPair.value.symbol)
            }
        }

        if (mode == StrategyMode.SCALPING) {
            MtfCacheManager.setActiveSymbol(selectedPair.value.symbol, prefs.marketDataSource.name)
        }
        
        marketDataCoordinator.startMarketPolling(selectedPair.value, selectedTimeframe.value)
        val tick = marketDataCoordinator.currentTick.value
        if (tick != null) {
            engine.resetForOffline()
            engine.onTickUpdate(tick)
        }
        marketViewModel.resetDashboardPagination()
        refreshWorthCoinsFromMarket(resetPagination = true)
    }

    fun setScalpingMode(enabled: Boolean) {
        setStrategyMode(if (enabled) StrategyMode.SCALPING else StrategyMode.SWING)
    }

    fun updateScalpingRuntimeConfig(
        config: agu.analys.engine.scalping.ScalpingRuntimeConfig,
        preset: String = "CUSTOM"
    ) {
        prefs.scalpingRuntimeConfig = config
        prefs.scalpingSensitivityPreset = preset
        engine.scalpingRuntimeConfig = config
        // S5 — slippage bisa berubah lewat slider; jaga basis PnL simulasi tetap sinkron.
        simCoordinator.setSlippagePct(config.slippagePct)
        _scalpingRuntimeConfig.value = config
        _scalpingSensitivityPreset.value = preset
        agu.analys.util.AppLogManager.service(
            "ScalpingConfig",
            "⚙️ Scalping runtime config updated (Preset: $preset) | ${config.toSummaryString()}"
        )
    }

    /**
     * Simpan konfigurasi slider mode **INTRADAY** (`StrategyMode.OFFICE_DAILY`).
     *
     * Sengaja **tidak** menyalurkan `slippagePct` ke `simCoordinator` seperti jalur scalping:
     * slippage simulasi adalah basis P&L global satu aplikasi, bukan milik satu mode. Menyalurkannya
     * di sini akan mengubah angka P&L scalping hanya karena pengguna menggeser slider intraday.
     */
    fun updateIntradayRuntimeConfig(
        config: agu.analys.engine.intraday.IntradayConfig,
        preset: String = "CUSTOM"
    ) {
        prefs.intradayRuntimeConfig = config
        prefs.intradaySensitivityPreset = preset
        agu.analys.engine.intraday.IntradayRuntimeConfig.current = config
        _intradayRuntimeConfig.value = config
        _intradaySensitivityPreset.value = preset
        agu.analys.util.AppLogManager.service(
            "IntradayConfig",
            "⚙️ Intraday runtime config updated (Preset: $preset) | " +
                agu.analys.engine.intraday.IntradayRuntimeConfig.toSummaryString(config)
        )
    }

    fun applyIntradayPreset(preset: agu.analys.engine.intraday.IntradaySensitivityPreset) {
        val config = when (preset) {
            agu.analys.engine.intraday.IntradaySensitivityPreset.STANDARD ->
                agu.analys.engine.intraday.IntradayRuntimeConfig.DEFAULT
            agu.analys.engine.intraday.IntradaySensitivityPreset.STRICT ->
                agu.analys.engine.intraday.IntradayRuntimeConfig.STRICT
            agu.analys.engine.intraday.IntradaySensitivityPreset.LOOSE ->
                agu.analys.engine.intraday.IntradayRuntimeConfig.LOOSE
            agu.analys.engine.intraday.IntradaySensitivityPreset.CUSTOM ->
                prefs.intradayRuntimeConfig
        }
        updateIntradayRuntimeConfig(config, preset.name)
    }

    /**
     * Simpan konfigurasi slider mode **SWING** (`StrategyMode.SWING`).
     *
     * Sengaja **tidak** menyalurkan `slippagePct` ke `simCoordinator` — basis P&L simulasi tetap
     * satu nilai global; menggeser slider swing tidak boleh mengubah angka P&L mode lain.
     */
    fun updateSwingRuntimeConfig(
        config: agu.analys.engine.swing.SwingConfig,
        preset: String = "CUSTOM"
    ) {
        prefs.swingRuntimeConfig = config
        prefs.swingSensitivityPreset = preset
        agu.analys.engine.swing.SwingRuntimeConfig.current = config
        _swingRuntimeConfig.value = config
        _swingSensitivityPreset.value = preset
        agu.analys.util.AppLogManager.service(
            "SwingConfig",
            "⚙️ Swing runtime config updated (Preset: $preset) | " +
                agu.analys.engine.swing.SwingRuntimeConfig.toSummaryString(config)
        )
    }

    fun applyScalpingPreset(preset: agu.analys.engine.scalping.SensitivityPreset) {        val config = when (preset) {
            agu.analys.engine.scalping.SensitivityPreset.DEFAULT -> agu.analys.engine.scalping.ScalpingRuntimeConfig.DEFAULT
            agu.analys.engine.scalping.SensitivityPreset.STRICT -> agu.analys.engine.scalping.ScalpingRuntimeConfig.STRICT
            agu.analys.engine.scalping.SensitivityPreset.LOOSE -> agu.analys.engine.scalping.ScalpingRuntimeConfig.LOOSE
            agu.analys.engine.scalping.SensitivityPreset.CUSTOM -> prefs.scalpingRuntimeConfig
        }
        updateScalpingRuntimeConfig(config, preset.name)
    }

    /**
     * Simpan pengaturan auto-buy / auto-exit **paper trade** (P2).
     *
     * Murni simulasi — tidak ada efek ke mode uang riil.
     */
    fun updatePaperAutoTradeConfig(config: agu.analys.engine.scalping.PaperAutoTradeConfig) {
        val previous = _paperAutoTradeConfig.value
        prefs.paperAutoTradeConfig = config
        _paperAutoTradeConfig.value = config
        // P9 — mematikan auto-exit saat posisi terbuka tidak boleh meninggalkan posisi tanpa
        // stop: serahkan ke jalur auto-sell lama, sama seperti rehidrasi setelah restart.
        if (previous.autoExitEnabled && !config.autoExitEnabled) {
            runCatching { handBackAllPaperEnginesToLegacy() }.onFailure {
                agu.analys.util.AppLogManager.service(
                    "PaperAutoExit",
                    "⚠️ Serah-terima posisi paper ke jalur lama gagal: ${it.message}"
                )
            }
        }
        if (!config.autoBuyEnabled && !config.autoExitEnabled) {
            // Jaring aman saat kedua fitur dimatikan. Mematikan auto-buy saja TIDAK boleh
            // menghapus mesin: auto-exit masih mengawasi posisi yang sudah terbuka.
            agu.analys.viewmodel.PaperAutoTradeRuntime.clearAll()
        }
        agu.analys.util.AppLogManager.service(
            "PaperAuto",
            "⚙️ Auto-buy paper: ${if (config.autoBuyEnabled) "AKTIF" else "nonaktif"} | " +
                "Auto-exit: ${if (config.autoExitEnabled) "AKTIF" else "nonaktif"} | " +
                "Alokasi: ${config.allocation.label} | Stage min: ${config.minStage.name}"
        )
    }

    fun updateTradingFees(fees: TradingFeeConfig) {
        settingsViewModel.setTradingFees(fees)
        _tradingFees.value = fees
        engine.tradingFees = fees
    }

    fun setDarkTheme(enabled: Boolean) = settingsViewModel.setDarkTheme(enabled)
    fun setThemeStyle(style: ThemeStyle) = settingsViewModel.setThemeStyle(style)
    fun setAccentColorPreset(preset: AccentColorPreset) = settingsViewModel.setAccentColorPreset(preset)
    fun setCandleColorStyle(style: CandleColorStyle) = settingsViewModel.setCandleColorStyle(style)
    fun setAnimationSpeed(speed: AnimationSpeed) = settingsViewModel.setAnimationSpeed(speed)
    fun setPriceAnimationMode(mode: PriceAnimationMode) = settingsViewModel.setPriceAnimationMode(mode)
    fun setPriceTickPulseEnabled(enabled: Boolean) = settingsViewModel.setPriceTickPulseEnabled(enabled)
    fun setSmoothChartEnabled(enabled: Boolean) = settingsViewModel.setSmoothChartEnabled(enabled)
    fun setNotifyCandidateBuyEnabled(enabled: Boolean) = settingsViewModel.setNotifyCandidateBuyEnabled(enabled)
    fun setNotifyPriceAlertsEnabled(enabled: Boolean) = settingsViewModel.setNotifyPriceAlertsEnabled(enabled)
    fun setNotifyTrailingStopEnabled(enabled: Boolean) = settingsViewModel.setNotifyTrailingStopEnabled(enabled)
    fun setNotifyEmergencyExitEnabled(enabled: Boolean) = settingsViewModel.setNotifyEmergencyExitEnabled(enabled)

    fun runNewsAiScreener(forceRefresh: Boolean = false) {
        val ticks = dashboardTicks.value
        aiNewsViewModel.fetchAiNewsScreening(
            forceRefresh = forceRefresh,
            currentLiveTicks = ticks
        )
    }
    fun clearNewsScreenerState() = aiNewsViewModel.clearNewsScreenerState()
    fun setNotificationsEnabled(enabled: Boolean) = settingsViewModel.setNotificationsEnabled(enabled)
    fun setRealSimSyncEnabled(enabled: Boolean) = settingsViewModel.setRealSimSyncEnabled(enabled)

    // ── Konversi saldo simulasi (IDR <-> USDT) berbasis rate exchange real-time ──

    fun convertSimulationIdrToUsdt(amountIdr: Double): SimulationTradeStore.ConversionResult =
        orderViewModel.convertSimulationIdrToUsdt(amountIdr)

    fun convertSimulationUsdtToIdr(amountUsdt: Double): SimulationTradeStore.ConversionResult =
        orderViewModel.convertSimulationUsdtToIdr(amountUsdt)

    fun topUpSimulationUsdt(amount: Double) = orderViewModel.topUpSimulationUsdt(amount)

    /** Paksa refresh kurs USDT/IDR dari Tokocrypto (mengabaikan jeda anti-spam). */
    fun refreshUsdtIdrRate() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            agu.analys.util.ExchangeRateManager.refresh(force = true)
        }
    }

    /** Saldo real pada mata uang kuotasi pair tertentu (USDT -> saldo USDT, IDR -> saldo IDR). */
    fun realBalanceForQuote(quoteAsset: String): Double = orderViewModel.realBalanceForQuote(quoteAsset)

    /** Saldo real bebas (free) pada mata uang kuotasi pair tertentu. */
    fun realFreeBalanceForQuote(quoteAsset: String): Double = orderViewModel.realFreeBalanceForQuote(quoteAsset)

    fun getH1Candles(symbol: String): List<CandleBar> = marketViewModel.getH1Candles(symbol)

    fun ensureH1Candles(symbol: String) = marketViewModel.ensureH1Candles(symbol)

    fun selectPair(pair: TradingPair) {
        marketViewModel.selectPair(pair)
        agu.analys.util.AppLogManager.market("PairSelect", "Pasangan koin aktif: ${pair.symbol} (${pair.baseAsset}/${pair.quoteAsset})")
        lastSavedSignalTimestamp = 0L
        positionCoordinator.setSelectedSymbol(pair.symbol)
        engine.resetForOffline()
        MtfCacheManager.setActiveSymbol(pair.symbol, prefs.marketDataSource.name)
        val loaded = marketDataCoordinator.loadPairCache(pair.symbol, selectedTimeframe.value)
        if (!loaded) marketDataCoordinator.clearPairData(pair.symbol)
        marketDataCoordinator.startMarketPolling(pair, selectedTimeframe.value)
        agu.analys.engine.global.GlobalContextManager.subscribeCoin(pair.baseAsset)
    }

    fun toggleSimpleChart() = marketViewModel.toggleSimpleChart()

    fun selectTimeframe(tf: Timeframe) {
        if (selectedTimeframe.value == tf) return
        marketViewModel.selectTimeframe(tf)
        selectPair(selectedPair.value)
    }

    fun selectChartStyle(style: ChartStyle) = marketViewModel.selectChartStyle(style)

    fun toggleChartExpanded() = marketViewModel.toggleChartExpanded()

    fun setUiPriceThrottleMs(ms: Long) {
        marketViewModel.setUiPriceThrottleMs(ms)
        marketDataCoordinator.setPriceFeedThrottleMs(ms)
    }

    fun retryConnection() {
        marketDataCoordinator.startMarketPolling(selectedPair.value, selectedTimeframe.value)
        refreshWorthCoinsFromMarket()
        MtfCacheManager.retryAllTimeframes(selectedPair.value.symbol, prefs.marketDataSource.name)
    }

    fun refreshMtfForActiveSymbol() {
        MtfCacheManager.retryAllTimeframes(selectedPair.value.symbol, prefs.marketDataSource.name)
    }

    fun simulateDisconnect() = marketDataCoordinator.markOffline("Mode offline manual.")

    fun toggleWatchlist(symbol: String) = watchlistViewModel.toggleWatchlist(symbol)

    fun addToWatchlist(symbol: String) = watchlistViewModel.addToWatchlist(symbol)

    fun removeFromWatchlist(symbol: String) = watchlistViewModel.removeFromWatchlist(symbol)

    fun isWatched(symbol: String): Boolean = watchlistViewModel.isWatched(symbol)

    fun setCustomWatchlist(symbols: Collection<String>) = watchlistViewModel.setCustomWatchlist(symbols)

    fun applyWatchlistPreset(presetType: String, source: MarketDataSource = prefs.marketDataSource) = watchlistViewModel.applyWatchlistPreset(presetType, source)

    fun toggleFavorite(symbol: String) = watchlistViewModel.toggleFavorite(symbol)

    fun isFavorite(symbol: String): Boolean = watchlistViewModel.isFavorite(symbol)

    fun selectCustomSymbol(rawSymbol: String) {
        marketViewModel.selectCustomSymbol(rawSymbol)?.let { selectPair(it) }
    }

    fun selectAndWatch(rawSymbol: String, addToWatchlist: Boolean = true) {
        if (rawSymbol.isBlank()) return
        val pair = TradingPair.fromCustomSymbol(rawSymbol, "IDR")
        selectPair(pair)
        if (addToWatchlist && !watchlistViewModel.isWatched(pair.symbol)) {
            watchlistViewModel.addToWatchlist(pair.symbol)
        }
    }

    fun recalculateDashboardBadges() {
        marketViewModel.recalculateDashboardBadges(
            watchlistSymbols = watchlist.value,
            favoritesSymbols = favorites.value,
            activeStrategy = strategyMode.value
        )
    }

    fun refreshWorthCoinsFromMarket(resetPagination: Boolean = true) {
        if (resetPagination) {
            marketViewModel.resetDashboardPagination()
        }
        marketViewModel.refreshWorthCoinsFromMarket(
            watchlistSymbols = watchlist.value,
            favoritesSymbols = favorites.value,
            activeStrategy = strategyMode.value,
            resetPagination = resetPagination
        )
    }

    fun startDashboardPolling() {
        marketViewModel.startDashboardPolling(
            watchlistSymbols = watchlist.value,
            favoritesSymbols = favorites.value,
            activeStrategy = strategyMode.value
        )
    }

    fun stopDashboardPolling() {
        marketViewModel.stopDashboardPolling()
    }

    fun onAppResume() {
        marketDataCoordinator.startMarketPolling(selectedPair.value, selectedTimeframe.value)
        refreshWorthCoinsFromMarket()
        startDashboardPolling()
        MtfCacheManager.setActiveSymbol(selectedPair.value.symbol, prefs.marketDataSource.name)
        if (prefs.hasTokocryptoCredentials()) {
            syncRealBalancesToPositionStore()
        }
    }

    fun getPositionFor(symbol: String, isReal: Boolean = isRealBuyMode.value): SpotPosition =
        positionCoordinator.getPosition(symbol, isReal)

    fun isMatchingSymbol(s1: String, s2: String): Boolean = positionCoordinator.isSameSymbol(s1, s2)

    fun getEngineSignal(symbol: String): AISignalState? =
        if (isMatchingSymbol(symbol, selectedPair.value.symbol)) engine.signalState.value else null

    fun getSignalLogsForSymbol(symbol: String): kotlinx.coroutines.flow.Flow<List<SignalLogEntity>> =
        signalLogRepository.getLogsBySymbolFlow(symbol)

    fun seedSampleSignalLogs() = signalLogViewModel.seedSampleLogsIfEmpty()
    fun deleteSignalLog(id: Long) = signalLogViewModel.deleteLog(id)
    fun clearAllSignalLogs() = signalLogViewModel.clearAllLogs()
    fun resolveSignalLogManually(id: Long, isWin: Boolean, exitPrice: Double? = null, pnlPct: Double? = null, note: String = "") {
        signalLogViewModel.resolveLogManually(id, isWin, exitPrice, pnlPct, note)
    }
    fun refreshSignalLogs() = signalLogViewModel.refreshSignalLogs()

    fun seedSampleTradeJourneys(force: Boolean = true) = portfolioViewModel.seedSampleTradeJourneys(force)
    fun deleteTradeHistoryRecord(id: Long) = portfolioViewModel.deleteTradeHistoryRecord(id)
    fun clearAllTradeHistoryRecords() = portfolioViewModel.clearAllTradeHistoryRecords()

    override fun onCleared() {
        marketViewModel.stopDashboardPolling()
        marketDataCoordinator.stopPolling()
        agu.analys.bridge.AgentTradingCommandHandler.unregister()
        super.onCleared()
    }
}
