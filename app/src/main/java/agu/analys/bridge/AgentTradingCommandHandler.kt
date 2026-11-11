package agu.analys.bridge

import android.content.Context
import agu.analys.config.MarketDataSource
import agu.analys.database.AppDatabase
import agu.analys.model.TradingPair
import agu.analys.service.TokocryptoTradeApi
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderStatus
import agu.analys.trading.SimulationOrderType
import agu.analys.util.AppLogManager
import agu.analys.util.AppPreferences
import agu.analys.viewmodel.RealTradeCoordinator
import agu.analys.viewmodel.SimulationCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pengendali eksekusi perintah trading dari Agent Bridge (Agent-Assisted Trading).
 *
 * Mengimplementasikan pengamanan terpusat:
 * 1. Validasi Mode Real (`isRealBuyEnabled`) dan sesi PIN (`isPinUnlocked`).
 * 2. Validasi bursa dan kuotasi resmi (Tokocrypto: hanya USDT dan IDR).
 * 3. Anti double-order (Atomic lock `tradeInFlight`).
 * 4. Pendelegasian ke `RealTradeCoordinator` (riil) atau `SimulationCoordinator` (simulasi).
 */
object AgentTradingCommandHandler {

    @Volatile
    private var realCoordinator: RealTradeCoordinator? = null

    @Volatile
    private var simCoordinator: SimulationCoordinator? = null

    @Volatile
    private var latestPriceProvider: ((String) -> Double?)? = null

    private val tradeInFlight = AtomicBoolean(false)

    /**
     * Status order riil untuk respons bridge (2026-11-06): `FILLED`/`PARTIALLY_FILLED`
     * dipilih dari record fill nyata, bukan mengklaim `FILLED` hanya karena callback
     * sukses. Record hilang (jalur lama / kredensial belum sempat) mempertahankan
     * perilaku lama supaya pemanggil tidak perlu tahu perubahannya.
     */
    private fun realFillStatus(
        success: Boolean,
        fill: agu.analys.viewmodel.RealTradeExecutor.RealFillRecord?
    ): String = when {
        !success -> "REJECTED"
        fill == null -> "FILLED"
        fill.executedQty <= 0.0 -> "NEW"
        fill.status.isNotBlank() -> fill.status.uppercase()
        fill.executedQty >= fill.requestedQty * 0.99 -> "FILLED"
        else -> "PARTIALLY_FILLED"
    }

    /**
     * Didaftarkan oleh [agu.analys.viewmodel.TradingViewModel] saat siklus hidup aktif.
     */
    fun register(
        real: RealTradeCoordinator,
        sim: SimulationCoordinator,
        priceProvider: (String) -> Double?
    ) {
        realCoordinator = real
        simCoordinator = sim
        latestPriceProvider = priceProvider
        AppLogManager.info("AgentTrading", "Coordinators berhasil didaftarkan ke AgentTradingCommandHandler")
    }

    fun unregister() {
        realCoordinator = null
        simCoordinator = null
        latestPriceProvider = null
        AppLogManager.info("AgentTrading", "Coordinators di-unregister dari AgentTradingCommandHandler")
    }

