package agu.analys.viewmodel

import androidx.lifecycle.viewModelScope
import agu.analys.database.AppDatabase
import agu.analys.database.RealTradeEntity
import agu.analys.config.MarketDataSource
import agu.analys.engine.scalping.PaperAtr
import agu.analys.model.SignalAction
import agu.analys.model.TradingPair
import agu.analys.model.resolveDefaultTimeframe
import agu.analys.service.TokocryptoMarketService
import agu.analys.trading.PaperArmSignal
import agu.analys.trading.RealTradePnlResolver
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SignalDerivedFields
import agu.analys.trading.SignalSymbolAttribution
import agu.analys.trading.SpotPosition
import agu.analys.trading.TradeSignalSnapshot
import agu.analys.util.AlertNotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Extension for TradingViewModel dealing with synchronizing real balances,
 * mirrored simulated trades, foreground service state, position store updates,
 * background trailing polling, and trade execution events.
 */

fun TradingViewModel.handleRealTradeExecution(
    pair: String,
    type: String,
    price: Double,
    quantity: Double,
    tp1: Double,
    tp2: Double
) {
    val currentEx = prefs.marketDataSource.name
    val symbol = pair.replace("_", "").uppercase()
    var isPartialSell = false
    var effectivePrice = price
    if (type.equals("sell", ignoreCase = true)) {
        // Baca posisi SEBELUM diubah: dipakai untuk deteksi jual sebagian dan PnL dari harga masuk.
        val before = positionStore.get(symbol, isReal = true, exchange = currentEx)
        val heldQty = before.quantity
        isPartialSell = before.isHolding && heldQty > 0.0 && (heldQty - quantity) / heldQty > 0.02

        val pending = pendingAutoSells.remove("${currentEx.uppercase()}_$symbol")
            ?.takeIf { System.currentTimeMillis() - it.createdAt < 120_000L }
        effectivePrice = pending?.refPrice?.takeIf { it > 0.0 } ?: price
        val exitReason = pending?.reason ?: if (tp1 > 0 || tp2 > 0) "TAKE_PROFIT" else "MARKET_SELL"

        // PnL bagian yang dijual vs harga masuk posisi (jurnal tidak boleh membandingkan jual sebagian dengan total modal)
        var customPnlIdr: Double? = null
        var customPnlPct: Double? = null
        if (before.entryPrice > 0.0 && effectivePrice > 0.0 && quantity > 0.0) {
            // J8 — SATU rumus P&L. Sebelumnya di sini ada rumus sendiri tanpa slippage, sehingga
            // angka jurnal berbeda ~0,16 pp dari portofolio/radar jual untuk trade yang sama.
            val quoteAsset = agu.analys.util.PriceFormatter.extractQuote(symbol)
            val pnl = RealTradePnlResolver.resolve(
                quantity = quantity,
                entry = before.entryPrice,
                exitPrice = effectivePrice,
                exchange = currentEx,
                quoteAsset = quoteAsset
            )
            customPnlPct = pnl.pnlPct
            customPnlIdr = pnl.pnlIdr
        }

        if (isPartialSell) {
            // Sisa koin tetap dipantau (trailing, TP2, SL tetap aktif)
            val remaining = heldQty - quantity
            positionStore.setHolding(
                symbol = symbol,
                invested = before.entryPrice * remaining,
                entry = before.entryPrice,
                quantity = remaining,
                isReal = true,
                exchange = currentEx
            )
        } else {
            positionStore.markSold(symbol, isReal = true, exchange = currentEx)
            positionStore.markSold(pair, isReal = true, exchange = currentEx)
            agu.analys.engine.sell.SellSignalLifecycleManager.reset(symbol, isReal = true)
            agu.analys.engine.sell.SellSignalLifecycleManager.reset(pair, isReal = true)
            positionCoordinator.markSoldAndClear(symbol, isReal = true)
            positionCoordinator.markSoldAndClear(pair, isReal = true)
            signalLogRepository.expireTrackingLogsForSymbol(symbol, "Posisi real sudah terjual (MarkSold)", currentEx)
        }
        tradeHistoryRecorder.recordSell(
            symbol = symbol, isReal = true, sellPrice = effectivePrice, sellQuantity = quantity,
            sellReason = exitReason,
            strategyMode = strategyMode.value.name,
            customPnlIdr = customPnlIdr,
            customPnlPercent = customPnlPct,
            exchange = currentEx,
            // P0-5 — status trailing nyata dari posisi sebelum ditutup.
            isTrailingUsed = before.isTrailingEnabled,
            trailingPercent = before.trailingPercent.takeIf { it > 0.0 },
            trailingPeakPrice = before.peakPrice.takeIf { it > 0.0 },
            trailingLockPrice = before.trailingStopPrice.takeIf { it > 0.0 }
        )
    } else if (type.equals("buy", ignoreCase = true)) {
        val snapshot = TradeSignalSnapshot.capture(
            symbol = symbol, strategyMode = strategyMode.value.name,
            tick = marketDataCoordinator.dashboardTicks.value[symbol],
            indicators = engine.indicators.value, signal = engine.signalState.value,
            exchange = currentEx
        )
        val snapshotJson = snapshot.toJson().toString()
        // N10 — nilai turunan sinyal hanya boleh menempel bila sinyal itu milik simbol ini.
        // Bila engine terakhir menilai pair lain, confidence/SL/waktu sinyal pair lain dikosongkan.
        val realSignal = SignalSymbolAttribution.attributedFields(
            requestedSymbol = symbol,
            signalSymbol = engine.signalState.value.marketSymbol,
            fields = SignalDerivedFields(
                confidence = engine.signalState.value.confidence,
                entryPrice = engine.signalState.value.entryPrice,
                targetPrice1 = 0.0,
                targetPrice2 = 0.0,
                stopLoss = engine.signalState.value.stopLoss,
                signalTime = engine.signalState.value.timestamp
            ),
            fallbackPrice = price,
            fallbackTime = System.currentTimeMillis()
        )
        // P0-5 — status trailing nyata untuk posisi ini (dibaca sebelum posisi diperbarui).
        val beforeBuy = positionStore.get(symbol, isReal = true, exchange = currentEx)
        tradeHistoryRecorder.recordBuy(
            symbol = symbol, isReal = true, strategyMode = strategyMode.value.name,
            buyPrice = price, buyQuantity = quantity, buyTotalIdr = price * quantity,
            buyOrderType = "LIMIT", snapshot = snapshot,
            signalPrice = realSignal.entryPrice,
            // tp1/tp2 datang dari parameter order (bukan dari engine), jadi tidak perlu diatribusikan.
            signalConfidence = realSignal.confidence, targetPrice1 = tp1, targetPrice2 = tp2,
            stopLossPrice = realSignal.stopLoss,
            exchange = currentEx,
            // P0-3 — waktu sinyal dari engine, bukan waktu klik order.
            signalTime = realSignal.signalTime,
            isTrailingUsed = beforeBuy.isTrailingEnabled,
            trailingPercent = beforeBuy.trailingPercent.takeIf { it > 0.0 },
            trailingPeakPrice = beforeBuy.peakPrice.takeIf { it > 0.0 }
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localEntity = RealTradeEntity(
                    id = "local_${System.currentTimeMillis()}_${symbol}",
                    symbol = pair.lowercase().replace("_", ""),
                    price = price, qty = quantity, amount = price * quantity,
                    time = System.currentTimeMillis(), side = "BUY", isBuyer = true,
                    strategyMode = strategyMode.value.name,
                    holdingDurationMs = 0L, entryPrice = price, entryTimestamp = System.currentTimeMillis(),
                    signalSnapshotJson = snapshotJson,
                    exchange = currentEx
                )
                AppDatabase.getInstance().realTradeDao().insertTrades(listOf(localEntity))
            } catch (_: Exception) {}
        }
    }
    syncRealTradeToSimulation(pair, type, effectivePrice, quantity, tp1, tp2, skipPositionUpdate = isPartialSell)
    positionCoordinator.refreshPosition(selectedPair.value.symbol)
    refreshSpotPosition()
}

