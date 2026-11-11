package agu.analys.engine

import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.ScalpingStage
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.Timeframe
import agu.analys.model.TrendSentiment
import agu.analys.service.TokocryptoMarketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thin orchestrator: realtime buffers + mode routing. Scoring stays in dedicated evaluators.
 *
 * Modified to support aggressive scalping, removing second wave and trenching.
 */
class LearningTradingEngine(private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
    var strategyMode: StrategyMode = StrategyMode.SCALPING
    var isScalpingMode: Boolean
        get() = strategyMode == StrategyMode.SCALPING
        set(value) {
            if (value) {
                strategyMode = StrategyMode.SCALPING
            } else if (strategyMode == StrategyMode.SCALPING) {
                strategyMode = StrategyMode.SWING
            }
        }
    var tradingFees = TradingFeeConfig()

    /**
     * Resolusi tarif **sadar kuotasi** (FEE_TRUTH §0.2: beli/jual asimetris, tarif IDR berbeda
     * dari USDT). ViewModel mengisinya dengan `prefs.feeConfigFor(symbol)`.
     *
     * Sebelumnya semua evaluate memakai [tradingFees] yang global — bawaannya Tokocrypto = preset
     * **USDT** (0,4044% semua sisi). Akibatnya pair IDR di Tokocrypto (BTCIDR, ETHIDR, XAUTIDR)
     * dievaluasi dengan tarif USDT, padahal jalur paper sudah memakai tarif IDR: lantai SL jadi
     * 5,81% (harusnya 4,89%), TP jauh, dan net R:R yang tampil berbeda dari keputusan auto-buy.
     * Jangan setel ulang ke `tradingFees` — itu membuat dua angka untuk satu pertanyaan.
     */
    var feeConfigForSymbol: (String) -> TradingFeeConfig = { tradingFees }
    var scalpingRuntimeConfig: agu.analys.engine.scalping.ScalpingRuntimeConfig = agu.analys.engine.scalping.ScalpingRuntimeConfig.current

    private val candlesH1 = mutableListOf<CandleBar>()
    private val candlesH4 = mutableListOf<CandleBar>()
    private val candles1D = mutableListOf<CandleBar>()
    private var currentTick: MarketTick? = null
    private var mtfRefreshJob: Job? = null
    private var lastMtfRefresh = 0L
    private var mtfSymbol = ""
    // P0 — BTC gatekeeper 5M/1M. Diperbarui paling cepat tiap 60 detik; bukan per tick.
    private var btcPulseJob: Job? = null
    @Volatile private var lastBtcPulseRefresh = 0L
    private var h4Candles: List<CandleBar> = emptyList()
    // Ditulis dari coroutine IO (refresh) dan dibaca dari thread tick — @Volatile agar
    // penugasan antar-thread selalu terlihat; pembaca wajib snapshot sekali per evaluasi
    // supaya triple (h1/m15/m1) yang dipakai konsisten dalam satu run.
    @Volatile private var h1Candles: List<CandleBar> = emptyList()
    @Volatile private var m15Candles: List<CandleBar> = emptyList()
    @Volatile private var m5Candles: List<CandleBar> = emptyList()
    @Volatile private var m1Candles: List<CandleBar> = emptyList()
    private val _signalState = MutableStateFlow(AISignalState())
    val signalState: StateFlow<AISignalState> = _signalState.asStateFlow()
    private val _indicators = MutableStateFlow(TechnicalIndicators())
    val indicators: StateFlow<TechnicalIndicators> = _indicators.asStateFlow()

    var onCandidateSignalTransition: ((agu.analys.engine.scalping.SignalTransition) -> Unit)? = null

    var currentFormingVolume: Double = 0.0

    var currentOrderBookBids: List<agu.analys.model.OrderBookItem> = emptyList()
    var currentOrderBookAsks: List<agu.analys.model.OrderBookItem> = emptyList()

    fun onOrderBookUpdate(bids: List<agu.analys.model.OrderBookItem>, asks: List<agu.analys.model.OrderBookItem>) {
        currentOrderBookBids = bids
        currentOrderBookAsks = asks
    }

    fun onTickUpdate(tick: MarketTick) {
        if (tick.price <= 0.0) return
        currentTick = tick

        when (strategyMode) {
            StrategyMode.SCALPING -> {
                if (m1Candles.isNotEmpty()) runScalping()
            }
            StrategyMode.SWING -> {
                val hasCandles = synchronized(candlesH1) { candlesH1.isNotEmpty() }
                if (hasCandles) runSwing()
            }
            StrategyMode.OFFICE_DAILY -> {
                val hasCandles = synchronized(candlesH4) { candlesH4.isNotEmpty() }
                if (hasCandles) runIntraday()
            }
        }

        refreshScalpingTimeframesIfDue(tick.symbol)
    }

    fun onCandleUpdate(candle: CandleBar) {
        if (candle.open <= 0.0 || candle.high <= 0.0 || candle.low <= 0.0 || candle.close <= 0.0) return

        when (strategyMode) {
            StrategyMode.SCALPING -> {
                if (candle.timestamp >= (m1Candles.lastOrNull()?.timestamp ?: 0L)) {
                    val updated = (m1Candles + candle).distinctBy { it.timestamp }.sortedBy { it.timestamp }.takeLast(250)
                    m1Candles = updated
                    runScalping()
                }
            }
            else -> {}
        }
    }

    fun resetForOffline(preserveState: Boolean = false, lastKnownPrice: Double = 0.0, cachedCandles: List<CandleBar> = emptyList()) {
        currentTick = null
        mtfRefreshJob?.cancel()
        mtfRefreshJob = null
        lastMtfRefresh = 0L
        mtfSymbol = ""
        // Reset yang mempertahankan state tidak boleh membatalkan pengambilan BTC yang sedang
        // berjalan: pembatalan itu yang muncul sebagai "Gagal mengambil candle ... was canceled".
        if (!preserveState) {
            btcPulseJob?.cancel()
            btcPulseJob = null
            lastBtcPulseRefresh = 0L
        }
        h4Candles = emptyList(); h1Candles = emptyList(); m15Candles = emptyList(); m5Candles = emptyList(); m1Candles = emptyList()
        synchronized(candlesH1) { candlesH1.clear() }
        synchronized(candlesH4) { candlesH4.clear() }
        synchronized(candles1D) { candles1D.clear() }
        if (preserveState) return

        val priceText = if (lastKnownPrice > 0.0) agu.analys.util.PriceFormatter.formatPrice(lastKnownPrice, showSymbol = true) else "Terakhir Disimpan"
        _indicators.value = TechnicalIndicators()
        _signalState.value = AISignalState(
            action = SignalAction.HOLD,
            confidence = 0,
            sentiment = TrendSentiment.NEUTRAL_CONSOLIDATION,
            reasoning = listOf(
                "MODE OFFLINE: Terputus dari Server Tokocrypto.",
                "Snapshot Harga Terakhir: $priceText",
                "Sinyal LIVE ditangguhkan untuk keamanan modal."
            ),
            timestamp = System.currentTimeMillis(),
            scalpingStage = ScalpingStage.HOLD,
            isOfflineMode = true
        )
    }

    private fun isMatchingSymbol(symA: String, symB: String): Boolean {
        val a = symA.trim().uppercase().replace("_", "").replace("/", "").replace("-", "")
        val b = symB.trim().uppercase().replace("_", "").replace("/", "").replace("-", "")
        return a.isNotBlank() && a == b
    }

    private fun refreshScalpingTimeframesIfDue(symbol: String) {
        if (symbol.isBlank()) return
        val now = System.currentTimeMillis()
        val intervalMs = when (strategyMode) {
            StrategyMode.SCALPING -> 10_000L
            StrategyMode.OFFICE_DAILY -> 60_000L
            StrategyMode.SWING -> 30_000L
        }
        if (now - lastMtfRefresh < intervalMs && isMatchingSymbol(mtfSymbol, symbol)) return
        if (mtfRefreshJob?.isActive == true) return
        lastMtfRefresh = now; mtfSymbol = symbol
        mtfRefreshJob = scope.launch {
            when (strategyMode) {
                StrategyMode.SCALPING -> {
                    val activeEx = agu.analys.util.MtfCacheManager.activeExchange
                    agu.analys.util.MtfCacheManager.setActiveSymbol(symbol, activeEx)

                    var h1 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.H1, activeEx) ?: emptyList()
                    var m15 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.M15, activeEx) ?: emptyList()
                    var m5 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.M5, activeEx) ?: emptyList()
                    var m1 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.M1, activeEx) ?: emptyList()

                    var attempts = 0
                    // P0 — MTF 4 TF. Jangan jalankan evaluator sebelum M5 juga siap; gate
                    // sengaja fail-closed, jadi menunggu di sini lebih jujur daripada
                    // mempublikasikan "MTF belum lengkap" berulang-ulang.
                    while ((h1.size < 20 || m15.size < 20 || m5.size < 20 || m1.size < 20) &&
                        attempts < 5 && isActive) {
                        attempts++
                        kotlinx.coroutines.delay(250)
                        h1 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.H1, activeEx) ?: emptyList()
                        m15 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.M15, activeEx) ?: emptyList()
                        m5 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.M5, activeEx) ?: emptyList()
                        m1 = agu.analys.util.MtfCacheManager.getCachedCandles(symbol, Timeframe.M1, activeEx) ?: emptyList()
                    }

                    if (h1.size >= 20 && m15.size >= 20 && m5.size >= 20 && m1.size >= 20 &&
                        isMatchingSymbol(currentTick?.symbol ?: "", symbol)) {
                        h1Candles = h1.dropLast(1); m15Candles = m15.dropLast(1)
                        m5Candles = m5.dropLast(1)
                        m1Candles = m1.dropLast(1)
                        runScalping()
                    } else if (h1.size < 20 || m15.size < 20 || m5.size < 20 || m1.size < 20) {
                        lastMtfRefresh = 0L
                    }
                    refreshBtcPulseIfDue()
                }
                StrategyMode.SWING -> {
                    val h1Job = async {
                        TokocryptoMarketService.fetchCandles(symbol, Timeframe.H1, 200)
                    }
                    val d1Job = async {
                        TokocryptoMarketService.fetchCandles(symbol, Timeframe.D1, 100)
                    }
                    val h1 = h1Job.await()
                    val d1 = d1Job.await()
                    if (h1.isNotEmpty() && isMatchingSymbol(currentTick?.symbol ?: "", symbol)) {
                        val closedH1 = h1.dropLast(1)
                        synchronized(candlesH1) {
                            candlesH1.clear()
                            candlesH1.addAll(closedH1)
                        }
                        synchronized(candles1D) {
                            candles1D.clear()
                            candles1D.addAll(d1)
                        }
                        runSwing()
                    }
                }
                StrategyMode.OFFICE_DAILY -> {
                    val h4Job = async {
                        TokocryptoMarketService.fetchCandles(symbol, Timeframe.H4, 200)
                    }
                    val d1Job = async {
                        TokocryptoMarketService.fetchCandles(symbol, Timeframe.D1, 100)
                    }
                    val h4 = h4Job.await()
                    val d1 = d1Job.await()
                    if (h4.isNotEmpty() && isMatchingSymbol(currentTick?.symbol ?: "", symbol)) {
                        val closedH4 = h4.dropLast(1)
                        synchronized(candlesH4) {
                            candlesH4.clear()
                            candlesH4.addAll(closedH4)
                        }
                        synchronized(candles1D) {
                            candles1D.clear()
                            candles1D.addAll(d1)
                        }
                        runIntraday()
                    }
                }
            }
        }
    }

    /**
     * P0 — BTC gatekeeper. Menganalisis BTCUSDT 5M + 1M dari bursa aktif supaya tidak
     * mencampur data dua bursa.
     *
     * Candle terakhir dibuang (`dropLast(1)`) karena masih berjalan — veto harus dihitung
     * dari candle yang sudah tutup, sama seperti replay offline.
     */
    private fun refreshBtcPulseIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastBtcPulseRefresh < 60_000L) return
        if (btcPulseJob?.isActive == true) return
        lastBtcPulseRefresh = now
        btcPulseJob = scope.launch {
            val btcPair = "BTCUSDT"
            try {
                val m5Raw = TokocryptoMarketService.fetchCandles(btcPair, Timeframe.M5, 120)
                val m1Raw = TokocryptoMarketService.fetchCandles(btcPair, Timeframe.M1, 60)
                val closed5m = if (m5Raw.size > 1) m5Raw.dropLast(1) else m5Raw
                val closed1m = if (m1Raw.size > 1) m1Raw.dropLast(1) else m1Raw
                if (closed5m.size >= 22) {
                    agu.analys.engine.global.GlobalContextManager.updateBtcPulse(
                        btc5mCandles = closed5m,
                        btc1mCandles = closed1m,
                        config = agu.analys.engine.global.BtcPulseAnalyzer.Config.from(scalpingRuntimeConfig),
                        priceIsUsdt = true
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Pembatalan adalah hasil reset/offline, bukan kegagalan jaringan. Jangan
                // tampilkan sebagai status gagal; coba lagi pada tick berikutnya.
                lastBtcPulseRefresh = 0L
            } catch (e: Exception) {
                // Jangan mematikan evaluasi scalping hanya karena BTC tidak bisa diambil.
                // Konteks tidak diperbarui: veto terakhir dipertahankan (fail-closed), bukan
                // dilepas diam-diam saat data BTC sedang tidak tersedia.
                agu.analys.util.AppLogManager.aiEngine(
                    "BtcPulse",
                    "Gagal mengambil candle BTC $btcPair: ${e.message}"
                )
            }
        }
    }

    private fun runScalping() {
        val tick = currentTick ?: return
        // Snapshot sekali: cegah writer IO mengganti list di tengah evaluasi.
        val h1 = h1Candles
        val m15 = m15Candles
        val m5 = m5Candles
        val m1 = m1Candles
        if (h1.size < 20 || m15.size < 20 || m5.size < 20 || m1.size < 20) {
            val need = "H1 ${h1.size}/20 · M15 ${m15.size}/20 · M5 ${m5.size}/20 · M1 ${m1.size}/20"
            _signalState.value = AISignalState(
                action = SignalAction.HOLD,
                confidence = 15,
                marketSymbol = tick.symbol,
                entryPrice = tick.price,
                reasoning = listOf("Menunggu data MTF scalping ($need)."),
                timestamp = System.currentTimeMillis(),
                scalpingStage = ScalpingStage.WATCH,
                isOfflineMode = false
            )
            return
        }

        // N6 + N17 — evaluasi lewat seam bersama. Engine dan worker memakai fungsi yang sama,
        // sehingga skor yang tampil di notifikasi tidak bisa lahir dari perakitan input berbeda.
        val result = ModeSignalEvaluator.evaluate(
            ModeSignalInput(
                mode = StrategyMode.SCALPING,
                symbol = tick.symbol,
                price = tick.price,
                nowMs = tick.timestamp,
                change24h = tick.change24h,
                h1Candles = h1,
                // Seam menyintesis bar M15/M5/M1 yang masih berjalan dari tick di dalam.
                m15Candles = m15,
                m5Candles = m5,
                m1Candles = m1,
                formingVolume = currentFormingVolume,
                bids = currentOrderBookBids,
                asks = currentOrderBookAsks,
                fees = feeConfigForSymbol(tick.symbol),
                scalpingConfig = scalpingRuntimeConfig,
                globalContext = agu.analys.engine.global.GlobalContextManager.context.value
            )
        ) ?: return

        val tracked = agu.analys.engine.scalping.SignalLifecycleManager.process(
            tick.symbol,
            tick.price,
            result.signal,
            StrategyMode.SCALPING,
            // TTL wajib jam dinding. Jangan memakai `tick.timestamp` (waktu peristiwa bursa):
            // saat umpan tertinggal, sinyal basi bisa tetap hidup lebih lama dari 5 menit.
            config = scalpingRuntimeConfig
        )
        val finalSignal = (tracked.activeSignalState ?: result.signal).copy(
            marketSymbol = tick.symbol,
            lifecycleState = tracked.state
        )

        _indicators.value = result.indicators
        _signalState.value = finalSignal
        tracked.transition?.let { trans ->
            if (trans.previousState != trans.newState || trans.hasTriggeringTransition) {
                val actEmoji = when (trans.signal.action) {
                    agu.analys.model.SignalAction.BUY -> "🟢 BUY"
                    agu.analys.model.SignalAction.SELL -> "🔴 SELL"
                    agu.analys.model.SignalAction.HOLD -> "⚪ HOLD"
                }
                val reason = trans.signal.reasoning.firstOrNull() ?: "-"
                agu.analys.util.AppLogManager.aiEngine(
                    "SignalEngine",
                    "[${trans.symbol}] Transisi SCALPING: ${trans.previousState} ➔ ${trans.newState} | Sinyal: $actEmoji (Confidence: ${trans.signal.confidence}%) | $reason"
                )
            }
            if (trans.hasTriggeringTransition) {
                onCandidateSignalTransition?.invoke(trans)
            }
        }
    }

    private fun runSwing() {
        if (strategyMode != StrategyMode.SWING) return
        val tick = currentTick ?: return
        val history = synchronized(candlesH1) { candlesH1.toList() }
        val dailyHistory = synchronized(candles1D) { candles1D.toList() }
        if (history.isEmpty()) return

        // N6 + N17 — seam yang sama dengan worker. Anomali makro dihitung dari D1 di dalam
        // `ModeSignalEvaluator`, jadi tidak ada lagi jalur yang bisa lupa meneruskannya.
        val result = ModeSignalEvaluator.evaluate(
            ModeSignalInput(
                mode = StrategyMode.SWING,
                symbol = tick.symbol,
                price = tick.price,
                nowMs = tick.timestamp,
                h1Candles = history,
                d1Candles = dailyHistory,
                fees = feeConfigForSymbol(tick.symbol),
                globalContext = agu.analys.engine.global.GlobalContextManager.context.value
            )
        ) ?: return

        val tracked = agu.analys.engine.scalping.SignalLifecycleManager.process(tick.symbol, tick.price, result.signal, StrategyMode.SWING)
        val finalSignal = (tracked.activeSignalState ?: result.signal).copy(
            marketSymbol = tick.symbol,
            lifecycleState = tracked.state
        )

        _indicators.value = result.indicators
        _signalState.value = finalSignal
        tracked.transition?.let { trans ->
            if (trans.previousState != trans.newState || trans.hasTriggeringTransition) {
                val actEmoji = when (trans.signal.action) {
                    agu.analys.model.SignalAction.BUY -> "🟢 BUY"
                    agu.analys.model.SignalAction.SELL -> "🔴 SELL"
                    agu.analys.model.SignalAction.HOLD -> "⚪ HOLD"
                }
                val reason = trans.signal.reasoning.firstOrNull() ?: "-"
                agu.analys.util.AppLogManager.aiEngine(
                    "SignalEngine",
                    "[${trans.symbol}] Transisi SWING: ${trans.previousState} ➔ ${trans.newState} | Sinyal: $actEmoji (Confidence: ${trans.signal.confidence}%) | $reason"
                )
            }
            if (trans.hasTriggeringTransition) {
                onCandidateSignalTransition?.invoke(trans)
            }
        }
    }

    private fun runIntraday() {
        if (strategyMode != StrategyMode.OFFICE_DAILY) return
        val tick = currentTick ?: return
        val history = synchronized(candlesH4) { candlesH4.toList() }
        val dailyHistory = synchronized(candles1D) { candles1D.toList() }
        if (history.isEmpty()) return

        // N6 + N17 — seam yang sama dengan worker (lihat runSwing).
        val result = ModeSignalEvaluator.evaluate(
            ModeSignalInput(
                mode = StrategyMode.OFFICE_DAILY,
                symbol = tick.symbol,
                price = tick.price,
                nowMs = tick.timestamp,
                h4Candles = history,
                d1Candles = dailyHistory,
                fees = feeConfigForSymbol(tick.symbol),
                globalContext = agu.analys.engine.global.GlobalContextManager.context.value
            )
        ) ?: return

        val tracked = agu.analys.engine.scalping.SignalLifecycleManager.process(tick.symbol, tick.price, result.signal, StrategyMode.OFFICE_DAILY)
        val finalSignal = (tracked.activeSignalState ?: result.signal).copy(
            marketSymbol = tick.symbol,
            lifecycleState = tracked.state
        )

        _indicators.value = result.indicators
        _signalState.value = finalSignal
        tracked.transition?.let { trans ->
            if (trans.previousState != trans.newState || trans.hasTriggeringTransition) {
                val actEmoji = when (trans.signal.action) {
                    agu.analys.model.SignalAction.BUY -> "🟢 BUY"
                    agu.analys.model.SignalAction.SELL -> "🔴 SELL"
                    agu.analys.model.SignalAction.HOLD -> "⚪ HOLD"
                }
                val reason = trans.signal.reasoning.firstOrNull() ?: "-"
                agu.analys.util.AppLogManager.aiEngine(
                    "SignalEngine",
                    "[${trans.symbol}] Transisi INTRADAY: ${trans.previousState} ➔ ${trans.newState} | Sinyal: $actEmoji (Confidence: ${trans.signal.confidence}%) | $reason"
                )
            }
            if (trans.hasTriggeringTransition) {
                onCandidateSignalTransition?.invoke(trans)
            }
        }
    }
}
