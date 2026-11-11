package agu.analys.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.engine.ModeSignalEvaluator
import agu.analys.engine.ModeSignalInput
import agu.analys.engine.global.GlobalContextManager
import agu.analys.engine.scalping.SignalLifecycleManager
import agu.analys.engine.scalping.TrackedSignal
import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.MarketKey
import agu.analys.model.MarketTick
import agu.analys.model.OrderBookItem
import agu.analys.model.Timeframe
import agu.analys.trading.SpotPositionStore
import agu.analys.util.AlertNotificationHelper
import agu.analys.util.AppPreferences
import agu.analys.util.PriceFormatter
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background worker untuk memindai kandidat sinyal BUY secara periodik.
 *
 * **N6 + N17 — satu mode, satu mesin, satu angka.** Worker hanya mengevaluasi **mode yang sedang
 * aktif** (`prefs.strategyMode`), memakai `ModeSignalEvaluator` yang sama dengan engine foreground.
 * Sebelumnya worker mengevaluasi SWING + INTRADAY + SCALPING sekaligus, berbagi
 * `SignalLifecycleManager` singleton dengan engine, dan merakit input evaluator sendiri — termasuk
 * **tidak** meneruskan penalti anomali makro untuk SWING. Akibatnya key `SYMBOL#MODE` yang sama
 * bisa diisi skor dari metode berbeda.
 *
 * **Batasi ke watchlist.** Pemindaian hanya menyentuh pair di watchlist — tidak pernah seluruh
 * pasar. Untuk SCALPING ada batas tambahan [MAX_SCALP_BACKGROUND_SYMBOLS] karena satu simbol
 * membutuhkan empat permintaan (H1, M15, M1, orderbook).
 */
class CandidateScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        if (!prefs.isNotificationsEnabled) {
            Timber.d("CandidateScanWorker: Notifikasi dinonaktifkan, lewati pekerjaan.")
            return Result.success()
        }

        val watchlist = prefs.getWatchlist()
        if (watchlist.isEmpty()) {
            return Result.success()
        }

        // N6 — worker hanya menghitung mode yang sedang dipakai. Mode lain tidak punya produsen
        // latar; notifikasinya memang tidak dikirim saat mode itu tidak aktif.
        val mode = prefs.strategyMode
        val currentExchange = prefs.marketDataSource.name
        val positionStore = SpotPositionStore(applicationContext)

        // 1. Filter koin: lewati koin yang sedang HOLDING (di akun real maupun simulasi untuk bursa aktif)
        val eligibleSymbols = watchlist.filter { rawSymbol ->
            val clean = normalizeSymbol(rawSymbol)
            !positionStore.get(clean, isReal = true, exchange = currentExchange).isHolding &&
                    !positionStore.get(clean, isReal = false, exchange = currentExchange).isHolding
        }

        if (eligibleSymbols.isEmpty()) {
            Timber.d("CandidateScanWorker: Semua koin watchlist sedang HOLDING atau kosong.")
            return Result.success()
        }

        agu.analys.util.AppLogManager.service(
            "CandidateScan",
            "Memulai pemindaian background ($currentExchange, mode ${modeLabel(mode)}) untuk " +
                "${eligibleSymbols.size} koin watchlist..."
        )

        try {
            val tickMap = fetchTicks(eligibleSymbols)
            if (tickMap.isEmpty()) {
                return Result.success()
            }

            var evaluated = 0
            // P0 — satu kali per pemindaian: BTC 5M/1M untuk gatekeeper. Biayanya 2 permintaan,
            // bukan per-simbol, sehingga tidak menambah beban per koin.
            if (mode == StrategyMode.SCALPING) {
                refreshBtcPulse(prefs.scalpingRuntimeConfig)
            }
            for (rawSymbol in eligibleSymbols) {
                // Hemat baterai: scalping butuh 5 permintaan per simbol (H1, M15, M5, M1,
                // orderbook), jadi dibatasi.
                if (mode == StrategyMode.SCALPING && evaluated >= MAX_SCALP_BACKGROUND_SYMBOLS) break
                evaluated++

                val cleanSymbol = normalizeSymbol(rawSymbol)
                val compactSym = TokocryptoMarketService.toTokocryptoSymbol(cleanSymbol)
                val tick = tickMap[cleanSymbol] ?: tickMap[compactSym] ?: continue
                val price = tick.price
                if (price <= 0.0) continue

                // Cek ulang holding status
                val isHolding = positionStore.get(cleanSymbol, isReal = true, exchange = currentExchange).isHolding ||
                        positionStore.get(cleanSymbol, isReal = false, exchange = currentExchange).isHolding
                if (isHolding) continue

                try {
                    val input = buildInput(mode, cleanSymbol, tick, prefs) ?: continue
                    val evaluation = ModeSignalEvaluator.evaluate(input) ?: continue
                    val tracked = SignalLifecycleManager.process(
                        symbol = cleanSymbol,
                        currentPrice = price,
                        rawSignal = evaluation.signal,
                        mode = mode,
                        // TTL berbasis jam dinding, bukan waktu peristiwa bursa.
                        config = prefs.scalpingRuntimeConfig
                    )
                    if (tracked.transition?.hasTriggeringTransition == true) {
                        notifyCandidate(
                            mode = mode,
                            marketKey = MarketKey.resolve(cleanSymbol, currentExchange),
                            price = price,
                            tracked = tracked,
                            fallback = evaluation.signal,
                            prefs = prefs,
                            positionStore = positionStore,
                            currentExchange = currentExchange,
                            cleanSymbol = cleanSymbol
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "CandidateScanWorker: Evaluasi ${modeLabel(mode)} gagal untuk $cleanSymbol")
                }
            }

            agu.analys.util.AppLogManager.service(
                "CandidateScan",
                "Pemindaian background ($currentExchange, mode ${modeLabel(mode)}) selesai. " +
                    "${eligibleSymbols.size} koin dievaluasi."
            )
            return Result.success()
        } catch (e: Exception) {
            Timber.e(e, "CandidateScanWorker: Terjadi kesalahan saat memindai kandidat")
            return Result.retry()
        }
    }

    /**
     * Merakit input untuk **satu** mode. Candle yang masih berjalan dibuang (`dropLast(1)`) supaya
     * bentuknya sama dengan buffer engine; `ModeSignalEvaluator` yang menyintesis ulang bar berjalan
     * dari tick.
     */
    private suspend fun buildInput(
        mode: StrategyMode,
        symbol: String,
        tick: MarketTick,
        prefs: AppPreferences
    ): ModeSignalInput? {
        return when (mode) {
            StrategyMode.SWING -> {
                val h1 = fetchCandles(symbol, Timeframe.H1, 200)
                if (h1.size < ModeSignalEvaluator.MIN_CANDLES) return null
                val d1 = fetchCandles(symbol, Timeframe.D1, 100)
                ModeSignalInput(
                    mode = mode,
                    symbol = symbol,
                    price = tick.price,
                    nowMs = tick.timestamp,
                    change24h = tick.change24h,
                    h1Candles = h1.dropLast(1),
                    d1Candles = d1,
                    fees = prefs.feeConfigFor(symbol),
                    globalContext = GlobalContextManager.context.value
                )
            }

            StrategyMode.OFFICE_DAILY -> {
                val h4 = fetchCandles(symbol, Timeframe.H4, 200)
                if (h4.size < ModeSignalEvaluator.MIN_CANDLES) return null
                val d1 = fetchCandles(symbol, Timeframe.D1, 100)
                ModeSignalInput(
                    mode = mode,
                    symbol = symbol,
                    price = tick.price,
                    nowMs = tick.timestamp,
                    change24h = tick.change24h,
                    h4Candles = h4.dropLast(1),
                    d1Candles = d1,
                    fees = prefs.feeConfigFor(symbol),
                    globalContext = GlobalContextManager.context.value
                )
            }

            StrategyMode.SCALPING -> {
                val h1 = fetchCandles(symbol, Timeframe.H1, 60)
                val m15 = fetchCandles(symbol, Timeframe.M15, 60)
                val m5 = fetchCandles(symbol, Timeframe.M5, 60)
                val m1 = fetchCandles(symbol, Timeframe.M1, 60)
                if (h1.size < ModeSignalEvaluator.MIN_CANDLES ||
                    m15.size < ModeSignalEvaluator.MIN_CANDLES ||
                    m5.size < ModeSignalEvaluator.MIN_CANDLES ||
                    m1.size < ModeSignalEvaluator.MIN_CANDLES
                ) {
                    return null
                }
                // Orderbook nyata: tanpa ini gerbang Step2 harus GAGAL, bukan dilewati. Jalur
                // diagnostik `diagnosticIgnoreOrderBookWhenUnavailable = true` sengaja tidak dipakai —
                // skor yang lebih tinggi dari engine tidak boleh masuk ke notifikasi.
                val (bids, asks) = fetchOrderBook(symbol)
                ModeSignalInput(
                    mode = mode,
                    symbol = symbol,
                    price = tick.price,
                    nowMs = tick.timestamp,
                    change24h = tick.change24h,
                    h1Candles = h1.dropLast(1),
                    m15Candles = m15.dropLast(1),
                    m5Candles = m5.dropLast(1),
                    m1Candles = m1.dropLast(1),
                    bids = bids,
                    asks = asks,
                    fees = prefs.feeConfigFor(symbol),
                    scalpingConfig = prefs.scalpingRuntimeConfig,
                    globalContext = GlobalContextManager.context.value
                )
            }
        }
    }

    private suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int
    ): List<CandleBar> = TokocryptoMarketService.fetchCandles(symbol, timeframe, limit)

    private suspend fun fetchOrderBook(
        symbol: String
    ): Pair<List<OrderBookItem>, List<OrderBookItem>> = try {
        TokocryptoMarketService.fetchOrderBook(symbol)
    } catch (e: Exception) {
        Timber.w(e, "CandidateScanWorker: Orderbook gagal untuk $symbol")
        emptyList<OrderBookItem>() to emptyList()
    }

    /**
     * P0 — BTC gatekeeper untuk pemindaian latar.
     *
     * Veto dihitung dari candle yang **sudah tutup**; candle berjalan dibuang. Bila BTC gagal
     * diambil, konteks tidak diperbarui dan veto terakhir dipertahankan (fail-closed) — bukan
     * dilepas diam-diam saat data BTC sedang tidak tersedia.
     */
    private suspend fun refreshBtcPulse(
        config: agu.analys.engine.scalping.ScalpingRuntimeConfig
    ) {
        val btcPair = "BTCUSDT"
        try {
            val m5Raw = fetchCandles(btcPair, Timeframe.M5, 120)
            val m1Raw = fetchCandles(btcPair, Timeframe.M1, 60)
            val closed5m = if (m5Raw.size > 1) m5Raw.dropLast(1) else m5Raw
            val closed1m = if (m1Raw.size > 1) m1Raw.dropLast(1) else m1Raw
            if (closed5m.size >= 22) {
                GlobalContextManager.updateBtcPulse(
                    btc5mCandles = closed5m,
                    btc1mCandles = closed1m,
                    config = agu.analys.engine.global.BtcPulseAnalyzer.Config.from(config),
                    priceIsUsdt = true
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "CandidateScanWorker: BTC pulse gagal ($btcPair)")
        }
    }

    /** Hanya pair di watchlist yang diambil tick-nya; tidak pernah seluruh pasar. */
    private suspend fun fetchTicks(
        eligibleSymbols: List<String>
    ): Map<String, MarketTick> {
        val tickMap = mutableMapOf<String, MarketTick>()
        val ticks = TokocryptoMarketService.fetchTickers(eligibleSymbols)
        for (t in ticks) {
            tickMap[t.symbol.uppercase()] = t
        }
        return tickMap
    }

    private fun notifyCandidate(
        mode: StrategyMode,
        marketKey: MarketKey,
        price: Double,
        tracked: TrackedSignal,
        fallback: AISignalState,
        prefs: AppPreferences,
        positionStore: SpotPositionStore,
        currentExchange: String,
        cleanSymbol: String
    ) {
        if (positionStore.get(cleanSymbol, isReal = prefs.isRealBuyMode, exchange = currentExchange).isHolding) {
            return
        }
        val priceStr = PriceFormatter.formatPrice(price, showSymbol = true, quoteAsset = marketKey.quote)
        agu.analys.util.AppLogManager.service(
            "CandidateFound",
            "🔔 [${modeLabel(mode)} - $currentExchange] Kandidat BUY terdeteksi untuk " +
                "${marketKey.formattedPair()} @ $priceStr! Mengirim notifikasi..."
        )
        // `activeSignalState` adalah sinyal yang sama dengan yang ditampilkan kartu engine
        // (engine juga memakai `tracked.activeSignalState ?: result.signal`). Satu sumber angka.
        AlertNotificationHelper.sendCandidateFoundNotification(
            context = applicationContext,
            marketKey = marketKey,
            strategyMode = mode,
            signal = tracked.activeSignalState ?: fallback
        )
    }

    companion object {
        const val WORK_NAME = "CandidateScanPeriodicWork"
        /** Batas simbol untuk evaluasi scalping background (hemat baterai/API). */
        const val MAX_SCALP_BACKGROUND_SYMBOLS = 10

        private fun normalizeSymbol(symbol: String): String =
            symbol.uppercase().replace("/", "").replace("-", "")

        private fun modeLabel(mode: StrategyMode): String = when (mode) {
            StrategyMode.SCALPING -> "SCALPING"
            StrategyMode.OFFICE_DAILY -> "INTRADAY"
            StrategyMode.SWING -> "SWING"
        }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<CandidateScanWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