fun TradingViewModel.syncRealTradeToSimulation(
    pair: String,
    type: String,
    price: Double,
    quantity: Double,
    tp1: Double = 0.0,
    tp2: Double = 0.0,
    skipPositionUpdate: Boolean = false
) {
    if (!prefs.isRealSimSyncEnabled) return
    val defaultQuote = prefs.marketDataSource.defaultQuoteAsset
    val tradingPair = TradingPair.fromCustomSymbol(pair, defaultQuote)
    val symbol = tradingPair.symbol
    val isBuy = type.equals("buy", ignoreCase = true)
    val currentEx = prefs.marketDataSource.name

    // 1. Mirror ke Riwayat Transaksi Simulasi dengan flag isRealMirror = true
    simCoordinator.recordMirroredRealTrade(
        symbol = symbol,
        baseAsset = tradingPair.baseAsset,
        quoteAsset = tradingPair.quoteAsset,
        side = if (isBuy) SimulationOrderSide.BUY else SimulationOrderSide.SELL,
        price = price,
        quantity = quantity
    )

    // 2. Sinkronkan ke SpotPositionStore agar engine tracking (Trailing Stop / TP / SL / Alert) aktif
    if (isBuy) {
        val currentPos = positionStore.get(symbol, isReal = true, exchange = currentEx)
        if (currentPos.isHolding && currentPos.quantity > 0.00000001 && currentPos.entryPrice > 0.0) {
            val totalQty = currentPos.quantity + quantity
            val totalCost = (currentPos.entryPrice * currentPos.quantity) + (price * quantity)
            val weightedAvgPrice = if (totalQty > 0.0) totalCost / totalQty else price
            positionStore.setHolding(
                symbol = symbol,
                invested = totalCost,
                entry = weightedAvgPrice,
                quantity = totalQty,
                isReal = true,
                exchange = currentEx
            )
        } else {
            positionStore.markBought(
                symbol = symbol,
                entryPrice = price,
                quantity = quantity,
                isReal = true,
                exchange = currentEx
            )
        }
        if (tp1 > price || tp2 > price) {
            val currentPos = positionStore.get(symbol, isReal = true, exchange = currentEx)
            positionStore.setAutoSellParams(
                symbol = symbol,
                enabled = true,
                tp1Price = if (tp1 > 0.0) tp1 else currentPos.tp1Price,
                tp1Percent = 50.0,
                tp2Price = if (tp2 > 0.0) tp2 else currentPos.tp2Price,
                tp2Percent = 50.0,
                isReal = true,
                exchange = currentEx
            )
        }
    } else if (!skipPositionUpdate) {
        val currentPos = positionStore.get(symbol, isReal = true, exchange = currentEx)
        val remainingQty = (currentPos.quantity - quantity).coerceAtLeast(0.0)
        if (remainingQty <= 0.00000001) {
            positionStore.markSold(symbol, isReal = true, exchange = currentEx)
        } else {
            positionStore.setHolding(
                symbol = symbol,
                invested = currentPos.entryPrice * remainingQty,
                entry = currentPos.entryPrice,
                quantity = remainingQty,
                isReal = true,
                exchange = currentEx
            )
        }
    }
    refreshSpotPosition()
}

