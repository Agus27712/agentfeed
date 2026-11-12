package agu.analys.bridge

import android.content.Context
import agu.analys.config.FeeSchedule
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.engine.ModeSignalEvaluator
import agu.analys.engine.ModeSignalInput
import agu.analys.model.Timeframe
import agu.analys.model.TokocryptoSymbolInfo
import agu.analys.service.TokocryptoMarketService
import agu.analys.util.AppLogManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Sistem penyedia data pasar untuk Agent Bridge (2026-11-08).
 *
 * Sebelum ini ekstensi OMP mengambil harga & klines langsung dari host eksternal
 * (`tokocrypto.site`, `cloudme-toko.2meta.app`). Itu membuat agent bisa memakai data
 * yang **berbeda** dari chart aplikasi, dan bergantung pada proxy yang bisa basi/mati.
 * Objek ini membungkus layanan yang **sama persis** dengan yang dipakai chart, lalu
 * menyajikannya lewat endpoint lokal `127.0.0.1:8765`.
 *
 * Dua keputusan yang sengaja diambil:
 *
 * 1. **Tidak bergantung pada sakelar `isAgentOutputEnabled`.** Sakelar itu mengatur
 *    emit sinyal/portofolio ke berkas & WebSocket. Data pasar tetap harus tersedia
 *    walau sakelar mati, karena agent tetap boleh memantau harga sebelum memutuskan.
 * 2. **Gagal = `available=false` + `error`, bukan angka nol.** Agen dilarang mengira
 *    `price: 0.0` sebagai harga nyata. Semua fetch dibungkus timeout agar endpoint
 *    tidak menggantung koneksi agent ketika jaringan bursa lambat.
 */
object AgentMarketDataProvider {

    const val API_VERSION = "2026-11-08"
    const val SERVICE_ID = "agentfeed-bridge-v3"

    val INTERVALS = listOf("1m", "5m", "15m", "1h", "4h", "1d")
    val QUOTE_ASSETS = listOf("USDT", "IDR")

    private const val FETCH_TIMEOUT_MS = 9_000L
    private const val SYNC_TIMEOUT_MS = 10_000L
    private const val MIN_KLINE_LIMIT = 10
    private const val MAX_KLINE_LIMIT = 1000

    private val readEndpoints = listOf(
        "GET /health",
        "GET /api/v1/capabilities",
        "GET /api/v1/market/time",
        "GET /api/v1/market/ticker?symbol=",
        "GET /api/v1/market/klines?symbol=&interval=&limit=",
        "GET /api/v1/market/depth?symbol=&limit=",
        "GET /api/v1/market/symbols?quote=&limit=",
        "GET /api/v1/market/context?symbol=&interval=&limit=",
        "GET /api/v1/engine/analysis?symbol=",
        "GET /api/v1/market/analysis?symbol=",
        "GET /signals/latest",
        "GET /signals/history?limit=",
        "GET /portfolio"
    )

    private val writeEndpoints = listOf(
        "GET  /api/v1/trading/status",
        "GET  /api/v1/trading/orders",
        "GET  /api/v1/trading/orders/{id}",
        "POST /api/v1/trading/buy",
        "POST /api/v1/trading/sell",
        "POST /api/v1/trading/cancel"
    )

