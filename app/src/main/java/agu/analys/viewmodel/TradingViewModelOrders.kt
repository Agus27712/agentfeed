package agu.analys.viewmodel

import androidx.lifecycle.viewModelScope
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.TradingPair
import agu.analys.service.TokocryptoMarketService
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import kotlinx.coroutines.launch

fun TradingViewModel.executeCancelRealOrder(symbol: String, orderId: String, onResult: (Boolean, String) -> Unit) =
    orderViewModel.executeCancelRealOrder(symbol, orderId, onResult)

fun TradingViewModel.checkPublicIp() = orderViewModel.checkPublicIp()
fun TradingViewModel.clearSecurityAlert() { /* handle locally if needed */ }
fun TradingViewModel.hasSecurityPin(): Boolean = orderViewModel.hasSecurityPin()
fun TradingViewModel.hasRealCredentialsConfigured(): Boolean = orderViewModel.hasRealCredentialsConfigured()
fun TradingViewModel.createSecurityPin(pin: String) = orderViewModel.createSecurityPin(pin)
fun TradingViewModel.saveRealCredentialsAndPin(pin: String, apiKey: String, secretKey: String) =
    orderViewModel.saveRealCredentialsAndPin(pin, apiKey, secretKey)
fun TradingViewModel.wipeSecurityCredentials() = orderViewModel.wipeSecurityCredentials()
fun TradingViewModel.verifyPin(pin: String): Boolean = orderViewModel.verifyPin(pin)
fun TradingViewModel.lockPin() = orderViewModel.lockPin()
fun TradingViewModel.setRealBuyMode(enabled: Boolean, pin: String? = null): Boolean =
    orderViewModel.setRealBuyMode(enabled, pin)
fun TradingViewModel.fetchRealBalance(force: Boolean = false) = orderViewModel.fetchRealBalance(force)
fun TradingViewModel.refreshRealBalance() {
    viewModelScope.launch {
        val allTicks = TokocryptoMarketService.fetchMarketRankings(limit = 100).allTicks
        if (allTicks.isNotEmpty()) {
            marketDataCoordinator.updateDashboardTicks(allTicks)
            marketViewModel.updateDashboardTicks(allTicks)
        }
    }
    fetchRealBalance()
}
fun TradingViewModel.executeRealTrade(pair: String, type: String, price: Double, amountIdr: Double, tp1: Double = 0.0, tp2: Double = 0.0, exchange: String? = null, onResult: (Boolean, String) -> Unit) =
    orderViewModel.executeRealTrade(pair, type, price, amountIdr, tp1, tp2, exchange, onResult)

fun TradingViewModel.refreshSimulationState() = orderViewModel.refreshSimulationState()
fun TradingViewModel.refreshSpotPosition() {
    val sym = try { _selectedPair.value.symbol } catch (_: Throwable) { null }
    if (sym != null) {
        positionCoordinator.refreshPosition(sym)
    }
}
fun TradingViewModel.refreshPriceAlerts() {
    val sym = try { _selectedPair.value.symbol } catch (_: Throwable) { null }
    if (sym != null) {
        positionCoordinator.refreshAlerts(sym)
    }
}
fun TradingViewModel.setOwnership(owned: Boolean, price: Double = 0.0, quantity: Double = 0.0, invested: Double = 0.0, isReal: Boolean = isRealBuyMode.value) {
    val symbol = _selectedPair.value.symbol
    positionCoordinator.setOwnership(symbol, owned, price, quantity, invested, isReal)
}