fun TradingViewModel.syncSimulationTradeToPositionStore(order: SimulationOrder) {
    val symbol = order.symbol
    val currentEx = prefs.marketDataSource.name
    val fillPrice = if (order.filledAvgPrice > 0.0) order.filledAvgPrice else order.limitPrice
    if (order.side == SimulationOrderSide.BUY) {
        val currentPos = positionStore.get(symbol, isReal = false, exchange = currentEx)
        // P3 — DCA: posisi sudah ada, jadi entry berubah menjadi harga rata-rata. Mesin exit WAJIB
        // dibangun ulang, karena mesin lama masih memakai entry fill **pertama** sementara store
        // memakai rata-rata. Dulu `armPaperExitEngine` dipanggil dengan `force = false`, sehingga
        // mesin tidak pernah diperbarui: SL lama (milik entry lama) bisa berada DI ATAS rata-rata
        // baru, dan pada restart berikutnya kondisi itu memicu penjualan paksa berlabel `STOP_LOSS`.
        val isDca = currentPos.isHolding && currentPos.quantity > 0.00000001 && currentPos.entryPrice > 0.0
        val effectiveEntry: Double
        if (isDca) {
            val totalQty = currentPos.quantity + order.quantity
            val totalCost = (currentPos.entryPrice * currentPos.quantity) + (fillPrice * order.quantity)
            effectiveEntry = if (totalQty > 0.0) totalCost / totalQty else fillPrice
            positionStore.setHolding(
                symbol = symbol,
                invested = totalCost,
                entry = effectiveEntry,
                quantity = totalQty,
                isReal = false,
                exchange = currentEx
            )
        } else {
            effectiveEntry = fillPrice
            positionStore.markBought(
                symbol = symbol,
                entryPrice = fillPrice,
                quantity = order.quantity,
                isReal = false,
                exchange = currentEx
            )
        }
        val snapshot = TradeSignalSnapshot.capture(
            symbol = symbol, strategyMode = strategyMode.value.name,
            tick = marketDataCoordinator.dashboardTicks.value[symbol],
            indicators = engine.indicators.value, signal = engine.signalState.value,
            exchange = currentEx
        )
        // N10 — nilai turunan sinyal hanya boleh menempel bila sinyal itu milik simbol ini.
        // Bila engine terakhir menilai pair lain, confidence/TP/SL pair lain dikosongkan.
        val paperSignal = SignalSymbolAttribution.attributedFields(
            requestedSymbol = symbol,
            signalSymbol = engine.signalState.value.marketSymbol,
            fields = SignalDerivedFields(
                confidence = engine.signalState.value.confidence,
                entryPrice = engine.signalState.value.entryPrice,
                targetPrice1 = engine.signalState.value.targetPrice1,
                targetPrice2 = engine.signalState.value.targetPrice2,
                stopLoss = engine.signalState.value.stopLoss,
                signalTime = engine.signalState.value.timestamp
            ),
            fallbackPrice = fillPrice,
            fallbackTime = System.currentTimeMillis()
        )
        // P0-5 — state posisi setelah entry (bukan sebelum), supaya status trailing yang tercatat
        // adalah yang benar-benar berlaku untuk posisi ini.
        val posAfterBuy = positionStore.get(symbol, isReal = false, exchange = currentEx)
        tradeHistoryRecorder.recordBuy(
            symbol = symbol,
            isReal = false,
            strategyMode = strategyMode.value.name,
            buyPrice = fillPrice,
            buyQuantity = order.quantity,
            buyTotalIdr = fillPrice * order.quantity,
            buyOrderType = order.type.name,
            snapshot = snapshot,
            signalPrice = paperSignal.entryPrice,
            signalConfidence = paperSignal.confidence,
            targetPrice1 = paperSignal.targetPrice1,
            targetPrice2 = paperSignal.targetPrice2,
            stopLossPrice = paperSignal.stopLoss,
            exchange = currentEx,
            // P0-3 — waktu sinyal dari engine, bukan waktu klik order.
            signalTime = paperSignal.signalTime,
            isTrailingUsed = posAfterBuy.isTrailingEnabled,
            trailingPercent = posAfterBuy.trailingPercent.takeIf { it > 0.0 },
            trailingPeakPrice = posAfterBuy.peakPrice.takeIf { it > 0.0 }
        )

        // S7 — posisi paper yang dibeli **manual** kini juga mendapat mesin exit.
        // Sebelumnya mesin exit hanya dibuat untuk auto-buy ([tryPaperAutoBuy]), sehingga beli
        // manual berjalan tanpa SL/BE/trailing terkelola — kasus AMP/USDT menahan −2,76%
        // selama 9 menit tanpa stop apa pun. Bila mesin sudah ada (jalur auto-buy), pemanggilan
        // ini tidak menimpa apa pun.
        //
        // P3 — kecuali untuk DCA: entry berubah menjadi rata-rata, jadi mesin lama (yang memakai
        // entry fill pertama) harus diganti. `force = isDca` adalah satu-satunya jalur yang boleh
        // menimpa mesin yang sudah hidup.
        //
        // P4 — `atrPct` kini ikut dikirim. Sebelumnya hanya jalur auto-buy yang bisa menghitungnya
        // (fungsi ATR-nya `private` di berkas lain), sehingga setiap posisi hasil beli manual
        // menerima `atrPct = 0.0`. Karena `0.0` berarti "ATR tidak diketahui", mesin jatuh ke ambang
        // BE/trailing persen tetap dan nilai nol itu ikut tersimpan ke disk — kerusakannya persisten.
        val atrPct = PaperAtr.of(engine.indicators.value.atr, effectiveEntry)
        // J10 (sisa) — SL/TP untuk mesin exit juga wajib milik simbol ini. Sebelumnya jalur ini
        // membaca `engine.signalState.value` mentah: TP1 pair lain yang berada di bawah entry
        // bisa memicu jual sebagian pada tick pertama. Bila sinyal bukan milik simbol ini,
        // ketiganya nol -> SL jatuh ke lantai friksi, TP tidak ada.
        val engineSignal = engine.signalState.value
        val armParams = PaperArmSignal.resolve(
            requestedSymbol = symbol,
            signalSymbol = engineSignal.marketSymbol,
            stopLoss = engineSignal.stopLoss,
            tp1 = engineSignal.targetPrice1,
            tp2 = engineSignal.targetPrice2
        )
        val armed = armPaperExitEngine(
            symbol = symbol,
            exchange = currentEx,
            entryPrice = effectiveEntry,
            stopLoss = armParams.stopLoss,
            tp1 = armParams.tp1,
            tp2 = armParams.tp2,
            atrPct = atrPct,
            force = isDca
        )
        if (isDca) {
            agu.analys.util.AppLogManager.service(
                "PaperAutoExit",
                if (armed) {
                    "🔁 [$symbol] DCA: entry menjadi rata-rata " +
                        "${agu.analys.util.PriceFormatter.formatPrice(effectiveEntry, quoteAsset = TradingPair.fromCustomSymbol(symbol, currentEx).quoteAsset)} — " +
                        "mesin exit dibangun ulang agar SL/TP sepadan dengan entry baru."
                } else {
                    "⚠️ [$symbol] DCA: entry menjadi rata-rata tetapi mesin exit tidak bisa dibangun ulang " +
                        "(lantai friksi nonaktif atau SL tidak tersedia). Periksa SL posisi ini."
                }
            )
        }
    } else if (order.side == SimulationOrderSide.SELL) {
        val currentPos = positionStore.get(symbol, isReal = false, exchange = currentEx)
        val currentQty = currentPos.quantity
        val remainingQty = (currentQty - order.quantity).coerceAtLeast(0.0)
        if (remainingQty <= 0.00000001) {
            positionStore.markSold(symbol, isReal = false, exchange = currentEx)
        } else {
            positionStore.setHolding(
                symbol = symbol,
                invested = currentPos.entryPrice * remainingQty,
                entry = currentPos.entryPrice,
                quantity = remainingQty,
                isReal = false,
                exchange = currentEx
            )
        }
        tradeHistoryRecorder.recordSell(
            symbol = symbol,
            isReal = false,
            sellPrice = fillPrice,
            sellQuantity = order.quantity,
            // P0-4 — alasan jual sebenarnya, bukan konstanta "SIMULATION_SELL".
            sellReason = resolveSimulationSellReason(order, currentPos),
            strategyMode = strategyMode.value.name,
            exchange = currentEx,
            // P0-5 — status trailing nyata saat posisi ditutup (dibaca sebelum markSold).
            isTrailingUsed = currentPos.isTrailingEnabled,
            trailingPercent = currentPos.trailingPercent.takeIf { it > 0.0 },
            trailingPeakPrice = currentPos.peakPrice.takeIf { it > 0.0 },
            trailingLockPrice = currentPos.trailingStopPrice.takeIf { it > 0.0 }
        )
    }
    refreshSpotPosition()
}

