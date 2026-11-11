package agu.analys.viewmodel

import agu.analys.database.AppDatabase
import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.database.TradeHistoryRecordEntity
import agu.analys.service.SpotOrderResult
import agu.analys.service.SpotBalances
import agu.analys.service.TokocryptoTradeApi
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber
import kotlin.math.min

class RealTradeCoordinator(
    private val scope: CoroutineScope,
    private val prefs: AppPreferences,
    private val getLatestTick: (String) -> agu.analys.model.MarketTick?,
    private val onBalanceAndAvgUpdated: ((balances: Map<String, Double>, avgPrices: Map<String, Double>) -> Unit)? = null,
    private val onRealTradeExecuted: ((pair: String, type: String, price: Double, quantity: Double, tp1: Double, tp2: Double) -> Unit)? = null
) {
    private val MAX_HISTORY_ASSETS = 15
    private val INTER_REQUEST_DELAY_MS = 1500L
    private val REFRESH_COOLDOWN_MS = 30000L

    private val securityManager = RealTradeSecurityManager(scope, prefs)
    private val executor = RealTradeExecutor(
        getLatestTick = getLatestTick,
        scope = scope,
        prefs = prefs,
        onStatusUpdate = { _realTradeStatus.value = it },
        onRateLimit = { markRateLimited(it) },
        isRateLimited = { isRateLimitedNow() },
        isPinSessionValid = { securityManager.isPinSessionValid() },
        refreshBalance = { 
            lastFetchTimeMs = 0L
            fetchRealBalance()
        },
        onRealTradeSuccess = { pair, type, price, qty, tp1, tp2 ->
            _realSpotBalance.value = prefs.getSavedRealBalance(prefs.marketDataSource.name)
            _realAvgBuyPrices.value = prefs.getSavedRealAvgBuyPrices(prefs.marketDataSource.name)
            onRealTradeExecuted?.invoke(pair, type, price, qty, tp1, tp2)
        }
    )

    private val _realSpotBalance = MutableStateFlow<Map<String, Double>>(prefs.getSavedRealBalance(prefs.marketDataSource.name))
    val realSpotBalance: StateFlow<Map<String, Double>> = _realSpotBalance.asStateFlow()

    private val _realFreeBalance = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realFreeBalance: StateFlow<Map<String, Double>> = _realFreeBalance.asStateFlow()

    private val _realLockedBalance = MutableStateFlow<Map<String, Double>>(emptyMap())
    val realLockedBalance: StateFlow<Map<String, Double>> = _realLockedBalance.asStateFlow()

    private val _realAvgBuyPrices = MutableStateFlow<Map<String, Double>>(prefs.getSavedRealAvgBuyPrices(prefs.marketDataSource.name))
    val realAvgBuyPrices: StateFlow<Map<String, Double>> = _realAvgBuyPrices.asStateFlow()

    private val _realAvgBuyPartial = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val realAvgBuyPartial: StateFlow<Map<String, Boolean>> = _realAvgBuyPartial.asStateFlow()

    private val _realTradeStatus = MutableStateFlow("Siap (${prefs.marketDataSource.label} API)")
    val realTradeStatus: StateFlow<String> = _realTradeStatus.asStateFlow()

    private val _isFetchingRealBalance = MutableStateFlow(false)
    val isFetchingRealBalance: StateFlow<Boolean> = _isFetchingRealBalance.asStateFlow()

    private var lastFetchTimeMs = 0L
    private var rateLimitedUntilMs = 0L

    init {
        scope.launch(Dispatchers.IO) {
            if (prefs.hasTokocryptoCredentials()) {
                fetchRealBalance(force = true)
            }
        }
    }

    /**
     * Saldo real sesuai mata uang kuotasi pair yang sedang diperdagangkan.
     * - Pair USDT (BTCUSDT, ETHUSDT, ...) -> saldo USDT di exchange.
     * - Pair IDR  (BTCIDR, ETHIDR, ...)   -> saldo IDR di exchange.
     *
     * Mengembalikan 0.0 bila aset tersebut tidak ada di akun.
     */
    fun realBalanceForQuote(quoteAsset: String): Double {
        val map = _realSpotBalance.value
        return balanceOfQuote(map, quoteAsset)
    }

    /** Saldo real bebas (free) untuk kuotasi tertentu. */
    fun realFreeBalanceForQuote(quoteAsset: String): Double {
        val map = if (_realFreeBalance.value.isNotEmpty()) _realFreeBalance.value else _realSpotBalance.value
        return balanceOfQuote(map, quoteAsset)
    }

    /** Saldo real terkunci (locked) untuk kuotasi tertentu. */
    fun realLockedBalanceForQuote(quoteAsset: String): Double {
        val map = _realLockedBalance.value
        return balanceOfQuote(map, quoteAsset)
    }

    private fun balanceOfQuote(map: Map<String, Double>, quoteAsset: String): Double {
        val effectiveMap = if (map.isNotEmpty()) map else prefs.getSavedRealBalance(prefs.marketDataSource.name)
        if (effectiveMap.isEmpty()) return 0.0
        val q = quoteAsset.trim().uppercase()
        val isUsdt = q == "USDT" || q == "USD" || q == "USDC" || q == "BUSD"
        val keys = if (isUsdt) listOf("usdt", "USDT", "usd", "USD", "usdc", "USDC", "busd", "BUSD") else listOf("idr", "IDR", "idrt", "IDRT", "bidr", "BIDR")
        for (k in keys) {
            val v = effectiveMap[k]
            if (v != null && v > 0.0) return v
        }
        // Fallback: cari tidak case-sensitive
        for ((k, v) in effectiveMap) {
            if (k.equals(quoteAsset, ignoreCase = true) && v > 0.0) return v
        }
        return 0.0
    }

    fun updateAvgBuyPrice(coin: String, newAvgPrice: Double) {
        val asset = baseFromPair(coin)
        val current = _realAvgBuyPrices.value.toMutableMap()
        current[asset.lowercase()] = newAvgPrice
        current[asset.uppercase()] = newAvgPrice
        current["${asset.lowercase()}idr"] = newAvgPrice
        current["${asset.uppercase()}IDR"] = newAvgPrice
        current["${asset.lowercase()}usdt"] = newAvgPrice
        current["${asset.uppercase()}USDT"] = newAvgPrice
        _realAvgBuyPrices.value = current
        prefs.saveRealAvgBuyPrices(current, prefs.marketDataSource.name)
    }

    // Security delegation
    val isRealBuyEnabled: StateFlow<Boolean> = securityManager.isRealBuyEnabled
    val publicIp: StateFlow<String?> = securityManager.publicIp
    val isPinRequired: StateFlow<Boolean> = securityManager.isPinRequired
    val isPinUnlocked: StateFlow<Boolean> = securityManager.isPinUnlocked
    fun checkPublicIp() = securityManager.checkPublicIp()
    fun verifyPin(pin: String) = securityManager.verifyPin(pin)
    fun lockPin() = securityManager.lockPin()
    fun setRealBuyMode(enabled: Boolean, pin: String? = null) = securityManager.setRealBuyMode(enabled, pin)

    // Executor delegation
    fun executeCancelRealOrder(symbol: String, orderId: String, onResult: (Boolean, String) -> Unit) =
        executor.executeCancelOrder(symbol, orderId, onResult)
    fun executeRealTrade(p: String, t: String, pr: Double, a: Double, tp1: Double, tp2: Double, exchange: String? = null, cb: (Boolean, String) -> Unit) =
        executor.executeTrade(p, t, pr, a, tp1, tp2, exchange, cb)

    /** Umur maksimal record fill yang masih dianggap milik order terakhir (2 menit). */
    private val FILL_STALE_MS = 120_000L

    /**
     * Record order riil terakhir untuk (pair, side) yang cocok dan belum basi
     * (dipakai Agent Bridge supaya respons order memuat executedQty/status nyata,
     * bukan hasil pembagian nominal — lihat 2026-11-06). `null` bila tidak ada.
     */
    fun lastRealFillFor(pairNorm: String, side: String): RealTradeExecutor.RealFillRecord? =
        executor.lastFill?.takeIf {
            System.currentTimeMillis() - it.ts <= FILL_STALE_MS &&
                it.pair.equals(pairNorm, ignoreCase = true) &&
                it.side.equals(side, ignoreCase = true)
        }
    fun executeRealSellOrders(
        pair: String,
        totalQuantity: Double,
        marketPrice: Double,
        isAutoTpEnabled: Boolean,
        tp1Price: Double,
        tp1Percent: Double,
        tp2Price: Double,
        tp2Percent: Double,
        onResult: (Boolean, String) -> Unit
    ) = executor.executeRealSellOrders(pair, totalQuantity, marketPrice, isAutoTpEnabled, tp1Price, tp1Percent, tp2Price, tp2Percent, onResult)
    fun executeRealAutoSellOnServer(p: String, tp1P: Double, tp1Pct: Double, tp2P: Double, tp2Pct: Double, cb: (Boolean, String) -> Unit) =
        executor.executeAutoSellOnServer(p, tp1P, tp1Pct, tp2P, tp2Pct, cb)

    private fun looksLikeRateLimit(msg: String): Boolean {
        return msg.contains("429") || msg.lowercase().contains("rate limit") || msg.lowercase().contains("too many requests")
    }

    private fun markRateLimited(msg: String) {
        rateLimitedUntilMs = System.currentTimeMillis() + 120_000L
        _realTradeStatus.value = "Rate-limit terdeteksi. $msg. Menunggu 2 menit."
    }

    private fun isRateLimitedNow(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs

    private fun baseFromPair(pair: String): String {
        val s = pair.lowercase().replace("_", "")
        return when {
            s.endsWith("idr") -> s.removeSuffix("idr")
            s.endsWith("usdt") -> s.removeSuffix("usdt")
            else -> s
        }
    }

    private fun buildHistoryCandidates(balance: Map<String, Double>): List<Pair<String, Double>> {
        val quoteKeys = setOf("idr", "idrt", "bidr", "usdt", "usdc", "busd", "usd")
        val active = balance.filter { it.key.lowercase() !in quoteKeys && it.value > 0.00000001 }
        prefs.rememberHistoryBases(active.keys)
        val ordered = linkedSetOf<String>()
        active.keys.forEach { ordered.add(it.lowercase()) }
        prefs.getRecentHistoryBases().forEach { ordered.add(it.lowercase()) }
        prefs.getWatchlist().forEach { sym ->
            val b = baseFromPair(sym)
            if (b.isNotBlank()) ordered.add(b.lowercase())
        }
        return ordered.take(MAX_HISTORY_ASSETS).map { base ->
            base to (balance[base] ?: balance[base.lowercase()] ?: balance[base.uppercase()] ?: 0.0)
        }
    }

    fun fetchRealBalance(force: Boolean = false) {
        val apiKey = prefs.tokocryptoApiKey
        val secretKey = prefs.tokocryptoSecretKey
        val sourceLabel = prefs.marketDataSource.label

        if (apiKey.isBlank() || secretKey.isBlank()) {
            _realTradeStatus.value = "Kredensial API $sourceLabel belum diisi."
            return
        }
        if (_isFetchingRealBalance.value) return
        val now = System.currentTimeMillis()
        if (isRateLimitedNow()) {
            val waitSec = ((rateLimitedUntilMs - now) / 1000L).coerceAtLeast(1)
            _realTradeStatus.value = "Rate-limit aktif. Tunggu ~${waitSec}s lagi."
            return
        }
        if (!force && now - lastFetchTimeMs < REFRESH_COOLDOWN_MS && _realSpotBalance.value.isNotEmpty()) {
            _realTradeStatus.value = "Cache aktif (cooldown ${REFRESH_COOLDOWN_MS / 1000}s)."
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isFetchingRealBalance.value = true
            lastFetchTimeMs = now
            _realTradeStatus.value = "Memperbarui saldo $sourceLabel..."
            val (balances, message) = TokocryptoTradeApi.getAccount(apiKey, secretKey)
            if (looksLikeRateLimit(message)) {
                markRateLimited(message); _isFetchingRealBalance.value = false; return@launch
            }
            _realTradeStatus.value = message
            if (balances != null) {
                _realSpotBalance.value = balances.total
                _realFreeBalance.value = balances.free
                _realLockedBalance.value = balances.locked
                prefs.saveRealBalance(balances.total, prefs.marketDataSource.name)
                onBalanceAndAvgUpdated?.invoke(balances.total, _realAvgBuyPrices.value)
                delay(INTER_REQUEST_DELAY_MS)
                if (fetchRealOpenOrdersSafe(apiKey, secretKey, balances)) {
                    delay(INTER_REQUEST_DELAY_MS)
                    fetchTradesAndAvgSafe(apiKey, secretKey, balances.total)
                }
            }
            _isFetchingRealBalance.value = false
        }
    }

    private suspend fun fetchRealOpenOrdersSafe(
        apiKey: String,
        secretKey: String,
        balances: SpotBalances
    ): Boolean {
        return try {
            val db = AppDatabase.getInstance().realTradeDao()
            val exchangeName = "TOKOCRYPTO"
            val entityMap = mutableMapOf<String, RealOpenOrderEntity>()
            var anyOk = false
            var lastError = ""

            // Daftar semua order aktif (satu panggilan). Bila gagal, cek per pair.
            val (okAll, rawAll) = TokocryptoTradeApi.openOrders(apiKey, secretKey)
            if (!okAll && looksLikeRateLimit(rawAll)) { markRateLimited(rawAll); return false }
            if (okAll) { anyOk = true; parseOrdersToMap(rawAll, entityMap, exchangeName) } else lastError = rawAll

            if (!anyOk) {
                val candidates = linkedSetOf<String>()
                val lockedQuoteKeys = setOf("idr", "idrt", "bidr", "usdt", "usdc", "busd", "usd")
                balances.locked.filter { it.key.lowercase() !in lockedQuoteKeys && it.value > 0.0 }.keys.forEach { candidates.add(it.lowercase()) }
                prefs.getRecentHistoryBases().forEach { candidates.add(it.lowercase()) }
                prefs.getWatchlist().forEach { candidates.add(baseFromPair(it).lowercase()) }

                for (base in candidates.take(15)) {
                    if (base.isBlank()) continue
                    delay(300)
                    // Tokocrypto mendukung USDT + IDR: cek dua-duanya agar order IDR tidak hilang.
                    val symUsdt = "${base}_USDT".uppercase()
                    val symIdr = "${base}_IDR".uppercase()
                    var symOk = false
                    val (okUsdt, rawUsdt) = TokocryptoTradeApi.openOrders(apiKey, secretKey, symUsdt)
                    if (!okUsdt && looksLikeRateLimit(rawUsdt)) { markRateLimited(rawUsdt); return false }
                    if (okUsdt) { symOk = true; parseOrdersToMap(rawUsdt, entityMap, exchangeName) }
                    delay(300)
                    val (okIdr, rawIdr) = TokocryptoTradeApi.openOrders(apiKey, secretKey, symIdr)
                    if (!okIdr && looksLikeRateLimit(rawIdr)) { markRateLimited(rawIdr); return false }
                    if (okIdr) { symOk = true; parseOrdersToMap(rawIdr, entityMap, exchangeName) }
                    if (symOk) { anyOk = true } else lastError = rawIdr.ifBlank { rawUsdt }
                }
            }

            // Jika tidak ada satu pun pengambilan yang berhasil, data lama DIPERTAHANKAN (jangan dikosongkan).
            if (!anyOk) {
                _realTradeStatus.value = "Antrean order $exchangeName belum terbaca: ${lastError.take(160)}"
                return true
            }
            db.clearOpenOrdersByExchange(exchangeName)
            if (entityMap.isNotEmpty()) {
                db.insertOpenOrders(entityMap.values.toList())
            }
            true
        } catch (e: Exception) {
            _realTradeStatus.value = "Gagal open orders: ${e.localizedMessage}"
            true
        }
    }

    private fun parseOrdersToMap(raw: String, map: MutableMap<String, RealOpenOrderEntity>, exchange: String) {
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val oId = obj.optString("orderId", "").ifBlank { obj.optString("id", "") }
                if (oId.isNotBlank()) {
                    val sideRaw = obj.optString("side", "")
                    val side = if (sideRaw == "0" || sideRaw.equals("BUY", true)) "BUY" else "SELL"
                    val typeRaw = obj.optString("type", "LIMIT")
                    val type = if (typeRaw == "1" || typeRaw.equals("LIMIT", true)) "LIMIT" else if (typeRaw == "2" || typeRaw.equals("MARKET", true)) "MARKET" else typeRaw.uppercase()
                    
                    map[oId] = RealOpenOrderEntity(
                        orderId = oId,
                        symbol = obj.optString("symbol", "").lowercase(),
                        side = side,
                        type = type,
                        price = obj.optString("price", "0").toDoubleOrNull() ?: obj.optDouble("price", 0.0),
                        quantity = obj.optString("origQty", "0").toDoubleOrNull() ?: obj.optDouble("origQty", 0.0),
                        executedQty = obj.optString("executedQty", "0").toDoubleOrNull() ?: obj.optDouble("executedQty", 0.0),
                        status = obj.optString("status", "OPEN").uppercase(),
                        time = obj.optLong("time", 0L).takeIf { it > 0L } ?: obj.optLong("createTime", System.currentTimeMillis()),
                        exchange = exchange
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun fetchTradesAndAvgSafe(
        apiKey: String,
        secretKey: String,
        balance: Map<String, Double>
    ) {
        val candidates = buildHistoryCandidates(balance)
        val exchangeName = "TOKOCRYPTO"
        if (candidates.isEmpty()) {
            _realTradeStatus.value = "Saldo diperbarui. Tidak ada pair untuk histori."
            onBalanceAndAvgUpdated?.invoke(balance, _realAvgBuyPrices.value)
            return
        }
        val db = AppDatabase.getInstance().realTradeDao()
        val accumulatedEntities = mutableListOf<RealTradeEntity>()
        val newAvg = _realAvgBuyPrices.value.toMutableMap()
        val newPartial = _realAvgBuyPartial.value.toMutableMap()
        var rateLimited = false
        var fetchErrors = 0
        var assetsWithTrades = 0

        for ((index, entry) in candidates.withIndex()) {
            if (index > 0) delay(INTER_REQUEST_DELAY_MS)
            val asset = entry.first
            val currentQty = entry.second

            // Tokocrypto mendukung USDT + IDR: ambil dua-duanya agar histori IDR tidak hilang.
            // Avg dihitung per kuotasi (tidak dicampur karena beda mata uang).
            var accBuyQtyUsdt = 0.0; var accBuyCostUsdt = 0.0
            var accBuyQtyIdr = 0.0; var accBuyCostIdr = 0.0
            var anyTokoTrades = false
            for ((tokoSymbol, quoteSuffix, avgSuffix) in listOf(
                Triple("${asset}_USDT".uppercase(), "USDT", "usdt"),
                Triple("${asset}_IDR".uppercase(), "IDR", "idr")
            )) {
                if (index > 0 || quoteSuffix == "IDR") delay(300)
                val (ok, raw) = try {
                    TokocryptoTradeApi.myTrades(apiKey, secretKey, tokoSymbol, limit = 100)
                } catch (e: Exception) { fetchErrors++; continue }
                if (!ok) {
                    if (looksLikeRateLimit(raw)) { markRateLimited(raw); rateLimited = true; break }
                    fetchErrors++; continue
                }
                val trades = TokocryptoTradeApi.parseTradesList(raw)
                if (trades.isEmpty()) continue
                anyTokoTrades = true
                for (trade in trades) {
                    val id = TokocryptoTradeApi.tradeIdOf(trade)
                    val isBuyer = TokocryptoTradeApi.isBuyerOf(trade)
                    val tP = TokocryptoTradeApi.tradePriceOf(trade)
                    val tQ = TokocryptoTradeApi.tradeQtyOf(trade)
                    if (id.isBlank() || tP <= 0.0 || tQ <= 0.0) continue
                    accumulatedEntities.add(
                        RealTradeEntity(
                            id = id,
                            symbol = "${asset.uppercase()}$quoteSuffix",
                            price = tP,
                            qty = tQ,
                            amount = tP * tQ,
                            time = TokocryptoTradeApi.tradeTimeMs(trade),
                            side = if (isBuyer) "BUY" else "SELL",
                            isBuyer = isBuyer,
                            exchange = exchangeName
                        )
                    )
                    if (isBuyer && currentQty > 0.0) {
                        if (quoteSuffix == "USDT" && accBuyQtyUsdt < currentQty) {
                            val qtyToUse = minOf(tQ, currentQty - accBuyQtyUsdt)
                            accBuyQtyUsdt += qtyToUse; accBuyCostUsdt += qtyToUse * tP
                        } else if (quoteSuffix == "IDR" && accBuyQtyIdr < currentQty) {
                            val qtyToUse = minOf(tQ, currentQty - accBuyQtyIdr)
                            accBuyQtyIdr += qtyToUse; accBuyCostIdr += qtyToUse * tP
                        }
                    }
                }
            }
            if (!anyTokoTrades && !rateLimited) continue
            if (anyTokoTrades) {
                prefs.rememberHistoryBase(asset)
                assetsWithTrades++
            }
            if (accBuyQtyUsdt > 0.0) {
                val avgP = accBuyCostUsdt / accBuyQtyUsdt
                newAvg["${asset.lowercase()}usdt"] = avgP
                newAvg["${asset.uppercase()}USDT"] = avgP
                // Kompatibilitas: avg bare-asset default ke USDT bila ada.
                newAvg[asset.lowercase()] = avgP
                newAvg[asset.uppercase()] = avgP
                newPartial[asset] = accBuyQtyUsdt + 1e-12 < currentQty
            }
            if (accBuyQtyIdr > 0.0) {
                val avgP = accBuyCostIdr / accBuyQtyIdr
                newAvg["${asset.lowercase()}idr"] = avgP
                newAvg["${asset.uppercase()}IDR"] = avgP
                // Bila tidak ada avg USDT, bare-asset pakai IDR.
                if (accBuyQtyUsdt <= 0.0) {
                    newAvg[asset.lowercase()] = avgP
                    newAvg[asset.uppercase()] = avgP
                    newPartial[asset] = accBuyQtyIdr + 1e-12 < currentQty
                }
            }
        }

        if (accumulatedEntities.isNotEmpty()) {
            // Gabungkan baris BUY lokal (id "local_...", dicatat saat order dikirim) dengan trade server yang sama.
            // Dicocokkan satu-satu: pair sama, BUY, selisih waktu terdekat dalam 24 jam. Snapshot sinyal ikut dipindah.
            val mergeWindowMs = 24L * 60L * 60L * 1000L
            val localRows = db.getLocalTradesByExchange(exchangeName).toMutableList()
            val consumedLocalIds = mutableListOf<String>()
            for (i in accumulatedEntities.indices) {
                val ent = accumulatedEntities[i]
                if (!ent.isBuyer || localRows.isEmpty()) continue
                val entNorm = ent.symbol.uppercase().replace("_", "")
                val match = localRows
                    .filter { loc ->
                        loc.isBuyer &&
                            loc.symbol.uppercase().replace("_", "") == entNorm &&
                            kotlin.math.abs(loc.time - ent.time) < mergeWindowMs
                    }
                    .minByOrNull { kotlin.math.abs(it.time - ent.time) } ?: continue
                localRows.remove(match)
                consumedLocalIds.add(match.id)
                accumulatedEntities[i] = ent.copy(
                    strategyMode = if (match.strategyMode != "MANUAL") match.strategyMode else ent.strategyMode,
                    signalSnapshotJson = match.signalSnapshotJson ?: ent.signalSnapshotJson,
                    entryPrice = ent.entryPrice ?: match.entryPrice,
                    entryTimestamp = ent.entryTimestamp ?: match.entryTimestamp,
                    isTrailingUsed = ent.isTrailingUsed || match.isTrailingUsed
                )
            }

            val enrichedEntities = mutableListOf<RealTradeEntity>()
            val historyDao = AppDatabase.getInstance().tradeHistoryRecordDao()
            for (ent in accumulatedEntities) {
                val existing = db.getTradesBySymbolAndExchange(ent.symbol, exchangeName).firstOrNull { it.id == ent.id }
                if (existing?.signalSnapshotJson != null) {
                    enrichedEntities.add(existing)
                    continue
                }
                val norm = ent.symbol.uppercase().replace("_", "")
                val alt = "${norm}USDT"
                val records: List<TradeHistoryRecordEntity> = historyDao.getRecordsForSymbolAndExchange(norm, alt, exchangeName)
                val matched: TradeHistoryRecordEntity? = records.firstOrNull { rec: TradeHistoryRecordEntity ->
                    val timeDiff = java.lang.Math.abs(rec.buyTime - ent.time)
                    val sellTimeDiff = java.lang.Math.abs((rec.sellTime ?: 0L) - ent.time)
                    (ent.isBuyer && (rec.buyPrice == ent.price || timeDiff < 600000L)) ||
                    (!ent.isBuyer && (rec.sellPrice == ent.price || sellTimeDiff < 600000L))
                } ?: records.firstOrNull()

                if (matched != null) {
                    enrichedEntities.add(
                        ent.copy(
                            strategyMode = if (ent.strategyMode != "MANUAL") ent.strategyMode else matched.strategyMode,
                            holdingDurationMs = ent.holdingDurationMs ?: matched.holdingDurationMs,
                            entryPrice = ent.entryPrice ?: matched.buyPrice,
                            entryTimestamp = ent.entryTimestamp ?: matched.buyTime,
                            pnlIdr = ent.pnlIdr ?: matched.pnlIdr,
                            pnlPercent = ent.pnlPercent ?: matched.pnlPercent,
                            isTrailingUsed = ent.isTrailingUsed || matched.isTrailingUsed,
                            trailingLockPrice = ent.trailingLockPrice ?: matched.trailingLockPrice,
                            signalSnapshotJson = ent.signalSnapshotJson ?: matched.signalSnapshotJson
                        )
                    )
                } else {
                    enrichedEntities.add(ent)
                }
            }
            db.insertTrades(enrichedEntities)
            if (consumedLocalIds.isNotEmpty()) db.deleteTradesByIds(consumedLocalIds)
        }
        _realAvgBuyPrices.value = newAvg
        _realAvgBuyPartial.value = newPartial
        prefs.saveRealAvgBuyPrices(newAvg, prefs.marketDataSource.name)
        onBalanceAndAvgUpdated?.invoke(balance, newAvg)
        if (!rateLimited) _realTradeStatus.value = "Saldo diperbarui. ${accumulatedEntities.size} trade dari $assetsWithTrades pair."
    }
}