package agu.analys.bridge

import android.content.Context

/**
 * Stub JVM untuk dua objek produksi yang bergantung Android/Room/coordinator:
 * `AgentMarketDataProvider` dan `AgentTradingCommandHandler`.
 *
 * Yang diuji di sini BUKAN angka pasar, melainkan **kontrak HTTP**: rute ada,
 * payload ter-serialisasi dengan benar, `available`/`error` ikut terkirim, dan
 * gerbang token 401 bekerja. Karena itu semua angka di berkas ini ditandai
 * `source = "SMOKE_FIXTURE"` dan tidak boleh dibaca sebagai data bursa.
 *
 * Berkas DTO produksi (`AgentMarketModels.kt`, `AgentTradingModels.kt`,
 * `AgentOutputModels.kt`) disalin apa adanya oleh `copyRealSources`, jadi
 * bentuk JSON yang diuji identik dengan aplikasi.
 */
object AgentMarketDataProvider {

    const val API_VERSION = "2026-11-08"
    const val SERVICE_ID = "agentfeed-bridge-v3"

    fun capabilities(): AgentCapabilities = AgentCapabilities(
        service = SERVICE_ID,
        apiVersion = API_VERSION,
        app = "nadi agentfeed",
        exchange = "TOKOCRYPTO",
        quoteAssets = listOf("USDT", "IDR"),
        intervals = listOf("1m", "5m", "15m", "1h", "4h", "1d"),
        minKlineLimit = 10,
        maxKlineLimit = 1000,
        maxDepthLimit = 100,
        readEndpoints = listOf(
            "GET /health",
            "GET /api/v1/capabilities",
            "GET /api/v1/market/time",
            "GET /api/v1/market/ticker?symbol=",
            "GET /api/v1/market/klines?symbol=&interval=&limit=",
            "GET /api/v1/market/depth?symbol=&limit=",
            "GET /api/v1/market/symbols?quote=&limit=",
            "GET /api/v1/market/context?symbol=&interval=&limit=",
            "GET /signals/latest",
            "GET /signals/history?limit=",
            "GET /portfolio"
        ),
        writeEndpoints = listOf(
            "GET  /api/v1/trading/status",
            "GET  /api/v1/trading/orders",
            "GET  /api/v1/trading/orders/{id}",
            "POST /api/v1/trading/buy",
            "POST /api/v1/trading/sell",
            "POST /api/v1/trading/cancel"
        ),
        pushEndpoints = listOf("WS  /ws/signals", "WS  /ws/portfolio", "SSE /events")
    )

    suspend fun time(): AgentServerTime {
        val now = System.currentTimeMillis()
        return AgentServerTime(now, now, 0L, "SMOKE_FIXTURE")
    }

    suspend fun ticker(symbol: String): AgentTickerSnapshot = AgentTickerSnapshot(
        symbol = symbol,
        available = true,
        source = "SMOKE_FIXTURE",
        price = 100.0,
        high24h = 110.0,
        low24h = 90.0,
        volume24h = 1000.0,
        change24h = 1.5
    )

    suspend fun klines(symbol: String, interval: String, limit: Int): AgentKlinesSnapshot {
        val now = System.currentTimeMillis()
        return AgentKlinesSnapshot(
            symbol = symbol,
            interval = interval,
            limit = limit,
            available = true,
            source = "SMOKE_FIXTURE",
            candles = (0 until 30).map { i ->
                AgentCandle(
                    openTime = now - (30 - i) * 60_000L,
                    open = 99.0 + i,
                    high = 101.0 + i,
                    low = 98.0 + i,
                    close = 100.0 + i,
                    volume = 10.0,
                    closed = true
                )
            }
        )
    }

    suspend fun depth(symbol: String, limit: Int): AgentDepthSnapshot = AgentDepthSnapshot(
        symbol = symbol,
        available = true,
        bids = listOf(AgentDepthLevel(99.5, 2.0, 199.0)),
        asks = listOf(AgentDepthLevel(100.5, 1.0, 100.5)),
        bidVolume = 2.0,
        askVolume = 1.0,
        spread = 1.0,
        imbalance = 1.0 / 3.0
    )