/**
 * P0-4 — Tentukan alasan jual yang sebenarnya dari jalur simulasi.
 *
 * Sebelumnya jalur ini selalu menulis `"SIMULATION_SELL"` (konstan), sehingga jurnal tidak bisa
 * membedakan exit manual, TP1/TP2, stop loss, dan trailing stop — padahal itu variabel paling
 * penting untuk mengevaluasi strategi.
 */
private fun resolveSimulationSellReason(order: SimulationOrder, pos: SpotPosition): String {
    val fill = if (order.filledAvgPrice > 0.0) order.filledAvgPrice else order.limitPrice
    val near = { target: Double -> target > 0.0 && fill > 0.0 && abs(fill - target) / target <= 0.0015 }

    return when {
        order.isStopTriggered && pos.isTrailingEnabled && pos.isTrailingTriggered -> "TRAILING_STOP"
        order.isStopTriggered -> "STOP_LOSS"
        near(pos.tp2Price) -> "HIT_TP2"
        near(pos.tp1Price) -> "HIT_TP1"
        pos.stopLossPrice > 0.0 && fill > 0.0 && fill <= pos.stopLossPrice * 1.001 -> "STOP_LOSS"
        else -> "MANUAL_SELL"
    }
}

fun TradingViewModel.baseFromSymbolOrPair(pair: String): String {
    val s = pair.lowercase().replace("_", "")
    return when {
        s.endsWith("idr") -> s.removeSuffix("idr")
        s.endsWith("usdt") -> s.removeSuffix("usdt")
        s.endsWith("bidr") -> s.removeSuffix("bidr")
        s.endsWith("usd") -> s.removeSuffix("usd")
        else -> s
    }
}