fun TradingViewModel.submitSimulationOrder(
    side: SimulationOrderSide,
    type: SimulationOrderType,
    price: Double,
    stopPrice: Double = 0.0,
    quantity: Double
): SimulationOrderResult {
    val pair = _selectedPair.value
    val curTick = marketDataCoordinator.currentTick.value
    val curIndicators = currentIndicators.value
    val curSignal = aiSignalState.value
    val curBids = marketDataCoordinator.orderBookBids.value
    val curAsks = marketDataCoordinator.orderBookAsks.value
    val mode = strategyMode.value.name
    val currentEx = prefs.marketDataSource.name
    val spotPos = positionStore.get(pair.symbol, isReal = false, exchange = currentEx)

    val snapshot = agu.analys.trading.TradeSignalSnapshot.capture(
        symbol = pair.symbol,
        strategyMode = mode,
        tick = curTick,
        indicators = curIndicators,
        signal = curSignal,
        bids = curBids,
        asks = curAsks,
        exchange = currentEx
    )

    val isHolding = spotPos.isHolding && !spotPos.isReal
    val holdDuration = if (side == SimulationOrderSide.SELL && isHolding && spotPos.openedAt > 0L) {
        (System.currentTimeMillis() - spotPos.openedAt).coerceAtLeast(0L)
    } else null
    val entryPrice = if (side == SimulationOrderSide.SELL && isHolding) spotPos.entryPrice else if (side == SimulationOrderSide.BUY) price else null
    val entryTimestamp = if (side == SimulationOrderSide.SELL && isHolding) spotPos.openedAt else if (side == SimulationOrderSide.BUY) System.currentTimeMillis() else null

    return simCoordinator.submitOrder(
        pair = pair,
        currentPrice = curTick?.price ?: price,
        side = side,
        type = type,
        price = price,
        stopPrice = stopPrice,
        quantity = quantity,
        strategyMode = mode,
        holdingDurationMs = holdDuration,
        entryPrice = entryPrice,
        entryTimestamp = entryTimestamp,
        isTrailingUsed = spotPos.isTrailingEnabled,
        trailingPercent = spotPos.trailingPercent,
        trailingPeakPrice = spotPos.peakPrice,
        trailingLockPrice = spotPos.trailingStopPrice,
        signalSnapshot = snapshot
    )
}

fun TradingViewModel.cancelSimulationOrder(orderId: String): Boolean = orderViewModel.cancelSimulationOrder(orderId)
fun TradingViewModel.cancelAllSimulationOrders(symbol: String? = null): Int = orderViewModel.cancelAllSimulationOrders(symbol)
fun TradingViewModel.topUpSimulationBalance(amount: Double) = orderViewModel.topUpSimulationBalance(amount)
fun TradingViewModel.setSimulationBalance(amount: Double) = orderViewModel.setSimulationBalance(amount)
fun TradingViewModel.resetSimulationAccount() = orderViewModel.resetSimulationAccount()