    /**
     * Membangun snapshot portofolio dari state coordinator aktif (real atau simulasi).
     */
    fun buildPortfolioSnapshot(context: Context): AgentPortfolioOutput? {
        val prefs = AppPreferences(context)
        val ex = prefs.marketDataSource.name
        val isToko = prefs.marketDataSource == MarketDataSource.TOKOCRYPTO
        val rate = agu.analys.util.ExchangeRateManager.usdtIdrRate.value
        val effectiveRate = if (rate > 0.0) rate else 16000.0

        val real = realCoordinator
        val isRealBuy = real?.isRealBuyEnabled?.value ?: false

        if (isRealBuy && real != null) {
            val totalBalances = real.realSpotBalance.value
            val freeBalances = real.realFreeBalance.value
            val lockedBalances = real.realLockedBalance.value
            val avgBuyPrices = real.realAvgBuyPrices.value

            val idrFree = freeBalances["idr"] ?: freeBalances["IDR"] ?: totalBalances["idr"] ?: totalBalances["IDR"] ?: 0.0
            val usdtFree = freeBalances["usdt"] ?: freeBalances["USDT"] ?: totalBalances["usdt"] ?: totalBalances["USDT"] ?: 0.0

            val assetList = mutableListOf<AgentAsset>()
            var cryptoValIdr = 0.0
            var cryptoValUsdt = 0.0

            val allCoins = (totalBalances.keys + freeBalances.keys).distinct()
            for (key in allCoins) {
                val k = key.lowercase()
                if (k == "idr" || k == "usdt" || k == "usd" || k == "usdc" || k == "busd" || k == "bidr") continue
                val totalQty = totalBalances[key] ?: totalBalances[key.uppercase()] ?: 0.0
                val freeQty = freeBalances[key] ?: freeBalances[key.uppercase()] ?: totalQty
                val lockedQty = lockedBalances[key] ?: lockedBalances[key.uppercase()] ?: (totalQty - freeQty).coerceAtLeast(0.0)
                if (totalQty <= 0.00000001) continue

                val coinUpper = key.uppercase()
                val symbolUsdt = "${coinUpper}USDT"
                val symbolIdr = "${coinUpper}IDR"
                val isUsdt = isToko || resolvePrice(symbolUsdt, null) > 0.0
                val primarySymbol = if (isUsdt) symbolUsdt else symbolIdr
                val curPrice = resolvePrice(primarySymbol, null)
                val avgPrice = avgBuyPrices[coinUpper] ?: avgBuyPrices[key.lowercase()] ?: 0.0
                val effPrice = if (curPrice > 0.0) curPrice else avgPrice

                val pnlPct = if (avgPrice > 0.0 && effPrice > 0.0) ((effPrice - avgPrice) / avgPrice) * 100.0 else 0.0
                val pnlVal = if (avgPrice > 0.0 && effPrice > 0.0) (effPrice - avgPrice) * totalQty else 0.0

                if (isUsdt) {
                    val valUsdt = totalQty * effPrice
                    cryptoValUsdt += valUsdt
                    cryptoValIdr += (valUsdt * effectiveRate)
                } else {
                    val valIdr = totalQty * effPrice
                    cryptoValIdr += valIdr
                    cryptoValUsdt += if (effectiveRate > 0.0) (valIdr / effectiveRate) else 0.0
                }

                assetList.add(
                    AgentAsset(
                        symbol = primarySymbol,
                        free = freeQty,
                        locked = lockedQty,
                        avgBuyPrice = avgPrice,
                        currentPrice = effPrice,
                        pnlPercent = pnlPct,
                        pnlValue = pnlVal
                    )
                )
            }

            // Tambahkan kas USDT dan IDR sebagai aset terdaftar
            if (usdtFree > 0.0) {
                assetList.add(
                    AgentAsset(
                        symbol = "USDT",
                        free = usdtFree,
                        locked = lockedBalances["usdt"] ?: lockedBalances["USDT"] ?: 0.0,
                        avgBuyPrice = 1.0,
                        currentPrice = 1.0,
                        pnlPercent = 0.0,
                        pnlValue = 0.0
                    )
                )
            }
            if (idrFree > 0.0) {
                assetList.add(
                    AgentAsset(
                        symbol = "IDR",
                        free = idrFree,
                        locked = lockedBalances["idr"] ?: lockedBalances["IDR"] ?: 0.0,
                        avgBuyPrice = 1.0,
                        currentPrice = 1.0,
                        pnlPercent = 0.0,
                        pnlValue = 0.0
                    )
                )
            }

            val totalIdr = idrFree + (usdtFree * effectiveRate) + cryptoValIdr
            val totalUsdt = usdtFree + (if (effectiveRate > 0.0) idrFree / effectiveRate else 0.0) + cryptoValUsdt

            return AgentPortfolioOutput(
                ts = System.currentTimeMillis(),
                exchange = ex,
                totalIdrValue = totalIdr,
                totalUsdtValue = totalUsdt,
                assets = assetList
            )
        }

        // Fallback ke portofolio simulasi (paper trading) jika mode riil mati atau kosong
        val sim = simCoordinator
        if (sim != null) {
            val wallet = sim.wallet.value
            val assetList = mutableListOf<AgentAsset>()
            var cryptoValIdr = 0.0
            var cryptoValUsdt = 0.0

            for ((coin, totalQty) in wallet.coinBalances) {
                if (totalQty <= 0.00000001) continue
                val coinUpper = coin.uppercase()
                val lockedQty = wallet.lockedCoinBalances[coin] ?: 0.0
                val freeQty = (totalQty - lockedQty).coerceAtLeast(0.0)
                val isUsdt = wallet.isUsdtPosition(coinUpper)
                val primarySymbol = if (isUsdt) "${coinUpper}USDT" else "${coinUpper}IDR"
                val curPrice = resolvePrice(primarySymbol, null)
                val avgPrice = wallet.avgBuyPrices[coinUpper] ?: 0.0
                val effPrice = if (curPrice > 0.0) curPrice else avgPrice

                val pnlPct = if (avgPrice > 0.0 && effPrice > 0.0) ((effPrice - avgPrice) / avgPrice) * 100.0 else 0.0
                val pnlVal = if (avgPrice > 0.0 && effPrice > 0.0) (effPrice - avgPrice) * totalQty else 0.0

                if (isUsdt) {
                    val valUsdt = totalQty * effPrice
                    cryptoValUsdt += valUsdt
                    cryptoValIdr += (valUsdt * effectiveRate)
                } else {
                    val valIdr = totalQty * effPrice
                    cryptoValIdr += valIdr
                    cryptoValUsdt += if (effectiveRate > 0.0) (valIdr / effectiveRate) else 0.0
                }

                assetList.add(
                    AgentAsset(
                        symbol = primarySymbol,
                        free = freeQty,
                        locked = lockedQty,
                        avgBuyPrice = avgPrice,
                        currentPrice = effPrice,
                        pnlPercent = pnlPct,
                        pnlValue = pnlVal
                    )
                )
            }

            if (wallet.usdtBalance > 0.0) {
                assetList.add(
                    AgentAsset(
                        symbol = "USDT",
                        free = wallet.getAvailableUsdt(),
                        locked = wallet.lockedUsdt,
                        avgBuyPrice = 1.0,
                        currentPrice = 1.0,
                        pnlPercent = 0.0,
                        pnlValue = 0.0
                    )
                )
            }
            if (wallet.idrBalance > 0.0) {
                assetList.add(
                    AgentAsset(
                        symbol = "IDR",
                        free = wallet.getAvailableIdr(),
                        locked = wallet.lockedIdr,
                        avgBuyPrice = 1.0,
                        currentPrice = 1.0,
                        pnlPercent = 0.0,
                        pnlValue = 0.0
                    )
                )
            }

            val totalIdr = wallet.idrBalance + (wallet.usdtBalance * effectiveRate) + cryptoValIdr
            val totalUsdt = wallet.usdtBalance + (if (effectiveRate > 0.0) wallet.idrBalance / effectiveRate else 0.0) + cryptoValUsdt

            return AgentPortfolioOutput(
                ts = System.currentTimeMillis(),
                exchange = "$ex (SIMULASI)",
                totalIdrValue = totalIdr,
                totalUsdtValue = totalUsdt,
                assets = assetList
            )
        }

        return null
    }