    suspend fun symbols(quote: String?, limit: Int): AgentSymbolsSnapshot = AgentSymbolsSnapshot(
        available = true,
        count = 1,
        quoteFilter = quote,
        symbols = listOf(
            AgentSymbolRules(
                symbol = "BTCUSDT",
                baseAsset = "BTC",
                quoteAsset = "USDT",
                tickSize = 0.01,
                minQty = 0.0001,
                stepSize = 0.0001,
                minNotional = 1.0
            )
        )
    )

    suspend fun context(
        ctx: Context,
        symbol: String,
        interval: String,
        limit: Int
    ): AgentTradingContext = AgentTradingContext(
        symbol = symbol,
        ticker = ticker(symbol),
        klines = klines(symbol, interval, limit),
        depth = depth(symbol, 20),
        rules = AgentSymbolRules(symbol, "BTC", "USDT", tickSize = 0.01),
        fees = AgentFeeSnapshot("USDT", 0.4044, 0.4044, 0.4044, 0.4044, 0.08, 0.9688),
        latestSignal = AgentOutputBridge.getInstance(ctx).getLatest(),
        portfolio = AgentTradingCommandHandler.buildPortfolioSnapshot(ctx),
        tradingStatus = AgentTradingCommandHandler.getTradingStatus(ctx),
        notes = listOf("SMOKE_FIXTURE: angka di sini bukan data bursa.")
    )
}

/** Stub handler order: cukup untuk menguji rute, token, dan serialisasi respons. */
object AgentTradingCommandHandler {

    fun buildPortfolioSnapshot(context: Context): AgentPortfolioOutput = AgentPortfolioOutput(
        ts = System.currentTimeMillis(),
        exchange = "TOKOCRYPTO",
        totalIdrValue = 0.0,
        totalUsdtValue = 100.0,
        assets = emptyList()
    )

    fun getTradingStatus(context: Context): AgentTradingStatusResponse = AgentTradingStatusResponse(
        isBridgeRunning = true,
        host = "127.0.0.1",
        port = 8765,
        exchange = "TOKOCRYPTO",
        isRealBuyEnabled = false,
        isPinAuthenticated = false,
        isSimulationAvailable = true
    )

    suspend fun handleBuy(req: AgentTradeRequest, context: Context): AgentTradeResponse =
        AgentTradeResponse(
            success = false,
            message = "SMOKE_FIXTURE: handler order tidak dijalankan di JVM.",
            symbol = req.symbol,
            side = req.side
        )

    suspend fun handleSell(req: AgentTradeRequest, context: Context): AgentTradeResponse =
        AgentTradeResponse(
            success = false,
            message = "SMOKE_FIXTURE: handler order tidak dijalankan di JVM.",
            symbol = req.symbol,
            side = req.side
        )

    suspend fun handleCancel(req: AgentCancelRequest, context: Context): AgentCancelResponse =
        AgentCancelResponse(
            success = false,
            message = "SMOKE_FIXTURE: handler order tidak dijalankan di JVM.",
            orderId = req.orderId,
            symbol = req.symbol
        )

    suspend fun handleGetOrder(
        orderId: String,
        symbol: String?,
        isSimulation: Boolean,
        exchange: String,
        context: Context
    ): AgentOrderStatusResponse = AgentOrderStatusResponse(
        success = false,
        message = "SMOKE_FIXTURE: handler order tidak dijalankan di JVM.",
        orderId = orderId,
        symbol = symbol ?: "",
        isSimulation = isSimulation
    )

    suspend fun handleListOpenOrders(
        symbol: String?,
        isSimulation: Boolean,
        exchange: String,
        context: Context
    ): AgentOpenOrdersResponse = AgentOpenOrdersResponse(
        success = true,
        message = "SMOKE_FIXTURE: daftar open order kosong.",
        exchange = exchange,
        isSimulation = isSimulation,
        symbolFilter = symbol,
        count = 0,
        orders = emptyList()
    )
}