fun TradingViewModel.getHoldingStatus(pair: TradingPair, forceIsReal: Boolean? = null): CoinHoldingStatus {
    val targetIsReal = forceIsReal ?: isRealBuyMode.value
    val baseLower = pair.baseAsset.lowercase()
    val baseUpper = pair.baseAsset.uppercase()
    val symbolNorm = pair.symbol.replace("_", "").uppercase()

    val currentEx = prefs.marketDataSource.name
    val hasCreds = prefs.hasTokocryptoCredentials()

    if (targetIsReal) {
        // STRICTLY REAL MODE: Hanya evaluasi posisi Real / saldo akun Real bursa aktif
        val spotPos = positionStore.get(pair.symbol, isReal = true, exchange = currentEx)
        val realBalances = realSpotBalance.value
        val realQty = realBalances[baseLower] ?: realBalances[baseUpper] ?: 0.0
        val realAvg = realAvgBuyPrices.value[symbolNorm]
            ?: realAvgBuyPrices.value[pair.symbol.uppercase()]
            ?: realAvgBuyPrices.value[baseUpper]
            ?: realAvgBuyPrices.value[baseLower]
            ?: if (spotPos.isReal && spotPos.entryPrice > 0.0) spotPos.entryPrice else 0.0

        // Jika saldo koin sudah 0 / debu (koin sudah dijual), pastikan status holding CLEAR
        if ((realBalances.isNotEmpty() || hasCreds) && realQty <= 0.00000001) {
            if (spotPos.isHolding) {
                positionStore.markSold(pair.symbol, isReal = true, exchange = currentEx)
                agu.analys.engine.sell.SellSignalLifecycleManager.reset(pair.symbol, isReal = true)
            }
            return CoinHoldingStatus(isHolding = false, isReal = true)
        }

        if (spotPos.isHolding && spotPos.isReal && spotPos.quantity > 0.00000001) {
            val entry = if (realAvg > 0.0) realAvg else spotPos.entryPrice
            val sl = if (spotPos.stopLossPrice > 0.0) spotPos.stopLossPrice else if (entry > 0.0) entry * 0.99 else 0.0
            return CoinHoldingStatus(
                isHolding = true,
                quantity = if (realQty > 0.0) realQty else spotPos.quantity,
                entryPrice = entry,
                isReal = true,
                tp1Price = spotPos.tp1Price,
                tp2Price = spotPos.tp2Price,
                stopLossPrice = sl,
                isTrailingTriggered = spotPos.isTrailingTriggered
            )
        }

        if (realQty > 0.00000001 && baseUpper != "IDR" && hasCreds) {
            val sl = if (spotPos.isReal && spotPos.stopLossPrice > 0.0) spotPos.stopLossPrice else if (realAvg > 0.0) realAvg * 0.99 else 0.0
            return CoinHoldingStatus(
                isHolding = true,
                quantity = realQty,
                entryPrice = realAvg,
                isReal = true,
                tp1Price = if (spotPos.isReal) spotPos.tp1Price else 0.0,
                tp2Price = if (spotPos.isReal) spotPos.tp2Price else 0.0,
                stopLossPrice = sl,
                isTrailingTriggered = spotPos.isReal && spotPos.isTrailingTriggered
            )
        }

        return CoinHoldingStatus(isHolding = false, isReal = true)
    } else {
        // STRICTLY SIMULATION MODE: Hanya evaluasi posisi Simulasi / saldo akun Simulasi
        val spotPos = positionStore.get(pair.symbol, isReal = false, exchange = currentEx)
        val simWallet = simulationWallet.value
        val simQty = (simWallet.coinBalances[baseLower] ?: simWallet.coinBalances[baseUpper] ?: 0.0) +
                     (simWallet.lockedCoinBalances[baseLower] ?: simWallet.lockedCoinBalances[baseUpper] ?: 0.0)

        val coinQuote = simWallet.quoteForCoin(baseUpper).ifBlank { simWallet.quoteForCoin(baseLower) }.ifBlank { "IDR" }
        val isQuoteMatched = pair.quoteAsset.equals(coinQuote, ignoreCase = true)

        if (simQty <= 0.00000001) {
            if (spotPos.isHolding) {
                positionStore.markSold(pair.symbol, isReal = false, exchange = currentEx)
                agu.analys.engine.sell.SellSignalLifecycleManager.reset(pair.symbol, isReal = false)
            }
            return CoinHoldingStatus(isHolding = false, isReal = false)
        }

        if (spotPos.isHolding && !spotPos.isReal && spotPos.quantity > 0.00000001) {
            val sl = if (spotPos.stopLossPrice > 0.0) spotPos.stopLossPrice else if (spotPos.entryPrice > 0.0) spotPos.entryPrice * 0.99 else 0.0
            return CoinHoldingStatus(
                isHolding = true,
                quantity = if (simQty > 0.0) simQty else spotPos.quantity,
                entryPrice = spotPos.entryPrice,
                isReal = false,
                tp1Price = spotPos.tp1Price,
                tp2Price = spotPos.tp2Price,
                stopLossPrice = sl,
                isTrailingTriggered = spotPos.isTrailingTriggered
            )
        }

        if (simQty > 0.00000001 && isQuoteMatched && baseUpper != "IDR" && baseUpper != "USDT") {
            val simAvg = simWallet.avgBuyPrices[baseLower] ?: simWallet.avgBuyPrices[baseUpper] ?: 0.0
            val sl = if (!spotPos.isReal && spotPos.stopLossPrice > 0.0) spotPos.stopLossPrice else if (simAvg > 0.0) simAvg * 0.99 else 0.0
            return CoinHoldingStatus(
                isHolding = true,
                quantity = simQty,
                entryPrice = simAvg,
                isReal = false,
                tp1Price = if (!spotPos.isReal) spotPos.tp1Price else 0.0,
                tp2Price = if (!spotPos.isReal) spotPos.tp2Price else 0.0,
                stopLossPrice = sl,
                isTrailingTriggered = !spotPos.isReal && spotPos.isTrailingTriggered
            )
        }

        return CoinHoldingStatus(isHolding = false, isReal = false)
    }
}

fun TradingViewModel.updateRealAvgBuyPrice(coin: String, newAvgPrice: Double, totalInvested: Double? = null) {
    realCoordinator.updateAvgBuyPrice(coin, newAvgPrice)
    val base = baseFromSymbolOrPair(coin)
    val symbol = "${base.uppercase()}IDR"
    val balances = realCoordinator.realSpotBalance.value
    val qty = balances[base.lowercase()] ?: balances[base.uppercase()] ?: 0.0
    val cost = totalInvested ?: if (qty > 0.0) newAvgPrice * qty else newAvgPrice
    val finalQty = if (qty > 0.0) qty else 1.0

    positionStore.setHolding(
        symbol = symbol,
        invested = cost,
        entry = newAvgPrice,
        quantity = finalQty,
        isReal = true
    )
    positionCoordinator.refreshPosition(symbol)
    recalculateDashboardBadges()
    updateForegroundServiceState()
}

