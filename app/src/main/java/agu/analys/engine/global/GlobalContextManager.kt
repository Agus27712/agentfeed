package agu.analys.engine.global

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GlobalContextManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _context = MutableStateFlow(GlobalMarketContext())
    val context: StateFlow<GlobalMarketContext> = _context.asStateFlow()

    private val priceHistory = java.util.Collections.synchronizedList(mutableListOf<PriceTick>())
    private val HISTORY_WINDOW_MS = 3 * 60 * 1000L // 3 minutes window for crash detection

    private var isStarted = false

    // P0 — dua sumber veto yang harus digabung, bukan saling menimpa:
    // (a) flash crash dari ticker BTC, (b) BtcPulseAnalyzer 5M/1M.
    private var lastTickerContext: GlobalMarketContext? = null
    private var lastPulse: BtcPulseAnalyzer.Result? = null
    // Harga di Result memakai kuotasi bursa sumber (BTCUSDT atau BTCIDR). Jangan menulis
    // BTCIDR ke field `btcPriceUsdt` — itu mencampur dua mata uang.
    private var lastPulsePriceIsUsdt = false
    private var flashVeto = false
    private var flashVetoReason: String? = null
    private var flashRegime: GlobalRegime = GlobalRegime.SIDEWAYS

    fun start() {
        if (isStarted) return
        isStarted = true
        _context.value = _context.value.copy(isConnected = true, dataSource = "Tokocrypto")
    }

    fun stop() {
        isStarted = false
    }

    fun subscribeCoin(baseAsset: String) {
        start()
    }

    /**
     * Memperbarui status pasar global / BTC menggunakan data BTC dari Tokocrypto.
     *
     * [usdtRate] adalah kurs USDT→IDR. Ketika tidak diberikan (atau tidak valid),
     * nilai diambil dari [agu.analys.util.ExchangeRateManager] (rate real-time/cache).
     * Bila rate sama sekali belum tersedia, pembaruan dibatalkan — **tidak pernah**
     * memakai konstanta hardcode supaya harga $ tidak tercampur dengan asumsi kurs lama.
     *
     * @return `true` bila konteks berhasil diperbarui.
     */
    @Synchronized
    fun updateFallbackFromTicker(priceIdr: Double, changePct: Double, usdtRate: Double = 0.0): Boolean {
        val now = System.currentTimeMillis()
        if (priceIdr <= 0) return false

        // Sumber rate tunggal: parameter valid → exchange rate manager (real-time/cache).
        val effectiveUsdtRate = if (usdtRate > 0.0) {
            usdtRate
        } else {
            agu.analys.util.ExchangeRateManager.currentRate()
        }

        // Rate belum tersedia dari exchange → jangan mengarang nilai, lewati pembaruan.
        if (effectiveUsdtRate <= 0.0) return false

        val priceUsdt = priceIdr / effectiveUsdtRate

        priceHistory.add(PriceTick(priceUsdt, now))
        priceHistory.removeAll { now - it.timestamp > HISTORY_WINDOW_MS }

        val btcTicker = BtcTickerData(price = priceUsdt, changePct = changePct, source = "Tokocrypto")
        val tickerContext = evaluateGlobalContext(btcTicker, now)
        lastTickerContext = tickerContext
        flashVeto = tickerContext.isVetoActive
        flashVetoReason = tickerContext.vetoReason
        flashRegime = tickerContext.regime
        publish(now)
        return true
    }

    /**
     * P0 — pasang hasil analisis BTC 5M/1M. [config] dibaca dari runtime scalping supaya
     * ambang veto tidak di-hardcode di sini.
     */
    @Synchronized
    fun updateBtcPulse(
        btc5mCandles: List<agu.analys.model.CandleBar>,
        btc1mCandles: List<agu.analys.model.CandleBar> = emptyList(),
        config: BtcPulseAnalyzer.Config = BtcPulseAnalyzer.Config(),
        nowMs: Long = 0L,
        priceIsUsdt: Boolean = false
    ): BtcPulseAnalyzer.Result {
        val now = if (nowMs > 0L) nowMs else System.currentTimeMillis()
        val result = BtcPulseAnalyzer.evaluate(btc5mCandles, btc1mCandles, config)
        lastPulse = result
        lastPulsePriceIsUsdt = priceIsUsdt
        publish(now)
        return result
    }

    /** P0 — hasil pulse terakhir; `null` bila belum pernah dihitung. */
    @Synchronized
    fun lastBtcPulse(): BtcPulseAnalyzer.Result? = lastPulse

    private fun publish(now: Long) {
        val base = lastTickerContext ?: _context.value
        val pulse = lastPulse
        val reasons = buildList {
            if (flashVeto) flashVetoReason?.let { add(it) }
            if (pulse?.vetoActive == true) pulse.reason?.let { add(it) }
        }
        val veto = flashVeto || pulse?.vetoActive == true
        val regime = when {
            flashVeto -> GlobalRegime.FLASH_CRASH
            pulse?.vetoActive == true -> GlobalRegime.BEARISH
            else -> flashRegime
        }
        _context.value = base.copy(
            btcPriceUsdt = if (lastPulsePriceIsUsdt) {
                pulse?.btcPrice?.takeIf { it > 0.0 } ?: base.btcPriceUsdt
            } else {
                base.btcPriceUsdt
            },
            regime = regime,
            isVetoActive = veto,
            vetoReason = reasons.takeIf { it.isNotEmpty() }?.joinToString("; "),
            lastUpdateTime = now,
            isConnected = true,
            dataSource = base.dataSource
        )
    }

    private fun evaluateGlobalContext(ticker: BtcTickerData, now: Long): GlobalMarketContext {
        var regime = GlobalRegime.SIDEWAYS
        var isVeto = false
        var vetoReason: String? = null

        if (ticker.changePct > 2.0) {
            regime = GlobalRegime.BULLISH
        } else if (ticker.changePct < -2.0) {
            regime = GlobalRegime.BEARISH
        }

        // Flash Crash Detection: Check if price dropped significantly in the last 3 minutes
        if (priceHistory.isNotEmpty()) {
            val oldestTick = priceHistory.first()
            val dropPct = ((oldestTick.price - ticker.price) / oldestTick.price) * 100.0

            if (dropPct > 1.5 || ticker.changePct < -7.0) {
                regime = GlobalRegime.FLASH_CRASH
                isVeto = true
                vetoReason = "Flash Crash BTC (Drop: ${String.format("%.2f", dropPct)}% / 3m)"
            }
        }

        return GlobalMarketContext(
            btcPriceUsdt = ticker.price,
            btc24hChangePct = ticker.changePct,
            regime = regime,
            isVetoActive = isVeto,
            vetoReason = vetoReason,
            lastUpdateTime = now,
            isConnected = true,
            dataSource = ticker.source
        )
    }

    /** Hanya untuk unit test: hindari state singleton bocor antar-test. */
    @Synchronized
    internal fun resetForTest() {
        isStarted = false
        priceHistory.clear()
        lastTickerContext = null
        lastPulse = null
        lastPulsePriceIsUsdt = false
        flashVeto = false
        flashVetoReason = null
        flashRegime = GlobalRegime.SIDEWAYS
        _context.value = GlobalMarketContext()
    }

    data class PriceTick(val price: Double, val timestamp: Long)
}