fun TradingViewModel.syncRealBalancesToPositionStore(
    balances: Map<String, Double> = realCoordinator.realSpotBalance.value,
    avgPrices: Map<String, Double> = realCoordinator.realAvgBuyPrices.value
) {
    val hasCreds = prefs.hasTokocryptoCredentials()
    if (!hasCreds) return
    val currentEx = prefs.marketDataSource.name
    val isToko = prefs.marketDataSource == MarketDataSource.TOKOCRYPTO
    val defaultQuote = if (isToko) "USDT" else "IDR"
    val staleQuote = if (isToko) "IDR" else "USDT"
    
    val basePairs = if (isToko) {
        TradingPair.POPULAR_TOKOCRYPTO_PAIRS.map { it.baseAsset.uppercase() }
    } else {
        TradingPair.POPULAR_IDR_PAIRS.map { it.baseAsset.uppercase() }
    }
    val popularAndCustom = (basePairs + balances.keys.map { it.uppercase() }).distinct()
    
    for (baseUpper in popularAndCustom) {
        if (baseUpper == "IDR" || baseUpper == "BIDR" || baseUpper == "USDT" || baseUpper == "USDC" || baseUpper == "USD") continue
        val baseLower = baseUpper.lowercase()
        val symbol = "${baseUpper}${defaultQuote}"
        val pairSymbol = "${baseLower}_${defaultQuote.lowercase()}"
        val staleSymbol = "${baseUpper}${staleQuote}"
        val stalePairSymbol = "${baseLower}_${staleQuote.lowercase()}"
        
        val qty = balances[baseLower] ?: balances[baseUpper] ?: 0.0
        val pos = positionStore.get(symbol, isReal = true, exchange = currentEx)
        
        val avgPrice = avgPrices[symbol]
            ?: avgPrices["${baseLower}${defaultQuote.lowercase()}"]
            ?: avgPrices[baseUpper]
            ?: avgPrices[baseLower]
            ?: 0.0
        
        if (qty > 0.00000001) {
            val finalEntry = if (avgPrice > 0.0) avgPrice else if (pos.entryPrice > 0.0) pos.entryPrice else 0.0
            val totalInvested = if (finalEntry > 0.0) finalEntry * qty else pos.investedAmount
            if (!pos.isHolding) {
                positionStore.markBought(
                    symbol = symbol,
                    entryPrice = finalEntry,
                    quantity = qty,
                    invested = totalInvested,
                    isReal = true,
                    exchange = currentEx
                )
                positionStore.markBought(
                    symbol = pairSymbol,
                    entryPrice = finalEntry,
                    quantity = qty,
                    invested = totalInvested,
                    isReal = true,
                    exchange = currentEx
                )
            } else {
                positionStore.setHolding(
                    symbol = symbol,
                    invested = totalInvested,
                    entry = finalEntry,
                    quantity = qty,
                    isReal = true,
                    exchange = currentEx
                )
                positionStore.setHolding(
                    symbol = pairSymbol,
                    invested = totalInvested,
                    entry = finalEntry,
                    quantity = qty,
                    isReal = true,
                    exchange = currentEx
                )
            }
            // Bersihkan posisi duplikat kuotasi yang tidak aktif untuk koin ini pada exchange ini
            positionStore.markSold(staleSymbol, isReal = true, exchange = currentEx)
            positionStore.markSold(stalePairSymbol, isReal = true, exchange = currentEx)
        } else {
            if (pos.isHolding) {
                positionStore.markSold(symbol, isReal = true, exchange = currentEx)
                positionStore.markSold(pairSymbol, isReal = true, exchange = currentEx)
            }
            positionStore.markSold(staleSymbol, isReal = true, exchange = currentEx)
            positionStore.markSold(stalePairSymbol, isReal = true, exchange = currentEx)
        }
    }
    refreshSpotPosition()
    agu.analys.bridge.AgentTradingCommandHandler.publishPortfolioSnapshot(getApplication<android.app.Application>())
}

fun TradingViewModel.updateForegroundServiceState() {
    val isReal = isRealBuyMode.value
    val currentEx = prefs.marketDataSource.name
    val hasActive = positionStore.getAllActiveTrailingSymbols(isReal = isReal, exchange = currentEx).isNotEmpty() ||
                    positionStore.hasAnyHolding(isReal = isReal, exchange = currentEx) ||
                    (!isReal && simCoordinator.wallet.value.coinBalances.any { it.value > 0.00000001 && !it.key.equals("IDR", true) && !it.key.equals("USDT", true) }) ||
                    (isReal && realCoordinator.realSpotBalance.value.any { it.value > 0.00000001 && !it.key.equals("IDR", true) && !it.key.equals("USDT", true) })

    // N15 — keputusan start/stop **hanya** dari keberadaan posisi.
    //
    // Dulu syaratnya `hasActive && isNotificationsEnabled.value`: mematikan sakelar "push
    // notifikasi" ikut mematikan foreground service, yaitu keep-alive pemantauan posisi — dan
    // membuat `livePrices` kosong sehingga P&L tampil 0% untuk semua posisi. Itu melanggar aturan
    // "mematikan notifikasi tidak boleh mematikan eksekusi". Bunyi notifikasi tetap diatur
    // `canNotify(...)` di dalam helper, bukan di sini.
    if (hasActive) {
        agu.analys.service.TradingForegroundService.startService(getApplication())
        agu.analys.service.TradingForegroundService.forceRefresh(getApplication())
    } else {
        agu.analys.service.TradingForegroundService.stopService(getApplication())
    }
}