fun TradingViewModel.setTrailingStop(
    enabled: Boolean,
    trailingPercent: Double,
    isTieredEnabled: Boolean = true,
    customTiersJson: String? = null
) {
    setTrailingStop(
        pairSymbol = _selectedPair.value.symbol,
        enabled = enabled,
        trailingPercent = trailingPercent,
        isTieredEnabled = isTieredEnabled,
        customTiersJson = customTiersJson
    )
}

fun TradingViewModel.setTrailingStop(
    pairSymbol: String,
    enabled: Boolean,
    trailingPercent: Double,
    isTieredEnabled: Boolean = true,
    customTiersJson: String? = null
) {
    val symbol = pairSymbol
    val isReal = isRealBuyMode.value
    val tick = if (currentTick.value?.symbol?.equals(symbol, ignoreCase = true) == true) {
        currentTick.value
    } else {
        marketDataCoordinator.dashboardTicks.value[symbol] ?: marketDataCoordinator.dashboardTicks.value[TradingPair.fromCustomSymbol(symbol).symbol]
    }
    var pos = positionStore.get(symbol, isReal)
    val currentP = tick?.price?.takeIf { it > 0.0 } ?: (if (pos.peakPrice > 0.0) pos.peakPrice else pos.entryPrice)
    val pair = TradingPair.fromCustomSymbol(symbol)
    val baseKey = pair.baseAsset.uppercase()
    val baseLower = baseKey.lowercase()

    if (enabled) {
        if (isReal) {
            val realQty = realCoordinator.realFreeBalance.value[baseLower]
                ?: realCoordinator.realFreeBalance.value[baseKey]
                ?: realCoordinator.realSpotBalance.value[baseLower]
                ?: realCoordinator.realSpotBalance.value[baseKey]
                ?: 0.0
            if (realQty > 0.0 && (!pos.isHolding || pos.quantity <= 0.0)) {
                val entryP = if (pos.entryPrice > 0.0) pos.entryPrice
                    else (realCoordinator.realAvgBuyPrices.value[symbol]
                        ?: realCoordinator.realAvgBuyPrices.value[baseLower]
                        ?: realCoordinator.realAvgBuyPrices.value[baseKey]
                        ?: currentP)
                positionStore.setHolding(symbol, invested = realQty * entryP, entry = entryP, quantity = realQty, isReal = true)
                pos = positionStore.get(symbol, isReal = true)
            }
        } else {
            val simCoin = simCoordinator.wallet.value.getTotalCoin(baseKey)
            if (simCoin > 0.0 && (!pos.isHolding || pos.quantity <= 0.0)) {
                val entryP = if (pos.entryPrice > 0.0) pos.entryPrice else currentP
                positionStore.setHolding(symbol, invested = simCoin * entryP, entry = entryP, quantity = simCoin, isReal = false)
                pos = positionStore.get(symbol, isReal = false)
            }
        }
    }

    positionCoordinator.setTrailing(
        symbol = symbol,
        enabled = enabled,
        pct = trailingPercent,
        refPrice = currentP,
        isTieredEnabled = isTieredEnabled,
        customTiersJson = customTiersJson,
        isReal = isReal
    )
    if (enabled) {
        startTrailingPolling()
    } else {
        checkAndStopTrailingServiceIfEmpty()
    }
    updateForegroundServiceState()
}

fun TradingViewModel.setAutoSellParams(
    enabled: Boolean,
    tp1Price: Double,
    tp1Percent: Double,
    tp2Price: Double,
    tp2Percent: Double,
    onResult: (Boolean, String) -> Unit = { _, _ -> }
) {
    setAutoSellParams(
        pairSymbol = _selectedPair.value.symbol,
        enabled = enabled,
        tp1Price = tp1Price,
        tp1Percent = tp1Percent,
        tp2Price = tp2Price,
        tp2Percent = tp2Percent,
        onResult = onResult
    )
}

