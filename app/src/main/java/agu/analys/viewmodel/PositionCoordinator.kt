package agu.analys.viewmodel

import agu.analys.model.PriceAlert
import agu.analys.trading.PriceAlertStore
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PositionCoordinator(
    private val positionStore: SpotPositionStore,
    private val alertStore: PriceAlertStore,
    private val isRealProvider: () -> Boolean = { false },
    private val exchangeProvider: () -> String = { "TOKOCRYPTO" },
    private val onPositionChanged: () -> Unit = {}
) {
    private val _positionVersion = MutableStateFlow(0L)
    val positionVersion: StateFlow<Long> = _positionVersion.asStateFlow()

    private val _spotPosition = MutableStateFlow(SpotPosition())
    val spotPosition: StateFlow<SpotPosition> = _spotPosition.asStateFlow()

    private val _priceAlerts = MutableStateFlow<List<PriceAlert>>(emptyList())
    val priceAlerts: StateFlow<List<PriceAlert>> = _priceAlerts.asStateFlow()

    private var currentSelectedSymbol: String = ""

    fun isSameSymbol(s1: String, s2: String): Boolean {
        if (s1.isBlank() || s2.isBlank()) return false
        val n1 = positionStore.normalize(s1)
        val n2 = positionStore.normalize(s2)
        return n1 == n2
    }

    fun setSelectedSymbol(symbol: String) {
        currentSelectedSymbol = symbol
        val isReal = isRealProvider()
        val currentEx = exchangeProvider()
        _spotPosition.value = positionStore.get(symbol, isReal, exchange = currentEx)
        _priceAlerts.value = alertStore.getAlertsForSymbol(symbol)
        notifyPositionChange()
    }

    fun getPosition(symbol: String, isReal: Boolean = isRealProvider()): SpotPosition {
        return positionStore.get(symbol, isReal, exchange = exchangeProvider())
    }

    private fun notifyPositionChange() {
        _positionVersion.value = System.currentTimeMillis()
        onPositionChanged()
    }

    fun refreshPosition(symbol: String = currentSelectedSymbol) {
        val target = if (symbol.isNotBlank()) symbol else currentSelectedSymbol
        val isReal = isRealProvider()
        val currentEx = exchangeProvider()
        if (target.isNotBlank()) {
            if (currentSelectedSymbol.isBlank() || isSameSymbol(target, currentSelectedSymbol)) {
                _spotPosition.value = positionStore.get(target, isReal, exchange = currentEx)
            }
        }
        notifyPositionChange()
    }

    fun refreshAlerts(symbol: String = currentSelectedSymbol) {
        val target = if (symbol.isNotBlank()) symbol else currentSelectedSymbol
        if (target.isNotBlank()) {
            if (currentSelectedSymbol.isBlank() || isSameSymbol(target, currentSelectedSymbol)) {
                _priceAlerts.value = alertStore.getAlertsForSymbol(target)
            }
        }
    }

    fun setOwnership(symbol: String, owned: Boolean, entryPrice: Double = 0.0, quantity: Double = 0.0, invested: Double = 0.0, isReal: Boolean = isRealProvider()) {
        val currentEx = exchangeProvider()
        if (owned) {
            positionStore.markBought(symbol, entryPrice, invested, quantity, isReal, exchange = currentEx)
        } else {
            markSoldAndClear(symbol, isReal)
            return
        }
        refreshPosition(symbol)
    }

    /**
     * Single source of truth untuk menutup posisi:
     * - hapus SpotPosition
     * - reset lifecycle sell signal (agar hilang dari Ready-to-Sell / Trailing badge)
     * - bump positionVersion agar holdingStatuses & UI recompose
     */
    fun markSoldAndClear(symbol: String, isReal: Boolean = isRealProvider()) {
        val currentEx = exchangeProvider()
        positionStore.markSold(symbol, isReal, exchange = currentEx)
        agu.analys.engine.sell.SellSignalLifecycleManager.reset(symbol, isReal, exchange = currentEx)
        refreshPosition(symbol)
    }

    fun setManualEntry(symbol: String, price: Double, amount: Double, isReal: Boolean = isRealProvider()) {
        val currentEx = exchangeProvider()
        positionStore.setManualEntryPrice(symbol, price, amount, isReal, exchange = currentEx)
        refreshPosition(symbol)
    }

    fun setTrailing(
        symbol: String,
        enabled: Boolean,
        pct: Double,
        refPrice: Double,
        isTieredEnabled: Boolean = true,
        customTiersJson: String? = null,
        isReal: Boolean = isRealProvider()
    ) {
        val currentEx = exchangeProvider()
        positionStore.setTrailingStop(symbol, enabled, pct, refPrice, isTieredEnabled, customTiersJson, isReal, exchange = currentEx)
        refreshPosition(symbol)
    }

    fun setTrailingOrderIdAndUpdateTime(symbol: String, orderId: String?, updateTime: Long, isReal: Boolean = isRealProvider()) {
        val currentEx = exchangeProvider()
        positionStore.setTrailingOrderIdAndUpdateTime(symbol, orderId, updateTime, isReal, exchange = currentEx)
        refreshPosition(symbol)
    }

    fun setAutoSell(symbol: String, enabled: Boolean, tp1: Double, tp1P: Double, tp2: Double, tp2P: Double, isReal: Boolean = isRealProvider()) {
        val currentEx = exchangeProvider()
        positionStore.setAutoSellParams(symbol, enabled, tp1, tp1P, tp2, tp2P, isReal, exchange = currentEx)
        refreshPosition(symbol)
    }

    fun resetTrailing(symbol: String, isReal: Boolean = isRealProvider()) {
        val currentEx = exchangeProvider()
        positionStore.resetTrailingTrigger(symbol, isReal, exchange = currentEx)
        refreshPosition(symbol)
    }

    fun addAlert(alert: PriceAlert, symbol: String) {
        val currentEx = exchangeProvider()
        alertStore.addAlert(if (alert.exchange.isBlank()) alert.copy(exchange = currentEx) else alert)
        refreshAlerts(symbol)
    }

    fun removeAlert(id: String, symbol: String) {
        alertStore.removeAlert(id)
        refreshAlerts(symbol)
    }

    fun toggleAlert(id: String, symbol: String) {
        alertStore.toggleAlert(id)
        refreshAlerts(symbol)
    }

    fun checkAlertsAndTrailing(symbol: String, price: Double, rsi: Double?) {
        val currentEx = exchangeProvider()
        val triggered = alertStore.checkAlerts(symbol, price, exchange = currentEx)
        if (triggered.isNotEmpty()) refreshAlerts(symbol)
        
        val isReal = isRealProvider()
        val pos = positionStore.get(symbol, isReal, exchange = currentEx)
        if (pos.isTrailingEnabled) {
            positionStore.updateTrailingPrice(symbol, price, isReal, exchange = currentEx)
            refreshPosition(symbol)
        }
    }
}