    private val pushEndpoints = listOf(
        "WS  /ws/signals",
        "WS  /ws/portfolio",
        "SSE /events (event: signal | portfolio | ping)"
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun capabilities(): AgentCapabilities = AgentCapabilities(
        service = SERVICE_ID,
        apiVersion = API_VERSION,
        app = "nadi agentfeed",
        exchange = "TOKOCRYPTO",
        quoteAssets = QUOTE_ASSETS,
        intervals = INTERVALS,
        minKlineLimit = MIN_KLINE_LIMIT,
        maxKlineLimit = MAX_KLINE_LIMIT,
        maxDepthLimit = 100,
        readEndpoints = readEndpoints,
        writeEndpoints = writeEndpoints,
        pushEndpoints = pushEndpoints
    )

    /** Tarif fee persen per sisi, mirror `FeeSchedule` (SSOT di AppConfiguration). */
    fun fees(quote: String): AgentFeeSnapshot {
        val q = quote.trim().uppercase().takeIf { it.isNotBlank() } ?: "USDT"
        val cfg = FeeSchedule.configFor("TOKOCRYPTO", q)
        return AgentFeeSnapshot(
            quote = q,
            buyMakerPct = cfg.buyMakerPct,
            buyTakerPct = cfg.buyTakerPct,
            sellMakerPct = cfg.sellMakerPct,
            sellTakerPct = cfg.sellTakerPct,
            slippagePctPerSide = FeeSchedule.DEFAULT_SLIPPAGE_PCT,
            roundTripPctMultiplicative = FeeSchedule.roundTripPctMultiplicative("TOKOCRYPTO", q)
        )
    }

    suspend fun time(): AgentServerTime {
        val device = System.currentTimeMillis()
        val server = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            runCatching { TokocryptoMarketService.fetchServerTime() }.getOrDefault(device)
        } ?: device
        return AgentServerTime(
            serverTime = server,
            deviceTime = device,
            offsetMs = server - device,
            source = "TOKOCRYPTO_OR_DEVICE_FALLBACK"
        )
    }

