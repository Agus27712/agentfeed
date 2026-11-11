package agu.analys.database

import agu.analys.trading.TradeSignalSnapshot
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Manajer pencatatan siklus hidup perdagangan (Trade Lifecycle Recorder).
 * Mengintegrasikan alur:
 * Pengeluaran Sinyal Buy (Mode & Perhitungan) -> User Buy -> Durasi Hold -> Sell (Profit/Loss).
 */
class TradeHistoryRecorder(
    private val dao: TradeHistoryRecordDao,
    private val scope: CoroutineScope
) {
    private val tickThrottleMap = ConcurrentHashMap<String, Long>()

    val allRecordsFlow: Flow<List<TradeHistoryRecordEntity>> = dao.getAllRecordsFlow()

    fun getRecordsByExchangeFlow(exchange: String): Flow<List<TradeHistoryRecordEntity>> =
        dao.getRecordsByExchangeFlow(exchange)

    fun getRecordsBySymbolFlow(symbol: String): Flow<List<TradeHistoryRecordEntity>> =
        dao.getRecordsBySymbolFlow(symbol)

    fun getRecordsBySymbolAndExchangeFlow(symbol: String, exchange: String): Flow<List<TradeHistoryRecordEntity>> =
        dao.getRecordsBySymbolAndExchangeFlow(symbol, exchange)

    fun getHoldingRecordsFlow(): Flow<List<TradeHistoryRecordEntity>> =
        dao.getHoldingRecordsFlow()

    fun getHoldingRecordsByExchangeFlow(exchange: String): Flow<List<TradeHistoryRecordEntity>> =
        dao.getHoldingRecordsByExchangeFlow(exchange)

    /**
     * Tahap 1 & 2: Catat pengeluaran sinyal buy + eksekusi pembelian pengguna.
     */
    fun recordBuy(
        symbol: String,
        isReal: Boolean,
        strategyMode: String,
        buyPrice: Double,
        buyQuantity: Double,
        buyTotalIdr: Double,
        buyOrderType: String = "LIMIT",
        snapshot: TradeSignalSnapshot? = null,
        signalPrice: Double = buyPrice,
        // J9 — TIDAK ada default karangan. Bila pemanggil tidak mengirim dan snapshot kosong,
        // nilainya 0 ("tidak diketahui"), bukan 75. Dulu di sini `?: 75`.
        signalConfidence: Int? = null,
        targetPrice1: Double = 0.0,
        targetPrice2: Double = 0.0,
        stopLossPrice: Double = 0.0,
        customUuid: String? = null,
        exchange: String = "TOKOCRYPTO",
        // P0-3 — waktu sinyal benar-benar terbit (dari engine), bukan waktu klik order.
        // Bila null/kosong, jatuh ke waktu beli supaya tidak ada regresi pada pemanggil lama.
        signalTime: Long? = null,
        // P0-5 — status trailing nyata saat posisi dibuka (dari SpotPositionStore).
        isTrailingUsed: Boolean = false,
        trailingPercent: Double? = null,
        trailingPeakPrice: Double? = null
    ): String {
        val tradeUuid = customUuid ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val normSymbol = symbol.uppercase().replace("_", "")

        // Bangun ringkasan perhitungan teknikal saat sinyal buy dikeluarkan
        val calculationSummary = buildCalculationSummary(snapshot, strategyMode)
        val reasons = snapshot?.reasons?.joinToString(" • ")
            ?: "Sinyal indikator teknikal terkonfirmasi pada mode ${strategyMode.uppercase()}"
        val snapshotJson = snapshot?.toJson()?.toString()

        // Biaya dari FeeSchedule (satu sumber kebenaran), bukan angka flat 0,3% yang salah
        // untuk Tokocrypto USDT (seharusnya 0,4044%) maupun Tokocrypto IDR.
        val quoteAsset = agu.analys.util.PriceFormatter.extractQuote(normSymbol)
        val feeRate = agu.analys.config.FeeSchedule.buyRate(exchange, quoteAsset)
        val feeIdr = buyTotalIdr * feeRate

        scope.launch(Dispatchers.IO) {
            try {
                val entity = TradeHistoryRecordEntity(
                    tradeUuid = tradeUuid,
                    symbol = normSymbol,
                    isRealTrade = isReal,
                    strategyMode = strategyMode.uppercase(),
                    exchange = exchange.uppercase(),
                    signalTime = signalTime?.takeIf { it > 0L } ?: now,
                    signalPrice = if (signalPrice > 0) signalPrice else buyPrice,
                    signalConfidence = TradeSignalConfidence.resolve(
                        explicit = signalConfidence,
                        fromSnapshot = snapshot?.confidenceScore
                    ),
                    signalCalculationSummary = calculationSummary,
                    signalReasons = reasons,
                    signalSnapshotJson = snapshotJson,
                    targetPrice1 = targetPrice1,
                    targetPrice2 = targetPrice2,
                    stopLossPrice = stopLossPrice,
                    buyTime = now,
                    buyPrice = buyPrice,
                    buyQuantity = buyQuantity,
                    buyTotalIdr = buyTotalIdr,
                    buyFeeIdr = feeIdr,
                    buyOrderType = buyOrderType,
                    status = "HOLDING",
                    holdingDurationMs = 0L,
                    peakPriceDuringHold = buyPrice,
                    troughPriceDuringHold = buyPrice,
                    maxProfitPctDuringHold = 0.0,
                    maxDrawdownPctDuringHold = 0.0,
                    // P0-5 — ambil dari state nyata, bukan hardcode false.
                    isTrailingUsed = isTrailingUsed,
                    trailingLockPrice = null,
                    trailingPercentUsed = trailingPercent,
                    trailingPeakPriceUsed = trailingPeakPrice
                )
                dao.insertRecord(entity)
            } catch (_: Exception) {}
        }
        return tradeUuid
    }

    /**
     * Tahap 3: Update durasi hold dan evolusi harga (peak & drawdown) saat market tick berjalan.
     */
    fun processPriceTick(
        symbol: String,
        currentPrice: Double,
        exchange: String = "TOKOCRYPTO",
        // P0-6 — high/low candle terakhir bila tersedia. Tanpa ini Peak/Drawdown hanya mengikuti
        // harga yang kebetulan tersampling (throttle 1,5 s) sehingga selalu understate.
        candleHigh: Double = 0.0,
        candleLow: Double = 0.0
    ) {
        if (currentPrice <= 0.0 || symbol.isBlank()) return
        val normSymbol = symbol.uppercase().replace("_", "")
        val normExchange = exchange.uppercase()
        val now = System.currentTimeMillis()

        // Throttle agar tidak membebani database Room
        val throttleKey = "${normExchange}_${normSymbol}"
        val lastUpdate = tickThrottleMap[throttleKey] ?: 0L
        if (now - lastUpdate < 1500L) return
        tickThrottleMap[throttleKey] = now

        scope.launch(Dispatchers.IO) {
            try {
                // J5 — hanya record milik bursa ini. Fallback "semua bursa" DIHAPUS: harga dari
                // satu bursa tidak boleh memperbarui peak/trough/drawdown record bursa lain.
                val holdings = HoldingResolution.holdingsForPriceTick(normSymbol, normExchange) { ex ->
                    dao.getHoldingRecordsByExchange(ex)
                }
                if (holdings.isEmpty()) return@launch

                val usedCandle = candleHigh > 0.0 || candleLow > 0.0
                for (record in holdings) {
                    val duration = (now - record.buyTime).coerceAtLeast(0L)
                    // P0-6 — pakai ekstrem candle bila tersedia, kalau tidak jatuh ke harga tick.
                    val highRef = if (candleHigh > 0.0) max(candleHigh, currentPrice) else currentPrice
                    val lowRef = if (candleLow > 0.0) min(candleLow, currentPrice) else currentPrice

                    val highPnlPct = if (record.buyPrice > 0) ((highRef - record.buyPrice) / record.buyPrice) * 100.0 else 0.0
                    val lowPnlPct = if (record.buyPrice > 0) ((lowRef - record.buyPrice) / record.buyPrice) * 100.0 else 0.0

                    val newPeak = if (record.peakPriceDuringHold <= 0.0) highRef else max(record.peakPriceDuringHold, highRef)
                    val newTrough = if (record.troughPriceDuringHold <= 0.0) lowRef else min(record.troughPriceDuringHold, lowRef)
                    val newMaxProfit = max(record.maxProfitPctDuringHold, max(0.0, highPnlPct))
                    val newMaxDrawdown = min(record.maxDrawdownPctDuringHold, min(0.0, lowPnlPct))

                    val updated = record.copy(
                        holdingDurationMs = duration,
                        peakPriceDuringHold = newPeak,
                        troughPriceDuringHold = newTrough,
                        maxProfitPctDuringHold = newMaxProfit,
                        maxDrawdownPctDuringHold = newMaxDrawdown,
                        peakTrackedFromCandle = record.peakTrackedFromCandle || usedCandle
                    )
                    dao.updateRecord(updated)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Update all holding records from a whole-market batch price update
     */
    fun processBatchPriceTicks(priceMap: Map<String, Double>, exchange: String = "TOKOCRYPTO") {
        if (priceMap.isEmpty()) return
        val normExchange = exchange.uppercase()
        val now = System.currentTimeMillis()

        scope.launch(Dispatchers.IO) {
            try {
                val holdings = dao.getHoldingRecordsByExchange(normExchange)
                if (holdings.isEmpty()) return@launch

                for (record in holdings) {
                    val currentPrice = priceMap[record.symbol]
                        ?: priceMap[record.symbol.lowercase()]
                        ?: priceMap["${record.symbol.removeSuffix("IDR").removeSuffix("idr")}idr"]
                        ?: priceMap["${record.symbol.removeSuffix("IDR").removeSuffix("idr")}IDR"]
                        ?: continue

                    if (currentPrice <= 0.0) continue

                    val duration = (now - record.buyTime).coerceAtLeast(0L)
                    val rawPnlPct = if (record.buyPrice > 0) {
                        ((currentPrice - record.buyPrice) / record.buyPrice) * 100.0
                    } else 0.0

                    val newPeak = if (record.peakPriceDuringHold <= 0.0) currentPrice else max(record.peakPriceDuringHold, currentPrice)
                    val newTrough = if (record.troughPriceDuringHold <= 0.0) currentPrice else min(record.troughPriceDuringHold, currentPrice)
                    val newMaxProfit = max(record.maxProfitPctDuringHold, max(0.0, rawPnlPct))
                    val newMaxDrawdown = min(record.maxDrawdownPctDuringHold, min(0.0, rawPnlPct))

                    val updated = record.copy(
                        holdingDurationMs = duration,
                        peakPriceDuringHold = newPeak,
                        troughPriceDuringHold = newTrough,
                        maxProfitPctDuringHold = newMaxProfit,
                        maxDrawdownPctDuringHold = newMaxDrawdown
                    )
                    dao.updateRecord(updated)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Tahap 4: Catat penjualan (Sell), durasi hold final, serta realisasi Profit/Loss (IDR & %).
     */
    fun recordSell(
        symbol: String,
        isReal: Boolean,
        sellPrice: Double,
        sellQuantity: Double,
        sellReason: String = "MANUAL_SELL",
        strategyMode: String? = null,
        customPnlIdr: Double? = null,
        customPnlPercent: Double? = null,
        tradeUuid: String? = null,
        isTrailingUsed: Boolean = false,
        trailingLockPrice: Double? = null,
        exchange: String = "TOKOCRYPTO",
        // P0-5 — persentase & puncak trailing nyata; sebelumnya tidak pernah tersimpan.
        trailingPercent: Double? = null,
        trailingPeakPrice: Double? = null
    ) {
        if (sellPrice <= 0.0 || sellQuantity <= 0.0) return
        val normSymbol = symbol.uppercase().replace("_", "")
        val normExchange = exchange.uppercase()
        val now = System.currentTimeMillis()

        scope.launch(Dispatchers.IO) {
            try {
                // J5 — cari record HOLDING aktif yang cocok. Fallback lintas bursa DIHAPUS:
                // BTCIDR/ETHIDR/XAUTIDR adalah pair IDR, jadi fallback
                // dulu bisa menutup record bursa yang salah dengan data bursa ini.
                val targetRecord = HoldingResolution.resolveForSell(
                    tradeUuid = tradeUuid,
                    symbol = normSymbol,
                    isReal = isReal,
                    exchange = normExchange,
                    byUuid = { dao.getRecordByUuid(it) },
                    bySymbolAndExchange = { s, r, e ->
                        dao.getActiveHoldingForSymbolAndExchange(s, r, e)
                    }
                )

                // Biaya jual & beli dari FeeSchedule — sisi jual lebih mahal dari sisi beli
                // pada pair IDR, dan biaya beli tetap dipakai untuk estimasi harga pokok.
                val quoteAsset = agu.analys.util.PriceFormatter.extractQuote(normSymbol)
                val feeRate = agu.analys.config.FeeSchedule.sellRate(normExchange, quoteAsset)
                val buyFeeRate = agu.analys.config.FeeSchedule.buyRate(normExchange, quoteAsset)
                val sellTotalIdr = sellPrice * sellQuantity
                val sellFeeIdr = sellTotalIdr * feeRate

                if (targetRecord != null && targetRecord.status == "HOLDING") {
                    val durationMs = (now - targetRecord.buyTime).coerceAtLeast(1000L)
                    val totalCost = targetRecord.buyTotalIdr + targetRecord.buyFeeIdr
                    val netProceeds = sellTotalIdr - sellFeeIdr
                    val computedPnlIdr = customPnlIdr ?: (netProceeds - totalCost)
                    val computedPnlPct = customPnlPercent ?: if (totalCost > 0) ((computedPnlIdr / totalCost) * 100.0) else 0.0
                    val isWin = computedPnlIdr >= 0.0

                    val closedRecord = targetRecord.copy(
                        status = "CLOSED",
                        holdingDurationMs = durationMs,
                        sellTime = now,
                        sellPrice = sellPrice,
                        sellQuantity = sellQuantity,
                        sellTotalIdr = sellTotalIdr,
                        sellFeeIdr = sellFeeIdr,
                        sellReason = sellReason,
                        pnlIdr = computedPnlIdr,
                        pnlPercent = computedPnlPct,
                        isProfit = isWin,
                        isTrailingUsed = isTrailingUsed || targetRecord.isTrailingUsed,
                        trailingLockPrice = trailingLockPrice ?: targetRecord.trailingLockPrice,
                        trailingPercentUsed = trailingPercent ?: targetRecord.trailingPercentUsed,
                        trailingPeakPriceUsed = trailingPeakPrice ?: targetRecord.trailingPeakPriceUsed
                    )
                    dao.updateRecord(closedRecord)
                } else {
                    // B1 — Hapus fallback yang MENGARANG trade. Sebelumnya, ketika tidak ada
                    // record open, sistem membuat record palsu dengan:
                    //   - estBuyPrice = sellPrice * 0.98 (mengarang harga beli)
                    //   - signalConfidence = 80 (mengarang skor)
                    //   - holdingDurationMs = 900_000L (mengarang durasi 15 menit)
                    // Trade palsu ini terlihat sah di statistik dan merusak audit trail.
                    // Sekarang: return early — jangan buat record dari data yang tidak diketahui.
                    return@launch
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun deleteRecord(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteRecordById(id)
    }

    suspend fun clearAllRecords() = withContext(Dispatchers.IO) {
        dao.clearAllRecords()
    }

    /**
     * Membangun ringkasan perhitungan indikator teknikal saat sinyal dikeluarkan.
     */
    private fun buildCalculationSummary(snapshot: TradeSignalSnapshot?, mode: String): String {
        if (snapshot == null) {
            return "Perhitungan Mode: ${mode.uppercase()} (Kombinasi RSI, MACD Trend, & Order Book Imbalance)"
        }
        val parts = mutableListOf<String>()

        snapshot.rsi14?.let { parts.add("RSI(14)=${String.format(Locale.US, "%.1f", it)}") }
        // P1-7 — histogram MACD bukan persilangan. Label "Golden/Death Cross" menyesatkan
        // (nilai ≈ 0 pun dicap "cross"); sekarang menyebut histogram dan tandanya secara eksplisit.
        // P1-8 — cetak dengan digit signifikan, bukan Double.toString() (mis. 9.010876114987423E-4).
        snapshot.macdHist?.let {
            val sign = if (it >= 0.0) "> 0" else "< 0"
            parts.add("MACD Histogram $sign (${String.format(Locale.US, "%.4g", it)})")
        }
        if (snapshot.ema20 != null && snapshot.ema50 != null) {
            val relation = if (snapshot.ema20 > snapshot.ema50) "EMA20 > EMA50 (Uptrend)" else "EMA20 < EMA50 (Downtrend)"
            parts.add(relation)
        }
        snapshot.bidRatioPct?.let { parts.add("Bid Depth=${PriceFormatter.formatPercentage(it, includePlusSign = false, decimals = 1)}") }
        snapshot.orderBookPressure?.let {
            parts.add(if (it > 0) "Pressure=+$it% (Buyer)" else "Pressure=$it% (Seller)")
        }
        snapshot.bbWidthPct?.let { parts.add("BB Width=${PriceFormatter.formatPercentage(it, includePlusSign = false, decimals = 2)}") }
        snapshot.rvol?.let { parts.add("RVOL=${String.format(Locale.US, "%.2f", it)}x") }
        // P0-2 — basis bar dicantumkan agar nilai indikator di atas bisa direproduksi dari candle bursa.
        snapshot.indicatorBarTime?.takeIf { it > 0L }?.let {
            val state = if (snapshot.indicatorBarClosed == true) "tutup" else "berjalan"
            val barClock = SimpleDateFormat("HH:mm", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("Asia/Jakarta") }
                .format(Date(it))
            parts.add("Basis bar M1 $barClock WIB ($state)")
        }

        return if (parts.isNotEmpty()) parts.joinToString(" • ") else "Mode ${mode.uppercase()} terverifikasi indikator multi-timeframe."
    }

    /**
     * Muat data sampel daur hidup trade agar user dapat menguji dan melihat visualisasi siklus trade secara langsung.
     * @param force jika true, sampel baru ditambahkan meskipun database sudah memiliki record.
     */
    suspend fun seedSampleTradeJourneys(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && dao.getRecordCount() > 0) return@withContext

        val now = System.currentTimeMillis()
        val uidSuffix = if (force) "-${(System.currentTimeMillis() % 100000)}" else ""
        val samples = listOf(
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-btc-01$uidSuffix",
                isSample = true,
                symbol = "BTCIDR",
                isRealTrade = true,
                strategyMode = "SCALPING",
                exchange = "TOKOCRYPTO",
                signalTime = now - 3600_000L * 3,
                signalPrice = 1450000000.0,
                signalConfidence = 88,
                signalCalculationSummary = "RSI(14)=32.4 • MACD=Golden Cross (+0.018) • EMA20 > EMA50 (Uptrend) • Bid Depth=68.5% • Pressure=+24% (Buyer)",
                signalReasons = "Breakout resistance M15 + lonjakan volume 3.4x rata-rata + order book buyer tebal.",
                targetPrice1 = 1485000000.0,
                targetPrice2 = 1510000000.0,
                stopLossPrice = 1430000000.0,
                buyTime = now - 3600_000L * 3 + 12_000L,
                buyPrice = 1452000000.0,
                buyQuantity = 0.00344352,
                buyTotalIdr = 5000000.0,
                buyFeeIdr = 15000.0,
                buyOrderType = "LIMIT",
                status = "CLOSED",
                holdingDurationMs = 1120_000L, // ~18 menit 40 detik
                peakPriceDuringHold = 1492000000.0,
                troughPriceDuringHold = 1448000000.0,
                maxProfitPctDuringHold = 2.75,
                maxDrawdownPctDuringHold = -0.27,
                isTrailingUsed = false,
                sellTime = now - 3600_000L * 3 + 1132_000L,
                sellPrice = 1485000000.0,
                sellQuantity = 0.00344352,
                sellTotalIdr = 5113627.0,
                sellFeeIdr = 15340.0,
                sellReason = "HIT_TP1",
                pnlIdr = 83287.0,
                pnlPercent = 1.66,
                isProfit = true
            ),
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-eth-02$uidSuffix",
                isSample = true,
                symbol = "ETHIDR",
                isRealTrade = false,
                strategyMode = "SCALPING",
                exchange = "TOKOCRYPTO",
                signalTime = now - 3600_000L * 6,
                signalPrice = 52000000.0,
                signalConfidence = 84,
                signalCalculationSummary = "RSI(14)=46.2 • MACD=Golden Cross (+0.042) • EMA20 > EMA50 (Uptrend) • Bid Depth=62.0% • Pressure=+18% (Buyer)",
                signalReasons = "Momentum expansion RSI crossing 50 + konfirmasi volume buy bursa.",
                targetPrice1 = 53500000.0,
                targetPrice2 = 54600000.0,
                stopLossPrice = 51200000.0,
                buyTime = now - 3600_000L * 6 + 8_000L,
                buyPrice = 52100000.0,
                buyQuantity = 0.09596929,
                buyTotalIdr = 5000000.0,
                buyFeeIdr = 15000.0,
                buyOrderType = "LIMIT",
                status = "CLOSED",
                holdingDurationMs = 2840_000L, // ~47 menit 20 detik
                peakPriceDuringHold = 54200000.0,
                troughPriceDuringHold = 51900000.0,
                maxProfitPctDuringHold = 4.03,
                maxDrawdownPctDuringHold = -0.38,
                isTrailingUsed = true,
                trailingLockPrice = 53800000.0,
                sellTime = now - 3600_000L * 6 + 2848_000L,
                sellPrice = 53800000.0,
                sellQuantity = 0.09596929,
                sellTotalIdr = 5163147.0,
                sellFeeIdr = 15489.0,
                sellReason = "TRAILING_STOP",
                pnlIdr = 132658.0,
                pnlPercent = 2.65,
                isProfit = true
            ),
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-sol-03$uidSuffix",
                isSample = true,
                symbol = "SOLIDR",
                isRealTrade = false,
                strategyMode = "SECOND_WAVE",
                exchange = "TOKOCRYPTO",
                signalTime = now - 3600_000L * 12,
                signalPrice = 2850000.0,
                signalConfidence = 76,
                signalCalculationSummary = "RSI(14)=38.0 • EMA20=2830000 • Bid Depth=59.4% • Pressure=+12% (Buyer) • Pattern=Bullish Hammer",
                signalReasons = "Pantulan gelombang 2 (Fibonacci 0.618) pada zona support kuat.",
                targetPrice1 = 2960000.0,
                targetPrice2 = 3050000.0,
                stopLossPrice = 2780000.0,
                buyTime = now - 3600_000L * 12 + 15_000L,
                buyPrice = 2855000.0,
                buyQuantity = 1.05078809,
                buyTotalIdr = 3000000.0,
                buyFeeIdr = 9000.0,
                buyOrderType = "LIMIT",
                status = "CLOSED",
                holdingDurationMs = 5040_000L, // ~1 jam 24 menit
                peakPriceDuringHold = 3065000.0,
                troughPriceDuringHold = 2840000.0,
                maxProfitPctDuringHold = 7.35,
                maxDrawdownPctDuringHold = -0.52,
                isTrailingUsed = false,
                sellTime = now - 3600_000L * 12 + 5055_000L,
                sellPrice = 3050000.0,
                sellQuantity = 1.05078809,
                sellTotalIdr = 3204903.0,
                sellFeeIdr = 9614.0,
                sellReason = "HIT_TP2",
                pnlIdr = 186289.0,
                pnlPercent = 6.20,
                isProfit = true
            ),
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-pepe-04$uidSuffix",
                isSample = true,
                symbol = "PEPEIDR",
                isRealTrade = false,
                strategyMode = "SCALPING",
                exchange = "TOKOCRYPTO",
                signalTime = now - 3600_000L * 16,
                signalPrice = 0.185,
                signalConfidence = 64,
                signalCalculationSummary = "RSI(14)=62.0 • BB Width=5.4% • Bid Depth=48.0% • Pressure=-4% (Seller)",
                signalReasons = "Breakout volatil awal namun likuiditas tipis.",
                targetPrice1 = 0.198,
                targetPrice2 = 0.208,
                stopLossPrice = 0.178,
                buyTime = now - 3600_000L * 16 + 5_000L,
                buyPrice = 0.186,
                buyQuantity = 10752688.0,
                buyTotalIdr = 2000000.0,
                buyFeeIdr = 6000.0,
                buyOrderType = "MARKET",
                status = "CLOSED",
                holdingDurationMs = 740_000L, // ~12 menit 20 detik
                peakPriceDuringHold = 0.189,
                troughPriceDuringHold = 0.177,
                maxProfitPctDuringHold = 1.61,
                maxDrawdownPctDuringHold = -4.83,
                isTrailingUsed = false,
                sellTime = now - 3600_000L * 16 + 745_000L,
                sellPrice = 0.178,
                sellQuantity = 10752688.0,
                sellTotalIdr = 1913978.0,
                sellFeeIdr = 5741.0,
                sellReason = "STOP_LOSS",
                pnlIdr = -97763.0,
                pnlPercent = -4.88,
                isProfit = false
            ),
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-near-05$uidSuffix",
                isSample = true,
                symbol = "NEARIDR",
                isRealTrade = true,
                strategyMode = "SCALPING",
                exchange = "TOKOCRYPTO",
                signalTime = now - 1800_000L,
                signalPrice = 85000.0,
                signalConfidence = 90,
                signalCalculationSummary = "RSI(14)=34.8 • MACD=Golden Cross (+0.024) • EMA20 > EMA50 (Uptrend) • Bid Depth=71.2% • Pressure=+32% (Buyer)",
                signalReasons = "Triple confluence: Order book buyer 71% + RSI crossing up 35 + Volume 4x rata-rata.",
                targetPrice1 = 88500.0,
                targetPrice2 = 91000.0,
                stopLossPrice = 83000.0,
                buyTime = now - 1800_000L + 10_000L,
                buyPrice = 85200.0,
                buyQuantity = 35.2112676,
                buyTotalIdr = 3000000.0,
                buyFeeIdr = 9000.0,
                buyOrderType = "LIMIT",
                status = "HOLDING",
                holdingDurationMs = 1790_000L, // ~29 menit
                peakPriceDuringHold = 87400.0,
                troughPriceDuringHold = 84900.0,
                maxProfitPctDuringHold = 2.58,
                maxDrawdownPctDuringHold = -0.35,
                isTrailingUsed = false
            ),
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-btcusdt-06$uidSuffix",
                isSample = true,
                symbol = "BTCUSDT",
                isRealTrade = false,
                strategyMode = "SCALPING",
                exchange = "TOKOCRYPTO",
                signalTime = now - 3600_000L * 4,
                signalPrice = 64500.0,
                signalConfidence = 89,
                signalCalculationSummary = "RSI(14)=35.2 • MACD=Golden Cross • EMA20 > EMA50 • Bid Depth=67.5% • Pressure=+28% (Buyer)",
                signalReasons = "Breakout resistance H1 + konfirmasi volume Tokocrypto dan order book buyer tebal.",
                targetPrice1 = 66500.0,
                targetPrice2 = 67800.0,
                stopLossPrice = 63500.0,
                buyTime = now - 3600_000L * 4 + 10_000L,
                buyPrice = 64600.0,
                buyQuantity = 0.015,
                buyTotalIdr = 969.0, // 969.0 USDT
                buyFeeIdr = 2.91, // 2.91 USDT
                buyOrderType = "LIMIT",
                status = "CLOSED",
                holdingDurationMs = 1840_000L, // ~30 menit 40 detik
                peakPriceDuringHold = 67500.0,
                troughPriceDuringHold = 64400.0,
                maxProfitPctDuringHold = 4.49,
                maxDrawdownPctDuringHold = -0.31,
                isTrailingUsed = true,
                trailingLockPrice = 67200.0,
                sellTime = now - 3600_000L * 4 + 1850_000L,
                sellPrice = 67200.0,
                sellQuantity = 0.015,
                sellTotalIdr = 1008.0, // 1008.0 USDT
                sellFeeIdr = 3.02,
                sellReason = "TRAILING_STOP",
                pnlIdr = 33.07, // +$33.07 USDT
                pnlPercent = 3.41,
                isProfit = true
            ),
            TradeHistoryRecordEntity(
                tradeUuid = "seed-trade-ethusdt-07$uidSuffix",
                isSample = true,
                symbol = "ETHUSDT",
                isRealTrade = true,
                strategyMode = "SCALPING",
                exchange = "TOKOCRYPTO",
                signalTime = now - 1200_000L,
                signalPrice = 3450.0,
                signalConfidence = 86,
                signalCalculationSummary = "RSI(14)=44.8 • MACD Line Crossing Signal • EMA20 > EMA50 • Bid Depth=64.0%",
                signalReasons = "Momentum expansion RSI crossing 40 + konfirmasi volume buy Tokocrypto.",
                targetPrice1 = 3560.0,
                targetPrice2 = 3650.0,
                stopLossPrice = 3380.0,
                buyTime = now - 1200_000L + 8_000L,
                buyPrice = 3455.0,
                buyQuantity = 0.35,
                buyTotalIdr = 1209.25, // 1209.25 USDT
                buyFeeIdr = 3.63,
                buyOrderType = "LIMIT",
                status = "HOLDING",
                holdingDurationMs = 1190_000L, // ~19 menit
                peakPriceDuringHold = 3530.0,
                troughPriceDuringHold = 3440.0,
                maxProfitPctDuringHold = 2.17,
                maxDrawdownPctDuringHold = -0.43,
                isTrailingUsed = false
            )
        )
        dao.insertRecords(samples)
    }

    suspend fun seedSampleTradeJourneysIfEmpty() = seedSampleTradeJourneys(force = false)
}