fun TradingViewModel.initSubscriptionsAndPolling() {
    viewModelScope.launch {
        realCoordinator.isRealBuyEnabled.collect {
            refreshSpotPosition()
        }
    }
    watchlistViewModel.onWatchlistUpdated = {
        recalculateDashboardBadges()
    }
    agu.analys.util.MtfCacheManager.updateQueues(watchlist.value.toList(), emptyList(), prefs.marketDataSource.name)
    engine.strategyMode = prefs.strategyMode
    engine.isScalpingMode = prefs.isScalpingMode
    engine.tradingFees = prefs.tradingFees
    // FeeTruth: tarif sadar-kuotasi per simbol (IDR ≠ USDT). Tanpa ini engine memakai preset
    // USDT global untuk pair IDR → lantai SL & net R:R beda dari jalur paper (lihat KDoc
    // `LearningTradingEngine.feeConfigForSymbol`).
    engine.feeConfigForSymbol = { symbol -> prefs.feeConfigFor(symbol) }
    engine.scalpingRuntimeConfig = prefs.scalpingRuntimeConfig
    // S5 — samakan basis PnL simulasi dengan mesin exit (fee + 2x slippage).
    simCoordinator.setSlippagePct(prefs.scalpingRuntimeConfig.slippagePct)

    // P2 — bangun ulang state machine exit paper setelah restart. Tanpa ini posisi simulasi
    // menggantung: `PaperAutoTradeRuntime` hanya hidup di memori, sedangkan `auto_sell_enabled`
    // sudah dimatikan saat engine mengambil alih exit. Lihat [PaperRehydrate].
    runCatching { rehydratePaperAutoExit() }.onFailure {
        agu.analys.util.AppLogManager.service(
            "PaperAutoExit",
            "⚠️ Rehidrasi posisi paper gagal: ${it.message}"
        )
    }

    // P1 — ganti bursa juga harus memicu rehidrasi. Mesin exit untuk bursa yang baru dipilih
    // mungkin hanya ada di disk (bukan di memori), dan tanpa pemicu ini posisi paper di bursa itu
    // berjalan tanpa SL/TP/BE/trailing walau pengguna sudah kembali ke sana. Dulu
    // `setMarketDataSource` sama sekali tidak memanggil rehidrasi, sehingga kembali ke bursa asal
    // pun tidak memulihkan apa pun.
    viewModelScope.launch {
        var isFirstEmission = true
        marketDataSource.collect { source ->
            if (isFirstEmission) {
                // Emisi pertama adalah nilai saat ini — sudah ditangani rehidrasi di atas.
                isFirstEmission = false
                return@collect
            }
            agu.analys.util.AppLogManager.service(
                "PaperAutoExit",
                "🔀 Bursa aktif berpindah ke ${source.name} — membangun ulang mesin exit posisi paper."
            )
            runCatching { rehydratePaperAutoExit() }.onFailure {
                agu.analys.util.AppLogManager.service(
                    "PaperAutoExit",
                    "⚠️ Rehidrasi setelah ganti bursa gagal: ${it.message}"
                )
            }
            checkAndStopTrailingServiceIfEmpty()
        }
    }

    engine.onCandidateSignalTransition = { transition ->
        if (isNotificationsEnabled.value) {
            val isReal = isRealBuyMode.value
            val currentEx = prefs.marketDataSource.name
            val position = positionStore.get(transition.symbol, isReal = isReal, exchange = currentEx)
            if (!position.isHolding) {
                AlertNotificationHelper.sendCandidateFoundNotification(
                    context = getApplication(),
                    symbol = transition.symbol,
                    strategyMode = transition.mode,
                    signal = transition.signal,
                    exchange = currentEx
                )
            }
        }
        if (transition.signal.action != SignalAction.HOLD && transition.signal.entryPrice > 0.0) {
            signalLogRepository.recordSignal(
                symbol = transition.symbol,
                action = transition.signal.action.name,
                strategyMode = transition.mode.name,
                confidence = transition.signal.confidence,
                sentiment = transition.signal.sentiment.name,
                entryPrice = transition.signal.entryPrice,
                targetPrice1 = transition.signal.targetPrice1,
                targetPrice2 = transition.signal.targetPrice2,
                stopLoss = transition.signal.stopLoss,
                reasoning = transition.signal.reasoning.joinToString(" • "),
                scalpingStage = transition.signal.scalpingStage.name,
                exchange = prefs.marketDataSource.name,
                scalpingSetup = transition.signal.scalpingSetup,
                scalpingScore = transition.signal.scalpingScore,
                scalpingScoreCategory = transition.signal.scalpingScoreCategory,
                scalpingRegime = transition.signal.scalpingRegime
            )

            emitAgentSignalIfEnabled(
                symbol = transition.symbol,
                action = transition.signal.action.name,
                mode = transition.mode.name,
                confidence = transition.signal.confidence,
                entryPrice = transition.signal.entryPrice,
                tp1 = transition.signal.targetPrice1,
                tp2 = transition.signal.targetPrice2,
                stopLoss = transition.signal.stopLoss,
                reasoning = transition.signal.reasoning.joinToString(" • "),
                orderbookPressure = transition.signal.scalpingScoreCategory.ifBlank { null },
                mtf = transition.signal.mtf,
                scalpingSetup = transition.signal.scalpingSetup,
                scalpingRegime = transition.signal.scalpingRegime
            )
        }

        // P2 — Auto-buy PAPER (simulasi). Tidak pernah memicu order uang riil.
        // Hanya saat transisi baru siap/di-trigger agar tidak membuka posisi berulang.
        if (transition.isNewReady || transition.isNewDetected) {
            runCatching {
                tryPaperAutoBuy(transition, prefs.marketDataSource.name)
            }.onFailure {
                agu.analys.util.AppLogManager.service(
                    "PaperAutoBuy",
                    "⚠️ Auto-buy paper gagal diproses untuk ${transition.symbol}: ${it.message}"
                )
            }
        }
    }

    viewModelScope.launch(Dispatchers.IO) {
        agu.analys.data.TokocryptoSymbolRepository.ensureSymbolsLoaded(false)
    }

    // Sinkronisasi SATU ARAH: MarketDataCoordinator (SSOT live feed) → MarketViewModel.
    // Echo-balik VM→coordinator dihapus: memutus loop 500ms yang memicu recomposition
    // berulang. VM tetap menulis cache restore & combinedTicks ke copy-nya sendiri;
    // UI menggabungkan hot/gainers/losers/topVolume dari flow VM langsung.
    viewModelScope.launch {
        marketDataCoordinator.dashboardTicks.collect { ticks ->
            if (ticks.isNotEmpty()) {
                marketViewModel.updateDashboardTicks(ticks)
            }
            delay(500L) // batasi maks 2x/detik; nilai terbaru tetap terkirim (StateFlow conflated)
        }
    }

    marketDataCoordinator.restoreFromCache(prefs.marketDataSource)
    val initialPair = TradingPair.popularPairsForSource(prefs.marketDataSource).first()
    // Default timeframe chart mengikuti mode tersimpan (2026-10-26) — resolver murni di
    // model/PositionContext.kt, bukan bawaan H4 global MarketViewModel. Diletakkan sebelum
    // selectPair supaya cache + polling awal langsung memakai TF yang benar.
    marketViewModel.selectTimeframe(resolveDefaultTimeframe(prefs.strategyMode))
    selectPair(initialPair)
    refreshWorthCoinsFromMarket()
    startDashboardPolling()
    startTrailingPolling()
    updateForegroundServiceState()
    listenToEngineSignals()
    checkPublicIp()

    if (prefs.hasTokocryptoCredentials()) {
        syncRealBalancesToPositionStore()
    }

    viewModelScope.launch {
        tradeHistoryRecorder.seedSampleTradeJourneysIfEmpty()
    }

    viewModelScope.launch {
        simCoordinator.lastFilledOrder.collect { filledOrder ->
            if (filledOrder != null && filledOrder.status == agu.analys.trading.SimulationOrderStatus.FILLED) {
                if (filledOrder.side == SimulationOrderSide.SELL) {
                    positionCoordinator.markSoldAndClear(filledOrder.symbol)
                    positionCoordinator.setTrailing(filledOrder.symbol, enabled = false, 0.0, 0.0)
                    signalLogRepository.expireTrackingLogsForSymbol(
                        filledOrder.symbol,
                        "Simulasi sell filled (MarkSold)",
                        prefs.marketDataSource.name
                    )
                    checkAndStopTrailingServiceIfEmpty()
                }
            }
        }
    }
}

