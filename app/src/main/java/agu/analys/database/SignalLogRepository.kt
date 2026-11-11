package agu.analys.database

import agu.analys.model.SetupStats
import agu.analys.model.SignalReliabilitySummary
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SignalLogRepository(
    private val dao: SignalLogDao,
    private val scope: CoroutineScope
) {
    private val lastLoggedMap = ConcurrentHashMap<String, Long>()
    private val tickThrottleMap = ConcurrentHashMap<String, Long>()

    init {
        // Automatically consolidate any duplicate tracking logs from past runs
        scope.launch(Dispatchers.IO) {
            try {
                consolidateDuplicateTrackingLogs()
            } catch (_: Exception) {}
        }
    }

    val allLogsFlow: Flow<List<SignalLogEntity>> = dao.getAllLogsFlow()

    val reliabilitySummaryFlow: Flow<SignalReliabilitySummary> = dao.getAllLogsFlow().map { logs ->
        calculateReliabilitySummary(logs)
    }

    fun getLogsByExchangeFlow(exchange: String): Flow<List<SignalLogEntity>> =
        dao.getLogsByExchangeFlow(exchange.uppercase())

    fun getReliabilitySummaryByExchangeFlow(exchange: String): Flow<SignalReliabilitySummary> =
        dao.getLogsByExchangeFlow(exchange.uppercase()).map { logs ->
            calculateReliabilitySummary(logs)
        }

    fun getLogsBySymbolFlow(symbol: String): Flow<List<SignalLogEntity>> =
        dao.getLogsBySymbolFlow(symbol)

    fun getLogsBySymbolAndExchangeFlow(symbol: String, exchange: String): Flow<List<SignalLogEntity>> =
        dao.getLogsBySymbolAndExchangeFlow(symbol, exchange.uppercase())

    /**
     * Record or update a signal for a coin.
     * If the coin is already being tracked in the signal log, updates its parameters (confidence, reasoning, targets, etc.)
     * instead of creating a duplicate log entry.
     */
    fun recordSignal(
        symbol: String,
        action: String,
        strategyMode: String,
        confidence: Int,
        sentiment: String,
        entryPrice: Double,
        targetPrice1: Double = 0.0,
        targetPrice2: Double = 0.0,
        stopLoss: Double = 0.0,
        reasoning: String = "",
        scalpingStage: String = "",
        exchange: String = "TOKOCRYPTO",
        scalpingSetup: String = "",
        scalpingScore: Int = 0,
        scalpingScoreCategory: String = "",
        scalpingRegime: String = ""
    ) {
        if (entryPrice <= 0.0 || symbol.isBlank() || action.equals("HOLD", ignoreCase = true)) return

        val normSymbol = symbol.uppercase().replace("_", "").replace("/", "").trim()
        val normAction = action.uppercase().trim()
        val normExchange = exchange.uppercase()
        val now = System.currentTimeMillis()

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Check if there are already active TRACKING logs for this coin under this exchange
                val activeTrackingLogs = dao.getActiveTrackingLogsForSymbolAndExchange(normSymbol, normExchange)
                val primaryLog = activeTrackingLogs.firstOrNull()

                // J2 — level TP/SL hanya boleh digabung dengan entry dari sinyal yang SAMA. Bila harga
                // entry sudah bergerak material, ini episode sinyal yang berbeda: memperbarui TP/SL-nya
                // di atas entry lama membuat stop loss bisa berada DI ATAS entry, sehingga harga yang
                // turun menyentuh SL justru menghasilkan P&L positif (HIT_SL ber-P&L positif). Kasus itu
                // dulu masuk keranjang menang SEKALIGUS kalah dan membuat win-rate melewati 100%.
                if (primaryLog != null && !hasEntryDrifted(primaryLog.entryPrice, entryPrice)) {
                    // Coin already exists in active signal tracking! UPDATE existing log, do NOT insert new one

                    // If there are duplicate tracking entries for this symbol (from past bugs), clean them up
                    if (activeTrackingLogs.size > 1) {
                        val extraIds = activeTrackingLogs.drop(1).map { it.id }
                        dao.deleteLogsByIds(extraIds)
                    }

                    val isBuy = (if (normAction.isNotBlank()) normAction else primaryLog.action).equals("BUY", ignoreCase = true)
                    val currentTrackPrice = entryPrice
                    val rawPnlPct = if (isBuy) {
                        ((currentTrackPrice - primaryLog.entryPrice) / primaryLog.entryPrice) * 100.0
                    } else {
                        ((primaryLog.entryPrice - currentTrackPrice) / primaryLog.entryPrice) * 100.0
                    }

                    // B5 — Confidence yang disimpan harusNilai SAAT ENTRY (firing time), bukan
                    // MAX sepanjang pelacakan. Penyimpanan MAX membuat "confidence 88" di laporan
                    // berarti skor tertinggi berjam-jam, bukan skor saat sinyal terbit — menyesatkan
                    // evaluasi akurasi. Entity sudah dikomentari "Confidence score at firing time".
                    val updatedConfidence = primaryLog.confidence
                    val updatedReasoning = if (reasoning.isNotBlank()) reasoning else primaryLog.reasoning
                    val updatedSentiment = if (sentiment.isNotBlank()) sentiment else primaryLog.sentiment
                    val updatedStrategy = if (strategyMode.isNotBlank()) strategyMode else primaryLog.strategyMode
                    val updatedStage = if (scalpingStage.isNotBlank()) scalpingStage else primaryLog.scalpingStage

                    val updatedTp1 = if (targetPrice1 > 0.0) targetPrice1 else primaryLog.targetPrice1
                    val updatedTp2 = if (targetPrice2 > 0.0) targetPrice2 else primaryLog.targetPrice2
                    val updatedSl = if (stopLoss > 0.0) stopLoss else primaryLog.stopLoss

                    val newPeak = if (primaryLog.peakPrice <= 0.0) currentTrackPrice else max(primaryLog.peakPrice, currentTrackPrice)
                    val newTrough = if (primaryLog.troughPrice <= 0.0) currentTrackPrice else min(primaryLog.troughPrice, currentTrackPrice)
                    val newMaxProfit = max(primaryLog.maxProfitPct, max(0.0, rawPnlPct))
                    val newMaxDrawdown = min(primaryLog.maxDrawdownPct, min(0.0, rawPnlPct))

                    val updatedLog = primaryLog.copy(
                        action = if (normAction.isNotBlank()) normAction else primaryLog.action,
                        strategyMode = updatedStrategy,
                        confidence = updatedConfidence,
                        sentiment = updatedSentiment,
                        targetPrice1 = updatedTp1,
                        targetPrice2 = updatedTp2,
                        stopLoss = updatedSl,
                        reasoning = updatedReasoning,
                        scalpingStage = updatedStage,
                        peakPrice = newPeak,
                        troughPrice = newTrough,
                        maxProfitPct = newMaxProfit,
                        maxDrawdownPct = newMaxDrawdown,
                        exchange = normExchange,
                        // Setup/skor dicatat saat sinyal pertama terpicu; hanya diisi bila sebelumnya kosong.
                        scalpingSetup = primaryLog.scalpingSetup.ifBlank { scalpingSetup },
                        scalpingScore = if (primaryLog.scalpingSetup.isBlank()) scalpingScore else primaryLog.scalpingScore,
                        scalpingScoreCategory = primaryLog.scalpingScoreCategory.ifBlank { scalpingScoreCategory },
                        scalpingRegime = primaryLog.scalpingRegime.ifBlank { scalpingRegime }
                    )

                    dao.updateLog(updatedLog)
                    return@launch
                }

                // J2 (lanjutan) — harga entry bergerak material: tutup episode lama apa adanya,
                // lalu lanjut ke jalur "log baru" di bawah. Level TP/SL antar-episode tidak digabung.
                if (primaryLog != null) {
                    if (activeTrackingLogs.size > 1) {
                        dao.deleteLogsByIds(activeTrackingLogs.drop(1).map { it.id })
                    }
                    resolveSupersededTrackingLog(primaryLog, entryPrice, now)
                }

                // 2. Check if coin already has a recent log in DB (e.g. resolved recently) to avoid immediate re-spam.
                //    Hanya dalam bursa yang sama — jangan pakai log bursa lain sebagai acuan anti-spam.
                val latest = dao.getLatestLogForSymbolAndExchange(normSymbol, normExchange)
                if (latest != null) {
                    // If it was resolved within 3 minutes and price hasn't meaningfully moved (<0.8%), skip creating duplicate
                    val lastTimestamp = latest.resolvedAt ?: latest.firedAt
                    if (now - lastTimestamp < 180_000L) {
                        val diff = abs(latest.entryPrice - entryPrice) / latest.entryPrice
                        if (diff < 0.008) {
                            return@launch
                        }
                    }
                }

                // 3. Brand new coin entry for tracking
                val entity = SignalLogEntity(
                    symbol = normSymbol,
                    action = normAction,
                    strategyMode = strategyMode,
                    confidence = confidence,
                    sentiment = sentiment,
                    entryPrice = entryPrice,
                    targetPrice1 = targetPrice1,
                    targetPrice2 = targetPrice2,
                    stopLoss = stopLoss,
                    firedAt = now,
                    reasoning = reasoning,
                    scalpingStage = scalpingStage,
                    exchange = normExchange,
                    outcomeStatus = "TRACKING",
                    peakPrice = entryPrice,
                    troughPrice = entryPrice,
                    maxProfitPct = 0.0,
                    maxDrawdownPct = 0.0,
                    scalpingSetup = scalpingSetup,
                    scalpingScore = scalpingScore,
                    scalpingScoreCategory = scalpingScoreCategory,
                    scalpingRegime = scalpingRegime
                )
                dao.insertLog(entity)
            } catch (_: Exception) {}
        }
    }

    /**
     * Tutup log pelacakan lama karena sinyal baru terbit pada harga entry yang berbeda material.
     *
     * **J2** — log lama sengaja dipertahankan **apa adanya**: `entryPrice`, `targetPrice1`,
     * `targetPrice2`, dan `stopLoss` miliknya sendiri tetap utuh, sehingga P&L-nya masih bermakna
     * (tidak ada SL di atas entry). Episode sinyal baru dicatat sebagai log terpisah oleh pemanggil.
     *
     * P&L memakai basis net yang sama dengan seluruh aplikasi ([netSignalPnlPct]).
     */
    private suspend fun resolveSupersededTrackingLog(
        log: SignalLogEntity,
        currentPrice: Double,
        now: Long
    ) {
        val exit = currentPrice.takeIf { it > 0.0 && it.isFinite() }
            ?: log.peakPrice.takeIf { it > 0.0 }
            ?: log.entryPrice
        dao.updateLog(
            log.copy(
                outcomeStatus = "EXPIRED",
                exitPrice = exit,
                realizedPnlPct = log.realizedPnlPct ?: netSignalPnlPct(log, exit),
                resolvedAt = now,
                resolutionNote = "Digantikan sinyal baru pada harga entry berbeda " +
                    "(entry lama ${log.entryPrice}, entry baru $currentPrice). " +
                    "Level TP/SL tidak digabung antar-episode (J2)."
            )
        )
    }

    /**
     * Consolidate duplicate tracking logs so that each symbol only has 1 active tracking log per exchange
     */
    suspend fun consolidateDuplicateTrackingLogs() = withContext(Dispatchers.IO) {
        val allTracking = dao.getActiveTrackingLogs()
        val grouped = allTracking.groupBy { "${it.exchange}_${it.symbol}" }
        for ((_, logs) in grouped) {
            if (logs.size > 1) {
                // Keep the primary (latest by firedAt or with best tracked stats)
                val primary = logs.maxByOrNull { it.firedAt } ?: logs.first()
                val duplicates = logs.filter { it.id != primary.id }
                if (duplicates.isNotEmpty()) {
                    dao.deleteLogsByIds(duplicates.map { it.id })
                }
            }
        }
    }

    /**
     * Update active tracking signals based on live market tick
     */
    fun processPriceTick(symbol: String, currentPrice: Double, exchange: String = "TOKOCRYPTO") {
        if (currentPrice <= 0.0 || symbol.isBlank()) return
        val normSymbol = symbol.uppercase().replace("_", "").replace("/", "").trim()
        val normExchange = exchange.uppercase()
        val now = System.currentTimeMillis()

        // Throttle updates per symbol to max 1 update per 1000ms
        val throttleKey = "${normExchange}_${normSymbol}"
        val lastUpdate = tickThrottleMap[throttleKey] ?: 0L
        if (now - lastUpdate < 1000L) return
        tickThrottleMap[throttleKey] = now

        scope.launch(Dispatchers.IO) {
            try {
                // Hanya perbarui log pelacakan milik bursa ini. Jangan pernah jatuh ke log
                // semua bursa, agar harga dari satu bursa tidak mengubah sinyal bursa lain.
                val trackingLogs = dao.getActiveTrackingLogsForSymbolAndExchange(normSymbol, normExchange)
                if (trackingLogs.isEmpty()) return@launch

                for (log in trackingLogs) {
                    updateTrackingLogWithPrice(log, currentPrice, now)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Batch update all active tracking signals from whole market tickers
     */
    fun processBatchPriceTicks(priceMap: Map<String, Double>, exchange: String = "TOKOCRYPTO") {
        if (priceMap.isEmpty()) return
        val normExchange = exchange.uppercase()
        val now = System.currentTimeMillis()

        scope.launch(Dispatchers.IO) {
            try {
                // Hanya perbarui log pelacakan bursa ini — tanpa fallback ke semua bursa.
                val trackingLogs = dao.getActiveTrackingLogsForExchange(normExchange)
                if (trackingLogs.isEmpty()) return@launch

                for (log in trackingLogs) {
                    val currentPrice = priceMap[log.symbol]
                        ?: priceMap[log.symbol.lowercase()]
                        ?: priceMap["${log.symbol.removeSuffix("IDR").removeSuffix("idr")}idr"]
                        ?: priceMap["${log.symbol.removeSuffix("IDR").removeSuffix("idr")}IDR"]
                        ?: continue

                    if (currentPrice <= 0.0) continue
                    updateTrackingLogWithPrice(log, currentPrice, now)
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun updateTrackingLogWithPrice(log: SignalLogEntity, currentPrice: Double, now: Long) {
        val isBuy = log.action.equals("BUY", ignoreCase = true)

        // GROSS — dipakai HANYA untuk peak/trough/maxProfit/maxDrawdown, yang memang berlabel
        // "kotor" di UI (B6). Jangan pernah pakai angka ini sebagai realizedPnlPct.
        val rawPnlPct = if (isBuy) {
            ((currentPrice - log.entryPrice) / log.entryPrice) * 100.0
        } else {
            ((log.entryPrice - currentPrice) / log.entryPrice) * 100.0
        }

        // NET — J1: satu-satunya angka P&L yang boleh ditampilkan sebagai hasil akhir. Sebelum
        // perbaikan, jurnal menyimpan selisih harga MENTAH lalu UI-nya melabelinya "Net", sehingga
        // "+0,35%" yang tampil sebagai "Net" sebenarnya masih rugi (impas net ≈ +0,82%).
        val netPnlPct = netSignalPnlPct(log, currentPrice)

        val newPeak = if (log.peakPrice <= 0.0) currentPrice else max(log.peakPrice, currentPrice)
        val newTrough = if (log.troughPrice <= 0.0) currentPrice else min(log.troughPrice, currentPrice)
        val newMaxProfit = max(log.maxProfitPct, max(0.0, rawPnlPct))
        val newMaxDrawdown = min(log.maxDrawdownPct, min(0.0, rawPnlPct))

        val quoteAsset = quoteAssetForSymbol(log.symbol)

        // Check for target outcomes
        var status = log.outcomeStatus
        var exitPrice: Double? = null
        var realizedPnl: Double? = null
        var resolvedAt: Long? = null
        var note: String? = null

        if (log.targetPrice2 > 0.0 && ((isBuy && currentPrice >= log.targetPrice2) || (!isBuy && currentPrice <= log.targetPrice2))) {
            status = "HIT_TP2"
            exitPrice = currentPrice
            realizedPnl = netPnlPct
            resolvedAt = now
            note = "Target TP2 tercapai pada ${PriceFormatter.formatPrice(currentPrice, quoteAsset = quoteAsset)} (net ${String.format(java.util.Locale.US, "%.2f", netPnlPct)}%)"
        } else if (log.targetPrice1 > 0.0 && ((isBuy && currentPrice >= log.targetPrice1) || (!isBuy && currentPrice <= log.targetPrice1))) {
            status = "HIT_TP1"
            exitPrice = currentPrice
            realizedPnl = netPnlPct
            resolvedAt = now
            note = "Target TP1 tercapai pada ${PriceFormatter.formatPrice(currentPrice, quoteAsset = quoteAsset)} (net ${String.format(java.util.Locale.US, "%.2f", netPnlPct)}%)"
        } else if (log.stopLoss > 0.0 && ((isBuy && currentPrice <= log.stopLoss) || (!isBuy && currentPrice >= log.stopLoss))) {
            status = "HIT_SL"
            exitPrice = currentPrice
            realizedPnl = netPnlPct
            resolvedAt = now
            note = "Stop Loss tersentuh pada ${PriceFormatter.formatPrice(currentPrice, quoteAsset = quoteAsset)} (net ${String.format(java.util.Locale.US, "%.2f", netPnlPct)}%)"
        } else if (now - log.firedAt > 86_400_000L * 3) {
            // Expire after 3 days
            status = "EXPIRED"
            exitPrice = currentPrice
            realizedPnl = netPnlPct
            resolvedAt = now
            note = "Sinyal kedaluwarsa setelah 3 hari. Hasil akhir net: ${String.format(java.util.Locale.US, "%.2f", netPnlPct)}%"
        }

        val updatedLog = log.copy(
            peakPrice = newPeak,
            troughPrice = newTrough,
            maxProfitPct = newMaxProfit,
            maxDrawdownPct = newMaxDrawdown,
            outcomeStatus = status,
            exitPrice = exitPrice ?: log.exitPrice,
            realizedPnlPct = realizedPnl ?: log.realizedPnlPct,
            resolvedAt = resolvedAt ?: log.resolvedAt,
            resolutionNote = note ?: log.resolutionNote
        )

        dao.updateLog(updatedLog)
    }

    /**
     * Resolve a signal log manually (e.g. user manually took profit or cut loss)
     */
    suspend fun resolveLogManually(
        id: Long,
        isWin: Boolean,
        customExitPrice: Double? = null,
        customPnlPct: Double? = null,
        note: String = "Diselesaikan manual oleh pengguna"
    ) = withContext(Dispatchers.IO) {
        val log = dao.getLogById(id) ?: return@withContext
        val status = if (isWin) "MANUAL_WIN" else "MANUAL_LOSS"
        val now = System.currentTimeMillis()
        val updated = log.copy(
            outcomeStatus = status,
            exitPrice = customExitPrice ?: log.peakPrice.takeIf { it > 0 } ?: log.entryPrice,
            // B4 — besaran PnL manual harus JUJUR, hasil kalkulasi dari harga exit, bukan
            // hasil clamp min(-1.5)/max(1.5) yang mengarang besaran. Bila exitPrice tersedia,
            // hitung dari situ; bila tidak, realPnlPct dibiarkan null (jujur "tidak diketahui").
            // J1 — kalkulasinya memakai basis net yang sama dengan seluruh aplikasi.
            realizedPnlPct = customPnlPct ?: log.exitPrice?.takeIf { it > 0 }?.let { exit ->
                netSignalPnlPct(log, exit)
            },
            resolvedAt = now,
            resolutionNote = note
        )
        dao.updateLog(updated)
    }

    /**
     * Expire all active TRACKING logs for a symbol when user has already sold (MarkSold).
     * Log becomes kadaluarsa so it no longer appears as active tracking.
     *
     * @param exchange bila diisi, hanya log bursa tersebut yang dikadaluarsakan (mencegah
     *                 penjualan di satu bursa mematikan pelacakan bursa lain).
     */
    fun expireTrackingLogsForSymbol(
        symbol: String,
        reason: String = "Posisi sudah terjual (MarkSold) — log kadaluarsa",
        exchange: String? = null
    ) {
        if (symbol.isBlank()) return
        val normSymbol = symbol.uppercase().replace("_", "").replace("/", "").trim()
        val normExchange = exchange?.uppercase()?.trim()
        scope.launch(Dispatchers.IO) {
            try {
                val tracking = if (normExchange.isNullOrBlank()) {
                    dao.getActiveTrackingLogsForSymbol(normSymbol)
                } else {
                    dao.getActiveTrackingLogsForSymbolAndExchange(normSymbol, normExchange)
                }
                if (tracking.isEmpty()) return@launch
                val now = System.currentTimeMillis()
                for (log in tracking) {
                    // B3 — PnL sinyal expired HARUS dihitung dari harga terakhir (exitPrice),
                    // BUKAN dari peak/trough. Peak adalah harga tertinggi yang pernah tercapai,
                    // bukan harga jual — memakainya membuat sinyal expired terlihat lebih untung
                    // dari kenyataan (bias optimistis sistematis pada win-rate).
                    // J1 — basisnya net, sama dengan seluruh aplikasi.
                    val approxPnl = log.realizedPnlPct
                        ?: log.exitPrice?.takeIf { it > 0 }?.let { exit -> netSignalPnlPct(log, exit) }
                        ?: 0.0
                    val updated = log.copy(
                        outcomeStatus = "EXPIRED",
                        resolvedAt = now,
                        exitPrice = log.exitPrice ?: log.peakPrice.takeIf { it > 0 } ?: log.entryPrice,
                        realizedPnlPct = log.realizedPnlPct ?: approxPnl,
                        resolutionNote = reason
                    )
                    dao.updateLog(updated)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Force refresh / consolidate tracking logs (for Refresh button on Log & Akurasi page).
     * Ensures 1 active TRACKING per symbol and cleans stale state.
     */
    fun refreshAndConsolidate() {
        scope.launch(Dispatchers.IO) {
            try {
                consolidateDuplicateTrackingLogs()
            } catch (_: Exception) {}
        }
    }

    suspend fun deleteLog(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteLogById(id)
    }

    suspend fun clearAllLogs() = withContext(Dispatchers.IO) {
        dao.clearAllLogs()
    }

    /**
     * Pre-populate seed logs if database is clean so user has immediate rich data to test reliability evaluation
     */
    suspend fun seedSampleLogsIfEmpty() = withContext(Dispatchers.IO) {
        if (dao.getLogCount() > 0) return@withContext

        val now = System.currentTimeMillis()
        val sampleLogs = listOf(
            SignalLogEntity(
                symbol = "BTCIDR",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 88,
                sentiment = "BULLISH_REVERSAL",
                entryPrice = 1450000000.0,
                targetPrice1 = 1485000000.0,
                targetPrice2 = 1510000000.0,
                stopLoss = 1430000000.0,
                firedAt = now - 3600_000L * 4,
                reasoning = "Breakout resistance M15 + konfirmasi volume besar dan MACD Golden Cross.",
                scalpingStage = "STRONG_ENTRY",
                outcomeStatus = "HIT_TP2",
                maxProfitPct = 4.14,
                maxDrawdownPct = -0.42,
                exitPrice = 1510000000.0,
                realizedPnlPct = 4.14,
                peakPrice = 1515000000.0,
                troughPrice = 1445000000.0,
                resolvedAt = now - 3600_000L * 2,
                resolutionNote = "Target TP2 tercapai pada Rp 1.510.000.000 (+4.14%)"
            ),
            SignalLogEntity(
                symbol = "ETHIDR",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 82,
                sentiment = "STRONG_BULLISH_CONTINUATION",
                entryPrice = 52000000.0,
                targetPrice1 = 53500000.0,
                targetPrice2 = 54600000.0,
                stopLoss = 51200000.0,
                firedAt = now - 3600_000L * 8,
                reasoning = "Volume Spike 3x rata-rata + RSI 58 momentum expansion.",
                scalpingStage = "ENTRY",
                outcomeStatus = "HIT_TP1",
                maxProfitPct = 2.88,
                maxDrawdownPct = -0.65,
                exitPrice = 53500000.0,
                realizedPnlPct = 2.88,
                peakPrice = 53800000.0,
                troughPrice = 51700000.0,
                resolvedAt = now - 3600_000L * 5,
                resolutionNote = "Target TP1 tercapai pada Rp 53.500.000 (+2.88%)"
            ),
            SignalLogEntity(
                symbol = "SOLIDR",
                action = "BUY",
                strategyMode = "SECOND_WAVE",
                confidence = 74,
                sentiment = "BULLISH_REVERSAL",
                entryPrice = 2850000.0,
                targetPrice1 = 2960000.0,
                targetPrice2 = 3050000.0,
                stopLoss = 2780000.0,
                firedAt = now - 3600_000L * 12,
                reasoning = "Pantulan gelombang 2 (Fibonacci 0.618) dengan candle hammer kuat.",
                scalpingStage = "ENTRY",
                outcomeStatus = "HIT_TP1",
                maxProfitPct = 3.86,
                maxDrawdownPct = -1.15,
                exitPrice = 2960000.0,
                realizedPnlPct = 3.86,
                peakPrice = 2975000.0,
                troughPrice = 2820000.0,
                resolvedAt = now - 3600_000L * 9,
                resolutionNote = "Target TP1 tercapai pada Rp 2.960.000 (+3.86%)"
            ),
            SignalLogEntity(
                symbol = "PEPEIDR",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 62,
                sentiment = "ACCUMULATION_SQUEEZE",
                entryPrice = 0.185,
                targetPrice1 = 0.198,
                targetPrice2 = 0.208,
                stopLoss = 0.178,
                firedAt = now - 3600_000L * 16,
                reasoning = "Breakout volatil squeeze, tapi volume relatif moderat.",
                scalpingStage = "EARLY_ENTRY",
                outcomeStatus = "HIT_SL",
                maxProfitPct = 1.62,
                maxDrawdownPct = -3.78,
                exitPrice = 0.178,
                realizedPnlPct = -3.78,
                peakPrice = 0.188,
                troughPrice = 0.176,
                resolvedAt = now - 3600_000L * 14,
                resolutionNote = "Stop Loss tersentuh pada Rp 0.178 (-3.78%)"
            ),
            SignalLogEntity(
                symbol = "DOGEIDR",
                action = "BUY",
                strategyMode = "SWING",
                confidence = 85,
                sentiment = "STRONG_BULLISH_CONTINUATION",
                entryPrice = 3250.0,
                targetPrice1 = 3450.0,
                targetPrice2 = 3600.0,
                stopLoss = 3120.0,
                firedAt = now - 3600_000L * 24,
                reasoning = "EMA20 crossing EMA50 pada grafik 4H didukung volume buy dominan.",
                scalpingStage = "STRONG_ENTRY",
                outcomeStatus = "HIT_TP2",
                maxProfitPct = 10.77,
                maxDrawdownPct = -0.92,
                exitPrice = 3600.0,
                realizedPnlPct = 10.77,
                peakPrice = 3620.0,
                troughPrice = 3220.0,
                resolvedAt = now - 3600_000L * 18,
                resolutionNote = "Target TP2 tercapai pada Rp 3.600 (+10.77%)"
            ),
            SignalLogEntity(
                symbol = "XRPIDR",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 58,
                sentiment = "NEUTRAL_CONSOLIDATION",
                entryPrice = 38500.0,
                targetPrice1 = 39800.0,
                targetPrice2 = 41000.0,
                stopLoss = 37600.0,
                firedAt = now - 3600_000L * 28,
                reasoning = "Sinyal spekulatif pembalikan awal support.",
                scalpingStage = "WATCH",
                outcomeStatus = "HIT_SL",
                maxProfitPct = 0.85,
                maxDrawdownPct = -2.34,
                exitPrice = 37600.0,
                realizedPnlPct = -2.34,
                peakPrice = 38800.0,
                troughPrice = 37500.0,
                resolvedAt = now - 3600_000L * 25,
                resolutionNote = "Stop Loss tersentuh pada Rp 37.600 (-2.34%)"
            ),
            SignalLogEntity(
                symbol = "NEARIDR",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 90,
                sentiment = "BULLISH_REVERSAL",
                entryPrice = 85000.0,
                targetPrice1 = 88500.0,
                targetPrice2 = 91000.0,
                stopLoss = 83000.0,
                firedAt = now - 3600_000L * 2,
                reasoning = "Triple confluence: Orderbook buy depth 68%, RSI 48 crossing up, Vol 4.5x.",
                scalpingStage = "STRONG_ENTRY",
                outcomeStatus = "TRACKING",
                maxProfitPct = 2.35,
                maxDrawdownPct = -0.35,
                peakPrice = 87000.0,
                troughPrice = 84700.0
            ),
            SignalLogEntity(
                symbol = "BTCUSDT",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 89,
                sentiment = "BULLISH_REVERSAL",
                entryPrice = 64800.0,
                targetPrice1 = 66500.0,
                targetPrice2 = 67800.0,
                stopLoss = 63800.0,
                firedAt = now - 3600_000L * 5,
                reasoning = "Breakout resistance H1 + lonjakan volume Tokocrypto dan MACD Golden Cross.",
                scalpingStage = "STRONG_ENTRY",
                exchange = "TOKOCRYPTO",
                outcomeStatus = "HIT_TP2",
                maxProfitPct = 4.63,
                maxDrawdownPct = -0.31,
                exitPrice = 67800.0,
                realizedPnlPct = 4.63,
                peakPrice = 67950.0,
                troughPrice = 64600.0,
                resolvedAt = now - 3600_000L * 3,
                resolutionNote = "Target TP2 tercapai pada $67,800.00 (+4.63%)"
            ),
            SignalLogEntity(
                symbol = "ETHUSDT",
                action = "BUY",
                strategyMode = "SCALPING",
                confidence = 84,
                sentiment = "STRONG_BULLISH_CONTINUATION",
                entryPrice = 3450.0,
                targetPrice1 = 3560.0,
                targetPrice2 = 3650.0,
                stopLoss = 3380.0,
                firedAt = now - 3600_000L * 10,
                reasoning = "Volume Spike 3x rata-rata + RSI 56 crossing 50.",
                scalpingStage = "ENTRY",
                exchange = "TOKOCRYPTO",
                outcomeStatus = "HIT_TP1",
                maxProfitPct = 3.19,
                maxDrawdownPct = -0.58,
                exitPrice = 3560.0,
                realizedPnlPct = 3.19,
                peakPrice = 3580.0,
                troughPrice = 3430.0,
                resolvedAt = now - 3600_000L * 7,
                resolutionNote = "Target TP1 tercapai pada $3,560.00 (+3.19%)"
            )
        ).map { log ->
            // Tandai jelas bahwa ini data CONTOH, bukan sinyal nyata, agar pengguna
            // tidak salah mengira sinyal buatan ini berasal dari pasar sungguhan.
            log.copy(
                reasoning = SAMPLE_MARKER + log.reasoning,
                resolutionNote = log.resolutionNote?.let { SAMPLE_MARKER + it }
            )
        }
        dao.insertLogs(sampleLogs)
    }

    companion object {
        /** Penanda tegas bahwa sebuah log adalah data contoh, bukan sinyal pasar nyata. */
        const val SAMPLE_MARKER = "[SAMPEL] "

        fun calculateReliabilitySummary(logs: List<SignalLogEntity>): SignalReliabilitySummary {
            if (logs.isEmpty()) return SignalReliabilitySummary()

            val total = logs.size
            val tracking = logs.count { it.outcomeStatus == "TRACKING" }
            val resolvedLogs = logs.filter { it.outcomeStatus != "TRACKING" }

            // J2 — klasifikasi menang/kalah WAJIB lewat satu predikat eksklusif. Sebelumnya di sini
            // ada dua filter independen, sehingga log HIT_SL ber-P&L positif masuk keranjang menang
            // SEKALIGUS kalah dan win-rate bisa melewati 100%.
            val wins = resolvedLogs.filter { it.classifyOutcome() == SignalOutcome.WIN }
            val losses = resolvedLogs.filter { it.classifyOutcome() == SignalOutcome.LOSS }

            val winCount = wins.size
            val lossCount = losses.size
            val resolvedCount = resolvedLogs.size

            val winRatePct = if (resolvedCount > 0) (winCount.toDouble() / resolvedCount) * 100.0 else 0.0

            // J2 — rata-rata dan nilai ekstrem hanya dihitung dari log yang P&L-nya SEARAH dengan
            // keranjangnya. Baris warisan berstatus HIT_SL tetapi ber-P&L positif (entry lama
            // dipasangkan stop loss baru) tidak boleh ikut menaikkan "rata-rata kalah" — angkanya
            // memang tidak dapat dipercaya, dan menyertakannya menghasilkan keluaran tak masuk akal
            // seperti "rata-rata kalah +90%".
            val winPnls = wins.mapNotNull { it.realizedPnlPct ?: it.maxProfitPct.takeIf { p -> p > 0 } }
                .filter { it > 0.0 && it.isFinite() }
            val lossPnls = losses.mapNotNull { it.realizedPnlPct ?: it.maxDrawdownPct.takeIf { d -> d < 0 } }
                .filter { it < 0.0 && it.isFinite() }

            val avgProfit = if (winPnls.isNotEmpty()) winPnls.average() else 0.0
            val avgLoss = if (lossPnls.isNotEmpty()) lossPnls.average() else 0.0

            val totalWinGain = winPnls.sum()
            val totalLossValue = abs(lossPnls.sum())
            val profitFactor = if (totalLossValue > 0) totalWinGain / totalLossValue else if (totalWinGain > 0) 9.99 else 0.0

            val maxWin = winPnls.maxOrNull() ?: 0.0
            val maxLoss = lossPnls.minOrNull() ?: 0.0

            // Best performing symbol & strategy — memakai predikat yang sama (J2), bukan definisi keempat.
            val symbolGroups = resolvedLogs.groupBy { it.symbol }
            val bestSymbol = symbolGroups.maxByOrNull { group ->
                val w = group.value.count { it.classifyOutcome() == SignalOutcome.WIN }
                w.toDouble() / group.value.size
            }?.key ?: "-"

            val strategyGroups = resolvedLogs.groupBy { it.strategyMode }
            val bestStrategy = strategyGroups.maxByOrNull { group ->
                val w = group.value.count { it.classifyOutcome() == SignalOutcome.WIN }
                w.toDouble() / group.value.size
            }?.key ?: "-"

            return SignalReliabilitySummary(
                totalLogs = total,
                completedLogs = resolvedCount,
                winCount = winCount,
                lossCount = lossCount,
                trackingCount = tracking,
                overallWinRatePct = winRatePct,
                avgProfitPct = avgProfit,
                avgLossPct = avgLoss,
                profitFactor = profitFactor,
                maxSingleWinPct = maxWin,
                maxSingleLossPct = maxLoss,
                bestPerformingSymbol = bestSymbol,
                bestStrategyMode = bestStrategy,
                setupStats = calculateSetupStats(logs),
                scoreCategoryStats = calculateScoreCategoryStats(logs),
                legacySignalCount = logs.count { it.scalpingSetup.isBlank() }
            )
        }

        private val SETUP_ORDER = listOf("LIQUIDITY_SWEEP", "BREAKOUT_RETEST", "BREAKOUT", "TREND_PULLBACK")
        private val CATEGORY_ORDER = listOf("VERY_STRONG", "STRONG", "WATCH", "WEAK", "NO_TRADE")

        /** Akurasi per setup. Sinyal lama tanpa setup dilewati (lihat legacySignalCount). */
        fun calculateSetupStats(logs: List<SignalLogEntity>): List<SetupStats> =
            groupStats(logs, SETUP_ORDER) { it.scalpingSetup }

        /** Akurasi per kategori skor engine. Sinyal lama tanpa kategori dilewati. */
        fun calculateScoreCategoryStats(logs: List<SignalLogEntity>): List<SetupStats> =
            groupStats(logs, CATEGORY_ORDER) { it.scalpingScoreCategory }

        private fun groupStats(
            logs: List<SignalLogEntity>,
            order: List<String>,
            keyOf: (SignalLogEntity) -> String
        ): List<SetupStats> {
            val groups = logs
                .filter { keyOf(it).isNotBlank() }
                .groupBy { keyOf(it).uppercase() }
            if (groups.isEmpty()) return emptyList()
            val sortedKeys = groups.keys.sortedWith(
                compareBy({ order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it })
            )
            return sortedKeys.map { key -> buildGroupStats(key, groups.getValue(key)) }
        }

        private fun buildGroupStats(key: String, group: List<SignalLogEntity>): SetupStats {
            val tracking = group.count { it.outcomeStatus == "TRACKING" }
            val resolved = group.filter { it.outcomeStatus != "TRACKING" }

            fun pnl(l: SignalLogEntity) = l.realizedPnlPct ?: 0.0
            // J2 — satu predikat eksklusif (dulu di sini ada definisi kedua yang benar, sekarang
            // definisi itu menjadi satu-satunya untuk seluruh berkas).
            val wins = resolved.filter { it.classifyOutcome() == SignalOutcome.WIN }
            val losses = resolved.filter { it.classifyOutcome() == SignalOutcome.LOSS }

            val winRate = if (resolved.isNotEmpty()) wins.size * 100.0 / resolved.size else 0.0
            val avgReturn = if (resolved.isNotEmpty()) resolved.sumOf { pnl(it) } / resolved.size else 0.0
            val gain = resolved.sumOf { pnl(it).coerceAtLeast(0.0) }
            val loss = resolved.sumOf { abs(pnl(it).coerceAtMost(0.0)) }
            val pf = if (loss > 0.0) gain / loss else if (gain > 0.0) 9.99 else 0.0

            return SetupStats(
                key = key,
                totalSignals = group.size,
                trackingCount = tracking,
                resolvedCount = resolved.size,
                winCount = wins.size,
                lossCount = losses.size,
                winRatePct = winRate,
                avgReturnPct = avgReturn,
                profitFactor = pf
            )
        }
    }
}