    /**
     * Mempublikasikan snapshot portofolio ke berkas lokal dan EventBus (WebSocket/SSE).
     */
    fun publishPortfolioSnapshot(context: Context) {
        val snapshot = buildPortfolioSnapshot(context) ?: return
        AgentOutputBridge.getInstance(context).emitPortfolio(snapshot)
    }

    /**
     * Status kesiapan sistem trading bridge.
     */
    fun getTradingStatus(context: Context): AgentTradingStatusResponse {
        val prefs = AppPreferences(context)
        val real = realCoordinator
        val sim = simCoordinator
        val isRealBuy = real?.isRealBuyEnabled?.value ?: false
        val isPinOk = real?.isPinUnlocked?.value ?: false
        val free = real?.realFreeBalance?.value ?: emptyMap()
        val total = real?.realSpotBalance?.value ?: emptyMap()
        val ip = real?.publicIp?.value

        return AgentTradingStatusResponse(
            isBridgeRunning = AgentHttpServer.getInstance(context).isRunning,
            host = AgentHttpServer.HOST,
            port = AgentHttpServer.PORT,
            exchange = prefs.marketDataSource.name,
            isRealBuyEnabled = isRealBuy,
            isPinAuthenticated = isPinOk,
            isSimulationAvailable = sim != null,
            publicIp = ip,
            freeBalances = free,
            totalBalances = total,
            serverTime = System.currentTimeMillis()
        )
    }