    suspend fun ticker(symbol: String): AgentTickerSnapshot {
        val (norm, _) = normalize(symbol)
            ?: return AgentTickerSnapshot(symbol, false, error = invalidSymbol(symbol))
        return try {
            val tick = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                TokocryptoMarketService.fetchTicker(norm)
            }
            if (tick == null || tick.price <= 0.0) {
                AgentTickerSnapshot(norm, false, error = "Ticker $norm tidak tersedia dari bursa.")
            } else {
                AgentTickerSnapshot(
                    symbol = norm,
                    available = true,
                    price = tick.price,
                    high24h = tick.high24h,
                    low24h = tick.low24h,
                    volume24h = tick.volume24h,
                    change24h = tick.change24h,
                    ts = tick.timestamp
                )
            }
        } catch (t: Throwable) {
            logGagal("ticker", norm, t)
            AgentTickerSnapshot(norm, false, error = ringkas(t))
        }
    }

    suspend fun klines(symbol: String, interval: String, limit: Int): AgentKlinesSnapshot {
        val (norm, _) = normalize(symbol)
            ?: return AgentKlinesSnapshot(symbol, interval, limit, false, error = invalidSymbol(symbol))
        val tf = timeframe(interval)
            ?: return AgentKlinesSnapshot(
                norm, interval, limit, false,
                error = "Interval '$interval' tidak didukung. Pakai salah satu dari $INTERVALS."
            )
        val aman = limit.coerceIn(MIN_KLINE_LIMIT, MAX_KLINE_LIMIT)
        return try {
            val bars = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                TokocryptoMarketService.fetchCandles(norm, tf, aman)
            }.orEmpty()
            if (bars.isEmpty()) {
                AgentKlinesSnapshot(norm, interval, aman, false, error = "Klines $norm $interval kosong.")
            } else {
                AgentKlinesSnapshot(
                    symbol = norm,
                    interval = interval,
                    limit = aman,
                    available = true,
                    candles = bars.map {
                        AgentCandle(
                            openTime = it.timestamp,
                            open = it.open,
                            high = it.high,
                            low = it.low,
                            close = it.close,
                            volume = it.volume,
                            closed = it.isClosed
                        )
                    }
                )
            }
        } catch (t: Throwable) {
            logGagal("klines", norm, t)
            AgentKlinesSnapshot(norm, interval, aman, false, error = ringkas(t))
        }
    }

    suspend fun depth(symbol: String, limit: Int): AgentDepthSnapshot {
        val (norm, _) = normalize(symbol)
            ?: return AgentDepthSnapshot(symbol, false, error = invalidSymbol(symbol))
        val aman = limit.coerceIn(5, 100)
        return try {
            val pasangan = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                TokocryptoMarketService.fetchOrderBook(norm, aman)
            }
            val bids = pasangan?.first.orEmpty()
            val asks = pasangan?.second.orEmpty()
            if (bids.isEmpty() && asks.isEmpty()) {
                return AgentDepthSnapshot(norm, false, error = "Order book $norm kosong.")
            }
            val bidVolume = bids.sumOf { it.amount }
            val askVolume = asks.sumOf { it.amount }
            val bestBid = bids.maxOfOrNull { it.price } ?: 0.0
            val bestAsk = asks.minOfOrNull { it.price } ?: 0.0
            AgentDepthSnapshot(
                symbol = norm,
                available = true,
                bids = bids.map { AgentDepthLevel(it.price, it.amount, it.total) },
                asks = asks.map { AgentDepthLevel(it.price, it.amount, it.total) },
                bidVolume = bidVolume,
                askVolume = askVolume,
                spread = if (bestBid > 0.0 && bestAsk > 0.0) bestAsk - bestBid else 0.0,
                imbalance = if (bidVolume + askVolume > 0.0) {
                    (bidVolume - askVolume) / (bidVolume + askVolume)
                } else 0.0
            )
        } catch (t: Throwable) {
            logGagal("depth", norm, t)
            AgentDepthSnapshot(norm, false, error = ringkas(t))
        }
    }

    /** Daftar simbol + aturan eksekusi. `quote` kosong/"ALL" = semua kuotasi. */
    suspend fun symbols(quote: String?, limit: Int): AgentSymbolsSnapshot {
        val filter = quote?.trim()?.uppercase()?.takeIf { it.isNotBlank() && it != "ALL" }
        val aman = limit.coerceIn(1, 500)
        return try {
            withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                runCatching { TokocryptoSymbolRepository.ensureSymbolsLoaded() }
            }
            val semua = TokocryptoSymbolRepository.symbolsState.value
            val terpilih = semua
                .filter { it.spotTradingEnable && (filter == null || it.quoteAsset.equals(filter, true)) }
                .take(aman)
            if (terpilih.isEmpty()) {
                AgentSymbolsSnapshot(
                    available = false, quoteFilter = filter,
                    error = "Daftar simbol belum tersinkron dari Tokocrypto."
                )
            } else {
                AgentSymbolsSnapshot(
                    available = true,
                    count = terpilih.size,
                    quoteFilter = filter,
                    symbols = terpilih.map { it.toRules() }
                )
            }
        } catch (t: Throwable) {
            logGagal("symbols", filter ?: "ALL", t)
            AgentSymbolsSnapshot(false, quoteFilter = filter, error = ringkas(t))
        }
    }

    /** Aturan eksekusi satu simbol; `null` bila repository belum punya simbol itu. */
    fun rules(symbol: String): AgentSymbolRules? {
        val (norm, _) = normalize(symbol) ?: return null
        val info = runCatching { TokocryptoSymbolRepository.getSymbolInfo(norm) }.getOrNull()
            ?: return null
        return info.toRules()
    }

    /**
     * Satu panggilan untuk seluruh konteks trading agent. Bagian yang berbasis jaringan
     * dijalankan paralel supaya agent tidak menunggu tiga request berurutan.
     */
    suspend fun context(
        ctx: Context,
        symbol: String,
        interval: String,
        limit: Int
    ): AgentTradingContext = try {
        buildContext(ctx, symbol, interval, limit)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        // Kontrak endpoint: kegagalan = `notes` berisi alasan, BUKAN exception
        // yang naik ke thread Ktor. `Error` sekalipun tidak boleh menutup aplikasi
        // saat agen memanggil `agent_market_context`.
        logGagal("context", symbol, t)
        AgentTradingContext(
            symbol = symbol,
            notes = listOf("Konteks pasar gagal dibangun: ${ringkas(t)}. Jangan kirim order sebelum data tersedia.")
        )
    }

    private suspend fun buildContext(
        ctx: Context,
        symbol: String,
        interval: String,
        limit: Int
    ): AgentTradingContext = coroutineScope {
        val parsed = normalize(symbol)
            ?: return@coroutineScope AgentTradingContext(
                symbol = symbol,
                notes = listOf(invalidSymbol(symbol))
            )
        val (norm, quote) = parsed

        val tickerJob = async { ticker(norm) }
        val klinesJob = async { klines(norm, interval, limit) }
        val depthJob = async { depth(norm, 20) }
        val engineJob = async { engineAnalysis(ctx, norm) }

        val aturan = rules(norm)
        val biaya = runCatching { fees(quote) }.getOrNull()
        val sinyal = latestSignal(ctx)
        val portofolio = runCatching {
            AgentTradingCommandHandler.buildPortfolioSnapshot(ctx)
        }.getOrNull()
        val status = runCatching {
            AgentTradingCommandHandler.getTradingStatus(ctx)
        }.getOrNull()

        val catatan = mutableListOf<String>()
        if (sinyal == null) catatan += "Belum ada sinyal engine; agent boleh tetap memakai data pasar mentah."
        if (portofolio == null) catatan += "Snapshot portofolio belum tersedia (coordinator belum terdaftar)."
        if (aturan == null) catatan += "Aturan eksekusi $norm belum tersinkron; jangan kirim order sebelum rules tersedia."
        if (biaya == null) catatan += "Tarif fee tidak dapat dihitung; jangan kirim order sebelum fees tersedia."
        if (status?.isBridgeRunning != true) catatan += "Status bridge belum terkonfirmasi Running."

        val anal = engineJob.await()

        AgentTradingContext(
            symbol = norm,
            ticker = tickerJob.await(),
            klines = klinesJob.await(),
            depth = depthJob.await(),
            rules = aturan,
            fees = biaya,
            latestSignal = sinyal,
            portfolio = portofolio,
            tradingStatus = status,
            engineAnalysis = anal,
            notes = catatan
        )
    }

    /**
     * Analisis komprehensif multi-engine (Scalping, Intraday, Swing) beserta indikator teknikal
     * nyata (RSI, MACD, EMA, ATR, RVOL, VWAP, Bollinger Bands) yang dihitung langsung oleh
     * [ModeSignalEvaluator] bursa Tokocrypto.
     */
    suspend fun engineAnalysis(ctx: Context, symbol: String): AgentEngineAnalysis {
        val parsed = normalize(symbol)
            ?: return AgentEngineAnalysis(
                symbol = symbol,
                available = false,
                error = invalidSymbol(symbol)
            )
        val (norm, quote) = parsed

        return try {
            coroutineScope {
                val tickJob = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { TokocryptoMarketService.fetchTicker(norm) }
                }
                val h1Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { TokocryptoMarketService.fetchCandles(norm, Timeframe.H1, 60) }.orEmpty()
                }
                val h4Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { TokocryptoMarketService.fetchCandles(norm, Timeframe.H4, 60) }.orEmpty()
                }
                val d1Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { TokocryptoMarketService.fetchCandles(norm, Timeframe.D1, 30) }.orEmpty()
                }
                val m15Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { TokocryptoMarketService.fetchCandles(norm, Timeframe.M15, 60) }.orEmpty()
                }
                val m5Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { TokocryptoMarketService.fetchCandles(norm, Timeframe.M5, 60) }.orEmpty()
                }
                val m1Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { TokocryptoMarketService.fetchCandles(norm, Timeframe.M1, 60) }.orEmpty()
                }
                val bookJob = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { TokocryptoMarketService.fetchOrderBook(norm, 20) }
                }

                val tick = tickJob.await()
                val price = tick?.price ?: 0.0
                if (price <= 0.0) {
                    return@coroutineScope AgentEngineAnalysis(
                        symbol = norm,
                        available = false,
                        error = "Ticker atau harga $norm belum tersedia dari bursa."
                    )
                }

                val h1Candles = h1Job.await()
                val h4Candles = h4Job.await()
                val d1Candles = d1Job.await()
                val m15Candles = m15Job.await()
                val m5Candles = m5Job.await()
                val m1Candles = m1Job.await()
                val bookPair = bookJob.await()
                val bids = bookPair?.first.orEmpty()
                val asks = bookPair?.second.orEmpty()

                val nowMs = System.currentTimeMillis()
                val feeModel = FeeSchedule.configFor("TOKOCRYPTO", quote)

                val baseInput = ModeSignalInput(
                    mode = StrategyMode.SCALPING,
                    symbol = norm,
                    price = price,
                    nowMs = nowMs,
                    change24h = tick?.change24h ?: Double.NaN,
                    h1Candles = if (h1Candles.size > 1) h1Candles.dropLast(1) else h1Candles,
                    h4Candles = if (h4Candles.size > 1) h4Candles.dropLast(1) else h4Candles,
                    d1Candles = d1Candles,
                    m15Candles = m15Candles,
                    m5Candles = m5Candles,
                    m1Candles = m1Candles,
                    bids = bids,
                    asks = asks,
                    fees = feeModel
                )

                val scalpResult = runCatching {
                    ModeSignalEvaluator.evaluate(baseInput.copy(mode = StrategyMode.SCALPING))
                }.getOrNull()

                val intradayResult = runCatching {
                    ModeSignalEvaluator.evaluate(baseInput.copy(mode = StrategyMode.OFFICE_DAILY))
                }.getOrNull()

                val swingResult = runCatching {
                    ModeSignalEvaluator.evaluate(baseInput.copy(mode = StrategyMode.SWING))
                }.getOrNull()

                val activeIndicators = scalpResult?.indicators
                    ?: intradayResult?.indicators
                    ?: swingResult?.indicators

                val techDto = activeIndicators?.let { ind ->
                    AgentTechnicalIndicators(
                        rsi14 = ind.rsi14.takeIf { !it.isNaN() },
                        macd = ind.macd.takeIf { !it.isNaN() },
                        macdSignal = ind.macdSignal.takeIf { !it.isNaN() },
                        macdHist = ind.macdHist.takeIf { !it.isNaN() },
                        ema20 = ind.ema20.takeIf { !it.isNaN() },
                        ema50 = ind.ema50.takeIf { !it.isNaN() },
                        ema200 = ind.ema200.takeIf { !it.isNaN() },
                        bbUpper = ind.bbUpper.takeIf { !it.isNaN() },
                        bbLower = ind.bbLower.takeIf { !it.isNaN() },
                        atr = ind.atr.takeIf { !it.isNaN() },
                        rvol = ind.rvol.takeIf { !it.isNaN() },
                        vwap = ind.vwap.takeIf { !it.isNaN() }
                    )
                }

                val scalpDto = scalpResult?.let { res ->
                    val s = res.signal
                    AgentScalpingAnalysis(
                        action = s.action.name,
                        stage = s.scalpingStage.displayName,
                        confidence = s.confidence,
                        setup = s.scalpingSetup.ifBlank { s.patternDetected.orEmpty() },
                        score = s.scalpingScore,
                        scoreCategory = s.scalpingScoreCategory,
                        regime = s.scalpingRegime.ifBlank { s.regimeDetected },
                        entryPrice = if (s.entryPrice > 0.0) s.entryPrice else price,
                        entryZoneLow = s.entryZoneLow,
                        entryZoneHigh = s.entryZoneHigh,
                        tp1 = s.targetPrice1,
                        tp2 = s.targetPrice2,
                        sl = s.stopLoss,
                        riskReward = s.riskRewardRatio,
                        checkpoints = s.mtf.resolvedCheckpoints().map {
                            AgentCheckpoint(
                                code = it.code,
                                label = it.label,
                                isOk = it.isOk,
                                metricValue = it.metricValue,
                                detail = it.detail
                            )
                        },
                        reasoning = s.reasoning
                    )
                }

                val intradayDto = intradayResult?.let { res ->
                    val s = res.signal
                    AgentIntradayAnalysis(
                        action = s.action.name,
                        confidence = s.confidence,
                        setup = s.patternDetected ?: "Intraday Momentum",
                        session = "H4 / Daily Session",
                        targetPrice = s.targetPrice1,
                        stopLoss = s.stopLoss,
                        riskReward = s.riskRewardRatio,
                        reasoning = s.reasoning
                    )
                }

                val swingDto = swingResult?.let { res ->
                    val s = res.signal
                    AgentSwingAnalysis(
                        action = s.action.name,
                        confidence = s.confidence,
                        trend = s.sentiment.displayName,
                        regime = s.regimeDetected,
                        support = res.indicators.bbLower.takeIf { !it.isNaN() } ?: 0.0,
                        resistance = res.indicators.bbUpper.takeIf { !it.isNaN() } ?: 0.0,
                        targetPrice = s.targetPrice1,
                        stopLoss = s.stopLoss,
                        reasoning = s.reasoning
                    )
                }

                val buys = (if (scalpDto?.action == "BUY") 1 else 0) +
                    (if (intradayDto?.action == "BUY") 1 else 0) +
                    (if (swingDto?.action == "BUY") 1 else 0)
                val sells = (if (scalpDto?.action == "SELL") 1 else 0) +
                    (if (intradayDto?.action == "SELL") 1 else 0) +
                    (if (swingDto?.action == "SELL") 1 else 0)
                val bias = when {
                    buys >= 2 -> "BULLISH_CONFLUENCE"
                    buys == 1 && sells == 0 -> "LEAN_BULLISH"
                    sells >= 2 -> "BEARISH_CONFLUENCE"
                    sells == 1 && buys == 0 -> "LEAN_BEARISH"
                    else -> "NEUTRAL_MIXED"
                }

                AgentEngineAnalysis(
                    symbol = norm,
                    available = true,
                    price = price,
                    scalping = scalpDto,
                    intraday = intradayDto,
                    swing = swingDto,
                    indicators = techDto,
                    overallBias = bias,
                    ts = nowMs
                )
            }
        } catch (t: Throwable) {
            logGagal("engineAnalysis", norm, t)
            AgentEngineAnalysis(
                symbol = norm,
                available = false,
                error = ringkas(t)
            )
        }
    }

    /** Sinyal terakhir: cache memori dulu, fallback ke `latest.json` di direktori output. */
    private fun latestSignal(ctx: Context): AgentSignalOutput? {
        val langsung = runCatching { AgentOutputBridge.getInstance(ctx).getLatest() }.getOrNull()
        if (langsung != null) return langsung
        return try {
            val berkas = File(AgentOutputBridge.getInstance(ctx).getDir(), "latest.json")
            if (!berkas.exists()) return null
            json.decodeFromString<AgentSignalOutput>(berkas.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun TokocryptoSymbolInfo.toRules(): AgentSymbolRules {
        val pf = priceFilter
        val lot = lotSizeFilter
        val notional = minNotionalFilter
        return AgentSymbolRules(
            symbol = symbol.uppercase().replace("_", ""),
            baseAsset = baseAsset.uppercase(),
            quoteAsset = quoteAsset.uppercase(),
            symbolType = symbolType,
            spotTradingEnabled = spotTradingEnable,
            tickSize = pf?.tickSize ?: 0.0,
            minPrice = pf?.minPrice ?: 0.0,
            // Double.MAX_VALUE berarti "tidak ada batas atas", bukan harga nyata.
            maxPrice = pf?.maxPrice?.takeIf { it != Double.MAX_VALUE } ?: 0.0,
            minQty = lot?.minQty ?: 0.0,
            maxQty = lot?.maxQty?.takeIf { it != Double.MAX_VALUE } ?: 0.0,
            stepSize = lot?.stepSize ?: 0.0,
            minNotional = notional?.minNotional ?: 0.0,
            basePrecision = basePrecision,
            quotePrecision = quotePrecision
        )
    }

    /** @return pasangan (simbol normal `BASE_QUOTE`, kuotasi). */
    private fun normalize(raw: String): Pair<String, String>? {
        val clean = raw.trim().uppercase()
            .replace("_", "").replace("-", "").replace("/", "").replace(" ", "")
        if (clean.endsWith("BIDR") && clean != "BNBIDR" && clean != "SHIBIDR") return null
        val (base, quote) = when {
            clean.endsWith("USDT") -> clean.removeSuffix("USDT") to "USDT"
            clean.endsWith("IDR") -> clean.removeSuffix("IDR") to "IDR"
            else -> return null
        }
        if (base.isBlank()) return null
        return "${base}_$quote" to quote
    }

    private fun timeframe(interval: String): Timeframe? = when (interval.trim().lowercase()) {
        "1m" -> Timeframe.M1
        "5m" -> Timeframe.M5
        "15m" -> Timeframe.M15
        "1h" -> Timeframe.H1
        "4h" -> Timeframe.H4
        "1d" -> Timeframe.D1
        else -> null
    }

    private fun invalidSymbol(raw: String): String =
        "Simbol '$raw' tidak valid. Aplikasi ini Tokocrypto-only: hanya kuotasi USDT atau IDR."

    private fun ringkas(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "tanpa pesan"}"

    private fun logGagal(bagian: String, simbol: String, t: Throwable) {
        runCatching {
            AppLogManager.error("AgentMarket", "Gagal ambil $bagian $simbol — ${ringkas(t)}", t)
        }
    }
}