fun TradingViewModel.startTrailingPolling() {
    if (trailingPollJob?.isActive == true) return
    trailingPollJob = viewModelScope.launch {
        while (isActive) {
            try {
                val currentEx = prefs.marketDataSource.name
                val isReal = isRealBuyMode.value
                // P2: posisi paper juga ikut dipantau, walau bukan pair yang sedang dibuka,
                // supaya auto-exit tetap berjalan di latar belakang.
                // P1: simbol paper juga dibaca dari **disk**, bukan hanya dari mesin di memori —
                // `PaperAutoTradeRuntime` kosong setelah restart sampai rehidrasi selesai, jadi
                // tanpa ini posisi paper bisa terlewat satu putaran polling.
                val activeSymbols = (
                    positionStore.getAllActiveTrailingSymbols(isReal = isReal, exchange = currentEx) +
                        PaperAutoTradeRuntime.activeSymbols(currentEx) +
                        positionStore.getPaperEngineSymbols(isReal = false, exchange = currentEx)
                    ).distinct()
                if (activeSymbols.isNotEmpty()) {
                    val ticks = TokocryptoMarketService.fetchTickers(activeSymbols)
                    for (tick in ticks) {
                        simCoordinator.onPriceTick(tick.symbol, tick.price, tick.high24h, tick.low24h)
                        checkAlertsAndTrailing(tick.symbol, tick.price, exchange = currentEx)
                        // P2: pemeriksaan level exit untuk posisi paper. Jendela M1 tidak
                        // tersedia di jalur latar ini, jadi early-profit sengaja dilewati —
                        // hanya SL / TP / BE / trailing yang dievaluasi.
                        runCatching { evaluatePaperAutoExit(tick.symbol, currentEx, tick.price) }
                        signalLogRepository.processPriceTick(tick.symbol, tick.price, currentEx)
                        tradeHistoryRecorder.processPriceTick(tick.symbol, tick.price, currentEx)
                    }
                    delay(10_000L)
                } else {
                    checkAndStopTrailingServiceIfEmpty()
                    delay(20_000L)
                }
            } catch (_: Exception) {
                delay(12_000L)
            }
        }
    }
}