    /**
     * Mengeksekusi order BELI.
     */
    suspend fun handleBuy(req: AgentTradeRequest, context: Context): AgentTradeResponse = withContext(Dispatchers.IO) {
        val prefs = AppPreferences(context)
        val (base, quote, normSymbol) = parseAndValidateSymbol(req.symbol)
            ?: return@withContext AgentTradeResponse(
                success = false,
                message = "Format simbol '${req.symbol}' tidak valid. Hanya kuotasi USDT dan IDR yang didukung.",
                symbol = req.symbol,
                side = "BUY",
                isSimulation = req.isSimulation
            )

        val price = resolvePrice(normSymbol, req.price)
        if (price <= 0.0) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "Harga pasar untuk $normSymbol belum tersedia. Sertakan parameter price secara eksplisit.",
                symbol = normSymbol,
                side = "BUY",
                isSimulation = req.isSimulation
            )
        }

        val quoteAmount = req.quoteAmount
        val quantity = when {
            quoteAmount != null && quoteAmount > 0.0 -> quoteAmount / price
            req.quantity != null && req.quantity > 0.0 -> req.quantity
            else -> 0.0
        }

        if (quantity <= 0.0) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "Nominal pembelian tidak valid. Tentukan quoteAmount (contoh: 15 USDT) atau quantity koin.",
                symbol = normSymbol,
                side = "BUY",
                price = price,
                isSimulation = req.isSimulation
            )
        }

        val effectiveQuoteAmount = if (quoteAmount != null && quoteAmount > 0.0) quoteAmount else (quantity * price)

        // --- MODE SIMULASI (PAPER TRADING) ---
        if (req.isSimulation) {
            return@withContext executeSimulationBuy(
                symbol = normSymbol,
                base = base,
                quote = quote,
                price = price,
                quantity = quantity,
                type = req.type
            )
        }

        // --- MODE TRANSAKSI RIIL ---
        val real = realCoordinator
            ?: return@withContext AgentTradeResponse(
                success = false,
                message = "Trading coordinator belum siap. Buka aplikasi nadi agentfeed terlebih dahulu.",
                symbol = normSymbol,
                side = "BUY",
                isSimulation = false
            )

        if (!real.isRealBuyEnabled.value) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "Mode Transaksi Riil dimatikan di aplikasi nadi agentfeed. Nyalakan Mode Real di Pengaturan atau gunakan isSimulation=true.",
                symbol = normSymbol,
                side = "BUY",
                isSimulation = false
            )
        }

        if (!real.isPinUnlocked.value) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "Sesi PIN keamanan terkunci atau kedaluwarsa. Masukkan PIN pada aplikasi nadi agentfeed terlebih dahulu.",
                symbol = normSymbol,
                side = "BUY",
                isSimulation = false
            )
        }

        if (AppPreferences.isKeyStoreRecoveryOccurred) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "Penyimpanan aman (KeyStore) sedang bermasalah. Order riil diblokir demi keamanan.",
                symbol = normSymbol,
                side = "BUY",
                isSimulation = false
            )
        }

        val apiKey = prefs.tokocryptoApiKey
        val secretKey = prefs.tokocryptoSecretKey
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "API Key / Secret Tokocrypto belum diatur di aplikasi nadi agentfeed.",
                symbol = normSymbol,
                side = "BUY",
                isSimulation = false
            )
        }

        if (!tradeInFlight.compareAndSet(false, true)) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "Order lain masih diproses. Tunggu sampai selesai.",
                symbol = normSymbol,
                side = "BUY",
                isSimulation = false
            )
        }

        try {
            AppLogManager.trade("AgentTrading", "Mengeksekusi BUY Riil: $normSymbol @ $price | Quote: $effectiveQuoteAmount | Qty: $quantity")
            val deferred = CompletableDeferred<Pair<Boolean, String>>()
            real.executeRealTrade(
                p = normSymbol,
                t = "buy",
                pr = price,
                a = effectiveQuoteAmount,
                tp1 = req.autoLimitTp1 ?: 0.0,
                tp2 = req.autoLimitTp2 ?: 0.0,
                exchange = req.exchange
            ) { ok, msg ->
                deferred.complete(ok to msg)
            }

            val (success, message) = withTimeoutOrNull(25_000L) { deferred.await() }
                ?: (false to "Waktu tunggu konfirmasi transaksi dari bursa habis (timeout).")

            // 2026-11-06 — respons memuat data order nyata (record fill dari executor),
            // bukan `executedQty` hasil pembagian nominal dan status `FILLED` karangan.
            val fill = real.lastRealFillFor(normSymbol, "buy")
            AgentTradeResponse(
                success = success,
                message = message,
                orderId = fill?.orderId?.takeIf { it.isNotBlank() },
                clientOrderId = fill?.clientOrderId?.takeIf { it.isNotBlank() },
                exchange = req.exchange,
                symbol = normSymbol,
                side = "BUY",
                price = fill?.requestedPrice ?: price,
                quantity = fill?.requestedQty?.takeIf { it > 0.0 } ?: quantity,
                status = realFillStatus(success, fill),
                executedQty = if (success) (fill?.executedQty ?: quantity) else 0.0,
                requestedPrice = fill?.requestedPrice,
                isSimulation = false
            )
        } finally {
            tradeInFlight.set(false)
        }
    }

    /**
     * Mengeksekusi order JUAL.
     */
    suspend fun handleSell(req: AgentTradeRequest, context: Context): AgentTradeResponse = withContext(Dispatchers.IO) {
        val prefs = AppPreferences(context)
        val (base, quote, normSymbol) = parseAndValidateSymbol(req.symbol)
            ?: return@withContext AgentTradeResponse(
                success = false,
                message = "Format simbol '${req.symbol}' tidak valid. Hanya kuotasi USDT dan IDR yang didukung.",
                symbol = req.symbol,
                side = "SELL",
                isSimulation = req.isSimulation
            )

        val price = resolvePrice(normSymbol, req.price)
        val quantity = req.quantity ?: 0.0
        if (quantity <= 0.0) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "Quantity aset koin untuk order SELL harus lebih besar dari 0.",
                symbol = normSymbol,
                side = "SELL",
                price = price,
                isSimulation = req.isSimulation
            )
        }

        // --- MODE SIMULASI (PAPER TRADING) ---
        if (req.isSimulation) {
            return@withContext executeSimulationSell(
                symbol = normSymbol,
                base = base,
                quote = quote,
                price = price,
                quantity = quantity,
                type = req.type
            )
        }

        // --- MODE TRANSAKSI RIIL ---
        val real = realCoordinator
            ?: return@withContext AgentTradeResponse(
                success = false,
                message = "Trading coordinator belum siap. Buka aplikasi nadi agentfeed terlebih dahulu.",
                symbol = normSymbol,
                side = "SELL",
                isSimulation = false
            )

        if (!real.isPinUnlocked.value) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "Sesi PIN keamanan terkunci atau kedaluwarsa. Masukkan PIN pada aplikasi nadi agentfeed terlebih dahulu.",
                symbol = normSymbol,
                side = "SELL",
                isSimulation = false
            )
        }

        if (AppPreferences.isKeyStoreRecoveryOccurred) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "Penyimpanan aman (KeyStore) sedang bermasalah. Order riil diblokir demi keamanan.",
                symbol = normSymbol,
                side = "SELL",
                isSimulation = false
            )
        }

        val apiKey = prefs.tokocryptoApiKey
        val secretKey = prefs.tokocryptoSecretKey
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "API Key / Secret Tokocrypto belum diatur di aplikasi nadi agentfeed.",
                symbol = normSymbol,
                side = "SELL",
                isSimulation = false
            )
        }

        if (!tradeInFlight.compareAndSet(false, true)) {
            return@withContext AgentTradeResponse(
                success = false,
                message = "Order lain masih diproses. Tunggu sampai selesai.",
                symbol = normSymbol,
                side = "SELL",
                isSimulation = false
            )
        }

        try {
            AppLogManager.trade("AgentTrading", "Mengeksekusi SELL Riil: $normSymbol | Qty: $quantity @ $price")
            val deferred = CompletableDeferred<Pair<Boolean, String>>()
            real.executeRealTrade(
                p = normSymbol,
                t = "sell",
                pr = price,
                a = quantity,
                tp1 = 0.0,
                tp2 = 0.0,
                exchange = req.exchange
            ) { ok, msg ->
                deferred.complete(ok to msg)
            }

            val (success, message) = withTimeoutOrNull(25_000L) { deferred.await() }
                ?: (false to "Waktu tunggu konfirmasi transaksi jual habis (timeout).")

            // 2026-11-06 — data order nyata dari record fill executor (sama dengan BUY).
            val fill = real.lastRealFillFor(normSymbol, "sell")
            AgentTradeResponse(
                success = success,
                message = message,
                orderId = fill?.orderId?.takeIf { it.isNotBlank() },
                clientOrderId = fill?.clientOrderId?.takeIf { it.isNotBlank() },
                exchange = req.exchange,
                symbol = normSymbol,
                side = "SELL",
                price = fill?.requestedPrice ?: price,
                quantity = fill?.requestedQty?.takeIf { it > 0.0 } ?: quantity,
                status = realFillStatus(success, fill),
                executedQty = if (success) (fill?.executedQty ?: quantity) else 0.0,
                requestedPrice = fill?.requestedPrice,
                isSimulation = false
            )
        } finally {
            tradeInFlight.set(false)
        }
    }

    /**
     * Membatalkan order aktif.
     */
    suspend fun handleCancel(req: AgentCancelRequest, context: Context): AgentCancelResponse = withContext(Dispatchers.IO) {
        val normSymbol = req.symbol.trim().uppercase().replace("_", "")

        if (req.isSimulation) {
            val sim = simCoordinator
                ?: return@withContext AgentCancelResponse(
                    success = false,
                    message = "Simulasi belum siap.",
                    orderId = req.orderId,
                    symbol = normSymbol,
                    isSimulation = true
                )
            val ok = sim.cancelOrder(req.orderId)
            return@withContext AgentCancelResponse(
                success = ok,
                message = if (ok) "Order simulasi ${req.orderId} berhasil dibatalkan." else "Order simulasi ${req.orderId} tidak ditemukan.",
                orderId = req.orderId,
                symbol = normSymbol,
                isSimulation = true
            )
        }

        val real = realCoordinator
            ?: return@withContext AgentCancelResponse(
                success = false,
                message = "Trading coordinator belum siap.",
                orderId = req.orderId,
                symbol = normSymbol,
                isSimulation = false
            )

        val deferred = CompletableDeferred<Pair<Boolean, String>>()
        real.executeCancelRealOrder(normSymbol, req.orderId) { ok, msg ->
            deferred.complete(ok to msg)
        }

        val (success, msg) = withTimeoutOrNull(15_000L) { deferred.await() }
            ?: (false to "Timeout menunggu pembatalan order.")

        AgentCancelResponse(
            success = success,
            message = msg,
            orderId = req.orderId,
            symbol = normSymbol,
            exchange = req.exchange,
            isSimulation = false
        )
    }

    /**
     * Membaca status order detail.
     */
    suspend fun handleGetOrder(
        orderId: String,
        symbol: String?,
        isSimulation: Boolean,
        exchange: String,
        context: Context
    ): AgentOrderStatusResponse = withContext(Dispatchers.IO) {
        val prefs = AppPreferences(context)
        val cleanSym = symbol?.trim()?.uppercase()?.replace("_", "") ?: ""

        if (isSimulation) {
            val sim = simCoordinator
            val order = sim?.openOrders?.value?.find { it.id == orderId }
            if (order != null) {
                return@withContext AgentOrderStatusResponse(
                    success = true,
                    message = "Order simulasi aktif",
                    orderId = order.id,
                    symbol = order.symbol,
                    side = order.side.name,
                    price = order.limitPrice,
                    origQty = order.quantity,
                    executedQty = order.filledQuantity,
                    status = order.status.name,
                    time = order.createdAt,
                    isSimulation = true
                )
            }
            return@withContext AgentOrderStatusResponse(
                success = false,
                message = "Order simulasi $orderId tidak ditemukan di antrean aktif.",
                orderId = orderId,
                symbol = cleanSym,
                isSimulation = true
            )
        }

        // Status satu order diverifikasi langsung ke bursa lewat
        // TokocryptoTradeApi.getOrder. Daftar open order (cache `real_open_orders`)
        // disediakan terpisah lewat GET /api/v1/trading/orders supaya agen bisa
        // merekonsiliasi tanpa menanyakan satu per satu ID.
        val apiKey = prefs.tokocryptoApiKey
        val secretKey = prefs.tokocryptoSecretKey

        if (apiKey.isNotBlank() && secretKey.isNotBlank() && cleanSym.isNotBlank()) {
            try {
                val res = TokocryptoTradeApi.getOrder(apiKey, secretKey, cleanSym, orderId, null)
                if (res.success) {
                    return@withContext AgentOrderStatusResponse(
                        success = true,
                        message = res.message,
                        orderId = res.orderId.ifBlank { orderId },
                        clientOrderId = res.clientOrderId,
                        exchange = exchange,
                        symbol = cleanSym,
                        price = 0.0,
                        origQty = res.origQty,
                        executedQty = res.executedQty,
                        status = res.status.ifBlank { "ACTIVE" },
                        time = System.currentTimeMillis(),
                        isSimulation = false
                    )
                }
            } catch (e: Throwable) {
                // `Throwable`, bukan `Exception`: Error (LinkageError/NoSuchMethodError)
                // dari jalur jaringan tidak boleh menembus handler dan menutup aplikasi.
                AppLogManager.error("AgentTrading", "Gagal mengambil order status Tokocrypto", e)
            }
        }

        AgentOrderStatusResponse(
            success = false,
            message = "Order $orderId tidak dapat diverifikasi dari server $exchange.",
            orderId = orderId,
            symbol = cleanSym,
            exchange = exchange,
            isSimulation = false
        )
    }

    /**
     * Daftar open order untuk rekonsiliasi agent (2026-11-08).
     *
     * Sebelumnya agen hanya bisa `GET /orders/{id}` — ia harus sudah tahu ID-nya.
     * Padahal setelah restart atau timeout koneksi, agen perlu mengetahui order mana
     * yang masih aktif sebelum memutuskan apakah akan membatalkan atau menunggu.
     *
     * Sumber: `SimulationCoordinator.openOrders` untuk paper, dan cache Room
     * `real_open_orders` untuk riil. Cache riil **bukan** hasil spekulasi: bila database
     * belum siap, endpoint mengembalikan `success=false` + pesan, bukan daftar kosong
     * yang bisa dibaca agen sebagai "tidak ada order".
     */
    suspend fun handleListOpenOrders(
        symbol: String?,
        isSimulation: Boolean,
        exchange: String,
        context: Context
    ): AgentOpenOrdersResponse = withContext(Dispatchers.IO) {
        val filter = symbol?.trim()?.uppercase()?.replace("_", "")?.takeIf { it.isNotBlank() }
        val cocok: (String) -> Boolean = { s ->
            filter == null || s.uppercase().replace("_", "") == filter
        }

        if (isSimulation) {
            val sim = simCoordinator
                ?: return@withContext AgentOpenOrdersResponse(
                    success = false,
                    message = "SimulationCoordinator belum siap.",
                    isSimulation = true,
                    symbolFilter = filter
                )
            val daftar = sim.openOrders.value
                .filter { cocok(it.symbol) }
                .map { o ->
                    AgentOpenOrder(
                        orderId = o.id,
                        symbol = o.symbol,
                        side = o.side.name,
                        price = o.limitPrice,
                        origQty = o.quantity,
                        executedQty = o.filledQuantity,
                        status = o.status.name,
                        isSimulation = true,
                        createdAt = o.createdAt
                    )
                }
            return@withContext AgentOpenOrdersResponse(
                success = true,
                message = "Open order simulasi: ${daftar.size}",
                exchange = exchange,
                isSimulation = true,
                symbolFilter = filter,
                count = daftar.size,
                orders = daftar
            )
        }

        val dariDb = runCatching {
            AppDatabase.getInstance().realTradeDao()
                .getOpenOrdersByExchangeFlow(exchange).first()
        }.getOrElse { err ->
            AppLogManager.error("AgentTrading", "Gagal baca cache open order riil", err)
            return@withContext AgentOpenOrdersResponse(
                success = false,
                message = "Cache open order riil tidak dapat dibaca: ${err.javaClass.simpleName}",
                exchange = exchange,
                symbolFilter = filter
            )
        }

        val daftar = dariDb
            .filter { cocok(it.symbol) }
            .map { o ->
                AgentOpenOrder(
                    orderId = o.orderId,
                    symbol = o.symbol,
                    side = o.side,
                    price = o.price,
                    origQty = o.quantity,
                    executedQty = o.executedQty,
                    status = o.status,
                    isSimulation = false,
                    createdAt = o.time
                )
            }
        AgentOpenOrdersResponse(
            success = true,
            message = "Open order riil dari cache lokal: ${daftar.size}",
            exchange = exchange,
            symbolFilter = filter,
            count = daftar.size,
            orders = daftar
        )
    }

    private fun executeSimulationBuy(
        symbol: String,
        base: String,
        quote: String,
        price: Double,
        quantity: Double,
        type: String?
    ): AgentTradeResponse {
        val sim = simCoordinator
            ?: return AgentTradeResponse(
                success = false,
                message = "SimulationCoordinator belum siap.",
                symbol = symbol,
                side = "BUY",
                isSimulation = true
            )

        val pair = TradingPair(
            symbol = symbol,
            baseAsset = base,
            quoteAsset = quote,
            displayName = "$base / $quote"
        )

        val orderType = if (type.equals("LIMIT", ignoreCase = true)) SimulationOrderType.LIMIT else SimulationOrderType.MARKET
        val result = sim.submitOrder(
            pair = pair,
            currentPrice = price,
            side = SimulationOrderSide.BUY,
            type = orderType,
            price = price,
            quantity = quantity,
            strategyMode = "AGENT_BRIDGE"
        )

        return when (result) {
            is SimulationOrderResult.Success -> {
                val simFilled = result.order.status == SimulationOrderStatus.FILLED
                AgentTradeResponse(
                    success = true,
                    message = result.message,
                    orderId = result.order.id,
                    symbol = symbol,
                    side = "BUY",
                    // 2026-11-06 — harga/qty fill nyata dari mesin simulasi (slippage diterapkan),
                    // bukan nilai ketikan acuan.
                    price = result.order.filledAvgPrice.takeIf { it > 0.0 } ?: price,
                    quantity = quantity,
                    status = result.order.status.name,
                    executedQty = if (simFilled) (result.order.filledQuantity.takeIf { it > 0.0 } ?: quantity) else 0.0,
                    isSimulation = true
                )
            }
            is SimulationOrderResult.Error -> {
                AgentTradeResponse(
                    success = false,
                    message = "Simulasi BUY ditolak: ${result.message}",
                    symbol = symbol,
                    side = "BUY",
                    price = price,
                    quantity = quantity,
                    status = "REJECTED",
                    isSimulation = true
                )
            }
        }
    }

    private fun executeSimulationSell(
        symbol: String,
        base: String,
        quote: String,
        price: Double,
        quantity: Double,
        type: String?
    ): AgentTradeResponse {
        val sim = simCoordinator
            ?: return AgentTradeResponse(
                success = false,
                message = "SimulationCoordinator belum siap.",
                symbol = symbol,
                side = "SELL",
                isSimulation = true
            )

        val pair = TradingPair(
            symbol = symbol,
            baseAsset = base,
            quoteAsset = quote,
            displayName = "$base / $quote"
        )

        val orderType = if (type.equals("LIMIT", ignoreCase = true)) SimulationOrderType.LIMIT else SimulationOrderType.MARKET
        val result = sim.submitOrder(
            pair = pair,
            currentPrice = price,
            side = SimulationOrderSide.SELL,
            type = orderType,
            price = price,
            quantity = quantity,
            strategyMode = "AGENT_BRIDGE"
        )

        return when (result) {
            is SimulationOrderResult.Success -> {
                val simFilled = result.order.status == SimulationOrderStatus.FILLED
                AgentTradeResponse(
                    success = true,
                    message = result.message,
                    orderId = result.order.id,
                    symbol = symbol,
                    side = "SELL",
                    // 2026-11-06 — harga/qty fill nyata dari mesin simulasi (slippage diterapkan),
                    // bukan nilai ketikan acuan.
                    price = result.order.filledAvgPrice.takeIf { it > 0.0 } ?: price,
                    quantity = quantity,
                    status = result.order.status.name,
                    executedQty = if (simFilled) (result.order.filledQuantity.takeIf { it > 0.0 } ?: quantity) else 0.0,
                    isSimulation = true
                )
            }
            is SimulationOrderResult.Error -> {
                AgentTradeResponse(
                    success = false,
                    message = "Simulasi SELL ditolak: ${result.message}",
                    symbol = symbol,
                    side = "SELL",
                    price = price,
                    quantity = quantity,
                    status = "REJECTED",
                    isSimulation = true
                )
            }
        }
    }

    private fun resolvePrice(symbol: String, requestedPrice: Double?): Double {
        if (requestedPrice != null && requestedPrice > 0.0) return requestedPrice
        val provider = latestPriceProvider ?: return 0.0
        return provider(symbol)
            ?: provider(symbol.replace("_", ""))
            ?: provider(symbol.lowercase())
            ?: provider(symbol.uppercase())
            ?: 0.0
    }

    /**
     * Memisahkan base asset dan quote asset serta memastikan quote sah (USDT atau IDR).
     */
    fun parseAndValidateSymbol(symbol: String): Triple<String, String, String>? {
        val clean = symbol.trim().uppercase().replace("_", "")
        if (clean.endsWith("BIDR") && clean != "BNBIDR" && clean != "SHIBIDR") {
            return null
        }
        val (base, quote) = when {
            clean.endsWith("USDT") -> clean.removeSuffix("USDT") to "USDT"
            clean.endsWith("IDR") -> clean.removeSuffix("IDR") to "IDR"
            else -> return null
        }
        if (base.isBlank()) return null
        return Triple(base, quote, "${base}_$quote")
    }
}
