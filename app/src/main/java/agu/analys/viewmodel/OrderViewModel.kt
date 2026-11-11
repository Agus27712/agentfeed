package agu.analys.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import agu.analys.model.TradingPair
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.trading.SimulationTradeStore
import agu.analys.trading.SimulationWallet
import agu.analys.trading.TradeSignalSnapshot
import agu.analys.database.AppDatabase
import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.util.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class OrderViewModel(
    application: Application,
    customSimCoordinator: SimulationCoordinator? = null,
    customRealCoordinator: RealTradeCoordinator? = null
) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    val simCoordinator: SimulationCoordinator = customSimCoordinator ?: SimulationCoordinator(SimulationTradeStore(application))
    val realCoordinator: RealTradeCoordinator = customRealCoordinator ?: RealTradeCoordinator(
        scope = viewModelScope,
        prefs = prefs,
        getLatestTick = { null }
    )

    val isRealBuyMode: StateFlow<Boolean> = realCoordinator.isRealBuyEnabled
    val isPinUnlocked: StateFlow<Boolean> = realCoordinator.isPinUnlocked
    val realSpotBalance: StateFlow<Map<String, Double>> = realCoordinator.realSpotBalance
    val realFreeBalance: StateFlow<Map<String, Double>> = realCoordinator.realFreeBalance
    val realLockedBalance: StateFlow<Map<String, Double>> = realCoordinator.realLockedBalance
    val realAvgBuyPrices: StateFlow<Map<String, Double>> = realCoordinator.realAvgBuyPrices
    val isFetchingRealBalance: StateFlow<Boolean> = realCoordinator.isFetchingRealBalance
    val realTradeStatus: StateFlow<String> = realCoordinator.realTradeStatus
    val userPublicIp: StateFlow<String?> = realCoordinator.publicIp
    val failedPinAttempts: StateFlow<Int> = MutableStateFlow(prefs.failedPinAttempts).asStateFlow()

    val realOpenOrders: StateFlow<List<RealOpenOrderEntity>> = AppDatabase.getInstance().realTradeDao().getOpenOrdersFlow()
        .map { list -> list.filter { it.exchange.equals(prefs.marketDataSource.name, ignoreCase = true) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val realTrades: StateFlow<List<RealTradeEntity>> = AppDatabase.getInstance().realTradeDao().getAllTradesFlow()
        .map { list -> list.filter { it.exchange.equals(prefs.marketDataSource.name, ignoreCase = true) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val simulationWallet: StateFlow<SimulationWallet> = simCoordinator.wallet
    val simulationOpenOrders: StateFlow<List<SimulationOrder>> = simCoordinator.openOrders
    val simulationHistory: StateFlow<List<SimulationTradeHistoryItem>> = simCoordinator.history
    val lastFilledSimulationOrder: StateFlow<SimulationOrder?> = simCoordinator.lastFilledOrder

    // --- Simulation Operations ---

    fun refreshSimulationState() = simCoordinator.refresh()

    fun submitSimulationOrder(
        pair: TradingPair,
        currentPrice: Double,
        side: SimulationOrderSide,
        type: SimulationOrderType,
        price: Double,
        stopPrice: Double = 0.0,
        quantity: Double,
        strategyMode: String = "SCALPING",
        holdingDurationMs: Long? = null,
        entryPrice: Double? = null,
        entryTimestamp: Long? = null,
        isTrailingUsed: Boolean = false,
        trailingPercent: Double? = null,
        trailingPeakPrice: Double? = null,
        trailingLockPrice: Double? = null,
        signalSnapshot: TradeSignalSnapshot? = null
    ): SimulationOrderResult {
        return simCoordinator.submitOrder(
            pair = pair,
            currentPrice = currentPrice,
            side = side,
            type = type,
            price = price,
            stopPrice = stopPrice,
            quantity = quantity,
            strategyMode = strategyMode,
            holdingDurationMs = holdingDurationMs,
            entryPrice = entryPrice,
            entryTimestamp = entryTimestamp,
            isTrailingUsed = isTrailingUsed,
            trailingPercent = trailingPercent,
            trailingPeakPrice = trailingPeakPrice,
            trailingLockPrice = trailingLockPrice,
            signalSnapshot = signalSnapshot
        )
    }

    fun cancelSimulationOrder(orderId: String): Boolean = simCoordinator.cancelOrder(orderId)

    fun cancelAllSimulationOrders(symbol: String? = null): Int = simCoordinator.cancelAllOrders(symbol)

    fun topUpSimulationBalance(amount: Double) = simCoordinator.topUpIdr(amount)

    fun topUpSimulationUsdt(amount: Double) = simCoordinator.topUpUsdt(amount)

    /** Konversi saldo Rupiah -> USDT (rate live exchange). */
    fun convertSimulationIdrToUsdt(amountIdr: Double): SimulationTradeStore.ConversionResult =
        simCoordinator.convertIdrToUsdt(amountIdr)

    /** Konversi saldo USDT -> Rupiah (rate live exchange). */
    fun convertSimulationUsdtToIdr(amountUsdt: Double): SimulationTradeStore.ConversionResult =
        simCoordinator.convertUsdtToIdr(amountUsdt)

    fun setSimulationBalance(amount: Double) = simCoordinator.setBalance(amount)

    fun resetSimulationAccount() = simCoordinator.resetAccount()

    fun resetAccount() {
        resetSimulationAccount()
    }

    // --- Real Trading Operations ---

    fun hasSecurityPin(): Boolean = prefs.hasSecurityPin()

    fun hasRealCredentialsConfigured(): Boolean = prefs.hasTokocryptoCredentials()

    fun createSecurityPin(pin: String) {
        prefs.setSecurityPin(pin)
    }

    fun saveRealCredentialsAndPin(pin: String, apiKey: String, secretKey: String) {
        prefs.setSecurityPin(pin)
        prefs.tokocryptoApiKey = apiKey
        prefs.tokocryptoSecretKey = secretKey
        prefs.isRealBuyMode = true
        realCoordinator.verifyPin(pin)
        realCoordinator.fetchRealBalance()
    }

    fun wipeSecurityCredentials() {
        prefs.wipeAllRealSecurityData()
        realCoordinator.lockPin()
        realCoordinator.fetchRealBalance()
    }

    fun verifyPin(pin: String): Boolean = realCoordinator.verifyPin(pin)

    fun lockPin() = realCoordinator.lockPin()

    fun setRealBuyMode(enabled: Boolean, pin: String? = null): Boolean = realCoordinator.setRealBuyMode(enabled, pin)

    fun fetchRealBalance(force: Boolean = false) = realCoordinator.fetchRealBalance(force)

    /**
     * Saldo real untuk mata uang kuotasi tertentu.
     * Pair USDT -> saldo USDT, pair IDR -> saldo IDR (sesuai ketersediaan di exchange).
     */
    fun realBalanceForQuote(quoteAsset: String): Double = realCoordinator.realBalanceForQuote(quoteAsset)

    /** Saldo real bebas (free) untuk mata uang kuotasi tertentu. */
    fun realFreeBalanceForQuote(quoteAsset: String): Double = realCoordinator.realFreeBalanceForQuote(quoteAsset)

    /** Saldo real Rupiah (IDR). */
    fun realIdrBalance(): Double = realCoordinator.realBalanceForQuote("IDR")

    /** Saldo real USDT. */
    fun realUsdtBalance(): Double = realCoordinator.realBalanceForQuote("USDT")

    fun checkPublicIp() = realCoordinator.checkPublicIp()

    fun executeRealTrade(
        pair: String,
        type: String,
        price: Double,
        amountIdr: Double,
        tp1: Double = 0.0,
        tp2: Double = 0.0,
        exchange: String? = null,
        onResult: (Boolean, String) -> Unit
    ) {
        realCoordinator.executeRealTrade(pair, type, price, amountIdr, tp1, tp2, exchange, onResult)
    }

    fun executeCancelRealOrder(
        symbol: String,
        orderId: String,
        onResult: (Boolean, String) -> Unit
    ) {
        realCoordinator.executeCancelRealOrder(symbol, orderId, onResult)
    }

    fun updateRealAvgBuyPrice(coin: String, newAvgPrice: Double) {
        realCoordinator.updateAvgBuyPrice(coin, newAvgPrice)
    }
}