fun TradingViewModel.setAutoSellParams(
    pairSymbol: String,
    enabled: Boolean,
    tp1Price: Double,
    tp1Percent: Double,
    tp2Price: Double,
    tp2Percent: Double,
    onResult: (Boolean, String) -> Unit = { _, _ -> }
) {
    val symbol = pairSymbol
    positionCoordinator.setAutoSell(symbol, enabled, tp1Price, tp1Percent, tp2Price, tp2Percent, isReal = isRealBuyMode.value)
    onResult(true, if (enabled) "Target TP1 & TP2 tersimpan ke evaluator sinyal." else "Target TP dinonaktifkan.")
}

fun TradingViewModel.executeSellOrders(
    pair: agu.analys.model.TradingPair,
    sellQty: Double,
    marketPrice: Double,
    isAutoTpEnabled: Boolean,
    tp1Price: Double,
    tp1Percent: Double,
    tp2Price: Double,
    tp2Percent: Double,
    isRealMode: Boolean,
    onResult: (Boolean, String) -> Unit
) {
    if (isRealMode) {
        realCoordinator.executeRealSellOrders(
            pair = pair.symbol,
            totalQuantity = sellQty,
            marketPrice = marketPrice,
            isAutoTpEnabled = isAutoTpEnabled,
            tp1Price = tp1Price,
            tp1Percent = tp1Percent,
            tp2Price = tp2Price,
            tp2Percent = tp2Percent,
            onResult = { success, msg ->
                if (success) {
                    val currentEx = prefs.marketDataSource.name
                    positionStore.markSold(pair.symbol, isReal = true, exchange = currentEx)
                    agu.analys.engine.sell.SellSignalLifecycleManager.reset(pair.symbol, isReal = true)
                    positionCoordinator.setOwnership(pair.symbol, false, isReal = true)
                    positionCoordinator.refreshPosition(pair.symbol)
                    refreshSpotPosition()
                }
                onResult(success, msg)
            }
        )
    } else {
        val curTick = marketDataCoordinator.currentTick.value
        val curIndicators = currentIndicators.value
        val curSignal = aiSignalState.value
        val curBids = marketDataCoordinator.orderBookBids.value
        val curAsks = marketDataCoordinator.orderBookAsks.value
        val mode = strategyMode.value.name
        val spotPos = positionStore.get(pair.symbol, isReal = false, exchange = prefs.marketDataSource.name)

        val snapshot = agu.analys.trading.TradeSignalSnapshot.capture(
            symbol = pair.symbol,
            strategyMode = mode,
            tick = curTick,
            indicators = curIndicators,
            signal = curSignal,
            bids = curBids,
            asks = curAsks,
            exchange = prefs.marketDataSource.name
        )

        val isHolding = spotPos.isHolding && !spotPos.isReal
        val holdDuration = if (isHolding && spotPos.openedAt > 0L) {
            (System.currentTimeMillis() - spotPos.openedAt).coerceAtLeast(0L)
        } else null
        val entryPrice = if (isHolding) spotPos.entryPrice else null
        val entryTimestamp = if (isHolding) spotPos.openedAt else null

        simCoordinator.executeSimulationSellOrders(
            pair = pair,
            totalQuantity = sellQty,
            marketPrice = marketPrice,
            isAutoTpEnabled = isAutoTpEnabled,
            tp1Price = tp1Price,
            tp1Percent = tp1Percent,
            tp2Price = tp2Price,
            tp2Percent = tp2Percent,
            strategyMode = mode,
            holdingDurationMs = holdDuration,
            entryPrice = entryPrice,
            entryTimestamp = entryTimestamp,
            isTrailingUsed = spotPos.isTrailingEnabled,
            trailingPercent = spotPos.trailingPercent,
            trailingPeakPrice = spotPos.peakPrice,
            trailingLockPrice = spotPos.trailingStopPrice,
            signalSnapshot = snapshot,
            onResult = { success, msg ->
                if (success) {
                    positionCoordinator.setOwnership(pair.symbol, false)
                }
                onResult(success, msg)
            }
        )
    }
}

fun TradingViewModel.resetTrailingTrigger() {
    positionCoordinator.resetTrailing(_selectedPair.value.symbol)
}

fun TradingViewModel.addPriceAlert(alert: agu.analys.model.PriceAlert) { positionCoordinator.addAlert(alert, _selectedPair.value.symbol) }
fun TradingViewModel.removePriceAlert(alertId: String) { positionCoordinator.removeAlert(alertId, _selectedPair.value.symbol) }
fun TradingViewModel.togglePriceAlert(alertId: String) { positionCoordinator.toggleAlert(alertId, _selectedPair.value.symbol) }
