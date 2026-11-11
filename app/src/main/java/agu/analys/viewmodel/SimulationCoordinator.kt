package agu.analys.viewmodel

import agu.analys.model.TradingPair
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.trading.SimulationTradeStore
import agu.analys.trading.SimulationWallet
import agu.analys.trading.TradeSignalSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SimulationCoordinator(
    private val store: SimulationTradeStore,
    private val onOrderFilled: ((SimulationOrder) -> Unit)? = null,
    private val exchangeProvider: () -> String = { "TOKOCRYPTO" }
) {
    private val currentEx get() = exchangeProvider()

    private val _wallet = MutableStateFlow(store.getWallet(exchangeProvider()))
    val wallet: StateFlow<SimulationWallet> = _wallet.asStateFlow()

    private val _openOrders = MutableStateFlow(store.getOpenOrders(exchangeProvider()))
    val openOrders: StateFlow<List<SimulationOrder>> = _openOrders.asStateFlow()

    private val _history = MutableStateFlow(store.getTradeHistory(exchangeProvider()))
    val history: StateFlow<List<SimulationTradeHistoryItem>> = _history.asStateFlow()

    private val _lastFilledOrder = MutableStateFlow<SimulationOrder?>(null)
    val lastFilledOrder: StateFlow<SimulationOrder?> = _lastFilledOrder.asStateFlow()

    fun refresh() {
        val ex = currentEx
        _wallet.value = store.getWallet(ex)
        _openOrders.value = store.getOpenOrders(ex)
        _history.value = store.getTradeHistory(ex)
    }

    /**
     * S5 — tetapkan asumsi slippage per sisi (%) untuk order MARKET simulasi.
     *
     * Dipakai supaya PnL yang dilaporkan di log audit memakai basis yang sama dengan mesin exit
     * (fee + 2× slippage), bukan fee-only. Lihat [SimulationTradeStore.slippagePct].
     */
    fun setSlippagePct(pct: Double) {
        store.slippagePct = if (pct.isFinite() && pct > 0.0) pct else 0.0
    }

    fun recordMirroredRealTrade(
        symbol: String,
        baseAsset: String,
        quoteAsset: String = "IDR",
        side: SimulationOrderSide,
        price: Double,
        quantity: Double,
        pnlIdr: Double? = null,
        pnlPercent: Double? = null
    ) {
        store.recordMirroredRealTrade(
            symbol = symbol,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            side = side,
            price = price,
            quantity = quantity,
            pnlIdr = pnlIdr,
            pnlPercent = pnlPercent,
            exchange = currentEx
        )
        refresh()
    }

    fun submitOrder(
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
        val execPrice = if (currentPrice > 0.0) currentPrice else price
        val result = store.placeOrder(
            symbol = pair.symbol,
            baseAsset = pair.baseAsset,
            quoteAsset = pair.quoteAsset,
            side = side,
            type = type,
            price = price,
            stopPrice = stopPrice,
            quantity = quantity,
            currentMarketPrice = execPrice,
            exchange = currentEx
        )
        refresh()
        // P2.2 Lifecycle
        if (result is agu.analys.trading.SimulationOrderResult.Success) {
            agu.analys.engine.scalping.SignalLifecycleManager.markTriggered(pair.symbol)
            if (result.order.status == agu.analys.trading.SimulationOrderStatus.FILLED) {
                _lastFilledOrder.value = result.order
                onOrderFilled?.invoke(result.order)
            }
        }
        return result
    }

    fun cancelOrder(orderId: String): Boolean {
        val ok = store.cancelOrder(orderId, currentEx)
        if (ok) refresh()
        return ok
    }

    fun cancelAllOrders(symbol: String? = null): Int {
        val count = store.cancelAllOrders(symbol, currentEx)
        if (count > 0) refresh()
        return count
    }

    fun executeSimulationSellOrders(
        pair: agu.analys.model.TradingPair,
        totalQuantity: Double,
        marketPrice: Double,
        isAutoTpEnabled: Boolean,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        strategyMode: String = "SCALPING",
        holdingDurationMs: Long? = null,
        entryPrice: Double? = null,
        entryTimestamp: Long? = null,
        isTrailingUsed: Boolean = false,
        trailingPercent: Double? = null,
        trailingPeakPrice: Double? = null,
        trailingLockPrice: Double? = null,
        signalSnapshot: TradeSignalSnapshot? = null,
        onResult: (Boolean, String) -> Unit
    ) {
        val wallet = store.getWallet(currentEx)
        val availableCoin = wallet.getAvailableCoin(pair.baseAsset)
        val sellQty = if (totalQuantity > 0.0) totalQuantity.coerceAtMost(availableCoin) else availableCoin
        if (sellQty <= 0.0) {
            onResult(false, "Saldo simulasi ${pair.baseAsset} kosong.")
            return
        }

        if (isAutoTpEnabled && tp1Price > 0.0 && tp2Price > 0.0) {
            val p1 = (tp1Percent / 100.0).coerceIn(0.01, 0.99)
            val qty1 = ((sellQty * p1) * 100_000_000.0).toLong() / 100_000_000.0
            val qty2 = sellQty - qty1

            var msg = ""
            var okCount = 0
            if (qty1 > 0.0) {
                val r1 = store.placeOrder(
                    symbol = pair.symbol,
                    baseAsset = pair.baseAsset,
                    quoteAsset = pair.quoteAsset,
                    side = agu.analys.trading.SimulationOrderSide.SELL,
                    type = agu.analys.trading.SimulationOrderType.LIMIT,
                    price = tp1Price,
                    stopPrice = 0.0,
                    quantity = qty1,
                    currentMarketPrice = marketPrice,
                    exchange = currentEx
                )
                if (r1 is agu.analys.trading.SimulationOrderResult.Success) {
                    okCount++
                    msg += "TP1: ${agu.analys.util.PriceFormatter.formatCryptoExact(qty1, 8)} @ ${agu.analys.util.PriceFormatter.formatPrice(tp1Price, quoteAsset = pair.quoteAsset)} (OK). "
                } else if (r1 is agu.analys.trading.SimulationOrderResult.Error) {
                    msg += "TP1 Gagal: ${r1.message}. "
                }
            }
            if (qty2 > 0.0) {
                val r2 = store.placeOrder(
                    symbol = pair.symbol,
                    baseAsset = pair.baseAsset,
                    quoteAsset = pair.quoteAsset,
                    side = agu.analys.trading.SimulationOrderSide.SELL,
                    type = agu.analys.trading.SimulationOrderType.LIMIT,
                    price = tp2Price,
                    stopPrice = 0.0,
                    quantity = qty2,
                    currentMarketPrice = marketPrice,
                    exchange = currentEx
                )
                if (r2 is agu.analys.trading.SimulationOrderResult.Success) {
                    okCount++
                    msg += "TP2: ${agu.analys.util.PriceFormatter.formatCryptoExact(qty2, 8)} @ ${agu.analys.util.PriceFormatter.formatPrice(tp2Price, quoteAsset = pair.quoteAsset)} (OK)."
                } else if (r2 is agu.analys.trading.SimulationOrderResult.Error) {
                    msg += "TP2 Gagal: ${r2.message}."
                }
            }
            refresh()
            onResult(okCount > 0, msg.trim())
        } else if (isAutoTpEnabled && tp1Price > 0.0) {
            val r = store.placeOrder(
                symbol = pair.symbol,
                baseAsset = pair.baseAsset,
                quoteAsset = pair.quoteAsset,
                side = agu.analys.trading.SimulationOrderSide.SELL,
                type = agu.analys.trading.SimulationOrderType.LIMIT,
                price = tp1Price,
                stopPrice = 0.0,
                quantity = sellQty,
                currentMarketPrice = marketPrice,
                exchange = currentEx
            )
            refresh()
            val ok = r is agu.analys.trading.SimulationOrderResult.Success
            val m = when (r) {
                is agu.analys.trading.SimulationOrderResult.Success -> "Order Limit TP1 ${agu.analys.util.PriceFormatter.formatCryptoExact(sellQty, 8)} @ ${agu.analys.util.PriceFormatter.formatPrice(tp1Price, quoteAsset = pair.quoteAsset)} terpasang."
                is agu.analys.trading.SimulationOrderResult.Error -> r.message
            }
            onResult(ok, m)
        } else if (isAutoTpEnabled && tp2Price > 0.0) {
            val r = store.placeOrder(
                symbol = pair.symbol,
                baseAsset = pair.baseAsset,
                quoteAsset = pair.quoteAsset,
                side = agu.analys.trading.SimulationOrderSide.SELL,
                type = agu.analys.trading.SimulationOrderType.LIMIT,
                price = tp2Price,
                stopPrice = 0.0,
                quantity = sellQty,
                currentMarketPrice = marketPrice,
                exchange = currentEx
            )
            refresh()
            val ok = r is agu.analys.trading.SimulationOrderResult.Success
            val m = when (r) {
                is agu.analys.trading.SimulationOrderResult.Success -> "Order Limit TP2 ${agu.analys.util.PriceFormatter.formatCryptoExact(sellQty, 8)} @ ${agu.analys.util.PriceFormatter.formatPrice(tp2Price, quoteAsset = pair.quoteAsset)} terpasang."
                is agu.analys.trading.SimulationOrderResult.Error -> r.message
            }
            onResult(ok, m)
        } else {
            val r = store.placeOrder(
                symbol = pair.symbol,
                baseAsset = pair.baseAsset,
                quoteAsset = pair.quoteAsset,
                side = agu.analys.trading.SimulationOrderSide.SELL,
                type = agu.analys.trading.SimulationOrderType.MARKET,
                price = marketPrice,
                stopPrice = 0.0,
                quantity = sellQty,
                currentMarketPrice = marketPrice,
                exchange = currentEx
            )
            refresh()
            if (r is agu.analys.trading.SimulationOrderResult.Success) {
                _lastFilledOrder.value = r.order
                onOrderFilled?.invoke(r.order)
            }
            val m = when (r) {
                is agu.analys.trading.SimulationOrderResult.Success -> "Order Jual Pasar ${agu.analys.util.PriceFormatter.formatCryptoExact(sellQty, 8)} @ ${agu.analys.util.PriceFormatter.formatPrice(marketPrice, quoteAsset = pair.quoteAsset)} berhasil."
                is agu.analys.trading.SimulationOrderResult.Error -> r.message
            }
            onResult(r is agu.analys.trading.SimulationOrderResult.Success, m)
        }
    }

    fun topUpIdr(amount: Double) {
        store.topUpIdr(amount, exchange = currentEx)
        refresh()
    }

    fun topUpUsdt(amount: Double) {
        store.topUpUsdt(amount, exchange = currentEx)
        refresh()
    }

    /** Konversi saldo Rupiah -> USDT pada rate exchange live. */
    fun convertIdrToUsdt(amountIdr: Double): SimulationTradeStore.ConversionResult {
        val result = store.convertIdrToUsdt(amountIdr, exchange = currentEx)
        if (result.success) refresh()
        return result
    }

    /** Konversi saldo USDT -> Rupiah pada rate exchange live. */
    fun convertUsdtToIdr(amountUsdt: Double): SimulationTradeStore.ConversionResult {
        val result = store.convertUsdtToIdr(amountUsdt, exchange = currentEx)
        if (result.success) refresh()
        return result
    }

    fun setBalance(amount: Double) {
        store.setBalance(amount, exchange = currentEx)
        refresh()
    }

    fun resetAccount() {
        store.resetWallet(exchange = currentEx)
        refresh()
    }

    fun onPriceTick(symbol: String, price: Double, high24h: Double, low24h: Double) {
        val filled = store.processPriceTick(symbol, price, high24h, low24h, exchange = currentEx)
        if (filled.isNotEmpty()) {
            _lastFilledOrder.value = filled.lastOrNull()
            refresh()
            filled.forEach { order ->
                onOrderFilled?.invoke(order)
            }
        }
    }
}