fun TradingViewModel.checkAndStopTrailingServiceIfEmpty() {
    updateForegroundServiceState()
    val currentEx = prefs.marketDataSource.name
    val isReal = isRealBuyMode.value
    val hasTrailing = positionStore.getAllActiveTrailingSymbols(isReal = isReal, exchange = currentEx).isNotEmpty()
    // P2: jangan hentikan polling selama masih ada posisi paper yang perlu diawasi exit-nya.
    val hasPaper = PaperAutoTradeRuntime.activeSymbols(currentEx).isNotEmpty()
    if (!hasTrailing && !hasPaper) {
        trailingPollJob?.cancel()
        trailingPollJob = null
    }
}

fun TradingViewModel.listenToEngineSignals() {
    viewModelScope.launch {
        engine.signalState.collect { signal ->
            val now = System.currentTimeMillis()
            if (signal.action != SignalAction.HOLD && now - lastSavedSignalTimestamp > 15000L) {
                // J4 — simbol jurnal adalah simbol yang **dievaluasi engine**, bukan pair yang
                // sedang dibuka. `selectPair()` mengganti pair lebih dulu, sedangkan `signalState`
                // masih berisi hasil pair lama sampai tick berikutnya; menstempel
                // `selectedPair` di sini membuat entry/TP/SL/confidence BTC tercatat sebagai ETH.
                val journalSymbol = SignalSymbolAttribution.journalSymbol(
                    signalSymbol = signal.marketSymbol,
                    selectedSymbol = selectedPair.value.symbol
                )
                if (!SignalSymbolAttribution.isRecordable(journalSymbol)) return@collect
                lastSavedSignalTimestamp = now
                val list = _signalHistory.value.toMutableList()
                list.add(0, signal.copy(marketSymbol = journalSymbol))
                if (list.size > 30) list.removeAt(list.lastIndex)
                _signalHistory.value = list

                signalLogRepository.recordSignal(
                    symbol = journalSymbol, action = signal.action.name,
                    strategyMode = strategyMode.value.name, confidence = signal.confidence,
                    sentiment = signal.sentiment.name,
                    // J4 — harga fallback juga harus milik simbol jurnal, bukan tick pair yang
                    // kebetulan sedang dibuka.
                    entryPrice = SignalSymbolAttribution.entryPrice(
                        signalEntryPrice = signal.entryPrice,
                        tickPrice = marketDataCoordinator.dashboardTicks.value[journalSymbol]?.price
                    ),
                    targetPrice1 = signal.targetPrice1, targetPrice2 = signal.targetPrice2, stopLoss = signal.stopLoss,
                    reasoning = signal.reasoning.joinToString(" • "), scalpingStage = signal.scalpingStage.name,
                    exchange = prefs.marketDataSource.name,
                    scalpingSetup = signal.scalpingSetup, scalpingScore = signal.scalpingScore,
                    scalpingScoreCategory = signal.scalpingScoreCategory, scalpingRegime = signal.scalpingRegime
                )

                emitAgentSignalIfEnabled(
                    symbol = journalSymbol,
                    action = signal.action.name,
                    mode = strategyMode.value.name,
                    confidence = signal.confidence,
                    entryPrice = SignalSymbolAttribution.entryPrice(
                        signalEntryPrice = signal.entryPrice,
                        tickPrice = marketDataCoordinator.dashboardTicks.value[journalSymbol]?.price
                    ),
                    tp1 = signal.targetPrice1,
                    tp2 = signal.targetPrice2,
                    stopLoss = signal.stopLoss,
                    reasoning = signal.reasoning.joinToString(" • "),
                    orderbookPressure = signal.scalpingScoreCategory.ifBlank { null },
                    mtf = signal.mtf,
                    scalpingSetup = signal.scalpingSetup,
                    scalpingRegime = signal.scalpingRegime
                )
            }
        }
    }
}

fun TradingViewModel.emitAgentSignalIfEnabled(
    symbol: String,
    action: String,
    mode: String,
    confidence: Int,
    entryPrice: Double,
    tp1: Double,
    tp2: Double,
    stopLoss: Double,
    reasoning: String,
    orderbookPressure: String? = null,
    mtf: agu.analys.model.ScalpingMtfSnapshot? = null,
    scalpingSetup: String? = null,
    scalpingRegime: String? = null
) {
    val bridge = agu.analys.bridge.AgentOutputBridge.getInstance(getApplication())
    if (!bridge.isEnabled()) return
    val currentEx = prefs.marketDataSource.name
    val quote = if (symbol.uppercase().endsWith("USDT")) "USDT" else "IDR"
    val feeRate = agu.analys.config.FeeSchedule.buyRate(currentEx, quote)
    val netPrice = entryPrice * (1.0 + feeRate)
    val out = agu.analys.bridge.AgentSignalOutput(
        ts = System.currentTimeMillis(),
        exchange = currentEx.lowercase(),
        symbol = symbol,
        quote = quote,
        mode = mode,
        action = action,
        confidence = confidence.toFloat(),
        price = entryPrice,
        tp1 = if (tp1 > 0) tp1 else null,
        tp2 = if (tp2 > 0) tp2 else null,
        sl = if (stopLoss > 0) stopLoss else null,
        reason = reasoning,
        technical = agu.analys.bridge.AgentSignalBreakdown.build(
            orderbookPressure = orderbookPressure,
            mtf = mtf,
            scalpingSetup = scalpingSetup,
            scalpingRegime = scalpingRegime
        ),
        feeTruth = agu.analys.bridge.FeeTruthInfo(
            feeRate = feeRate,
            netPrice = netPrice,
            exchange = currentEx.lowercase()
        )
    )
    bridge.emitSignal(out)
}
