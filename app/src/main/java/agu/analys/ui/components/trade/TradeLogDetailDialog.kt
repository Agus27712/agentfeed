package agu.analys.ui.components.trade

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import agu.analys.trading.TradeLogExporter
import agu.analys.trading.TradeSignalSnapshot
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@Composable
fun TradeLogDetailDialog(
    tradeId: String,
    symbol: String,
    baseAsset: String,
    quoteAsset: String,
    side: String,
    orderType: String,
    executionPrice: Double,
    quantity: Double,
    totalIdr: Double,
    feeIdr: Double,
    timestamp: Long,
    isReal: Boolean,
    strategyMode: String,
    holdingDurationMs: Long?,
    entryPrice: Double?,
    entryTimestamp: Long?,
    pnlIdr: Double?,
    pnlPercent: Double?,
    isTrailingUsed: Boolean,
    trailingPercent: Double?,
    trailingPeakPrice: Double?,
    trailingLockPrice: Double?,
    snapshot: TradeSignalSnapshot?,
    exchange: String = "TOKOCRYPTO",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isBuy = side.equals("BUY", true)
    val sideColor = if (isBuy) TvGreen else TvRed
    val quote = quoteAsset.ifBlank { "IDR" }
    val timeFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()) }

    var resolvedSnapshot by remember(snapshot) { mutableStateOf(snapshot) }
    var resolvedStrategy by remember(strategyMode) { mutableStateOf(strategyMode) }
    var resolvedDuration by remember(holdingDurationMs) { mutableStateOf(holdingDurationMs) }
    var resolvedEntryPrice by remember(entryPrice) { mutableStateOf(entryPrice) }
    var resolvedPnlIdr by remember(pnlIdr) { mutableStateOf(pnlIdr) }
    var resolvedPnlPct by remember(pnlPercent) { mutableStateOf(pnlPercent) }
    var resolvedTrailing by remember(isTrailingUsed) { mutableStateOf(isTrailingUsed) }
    var resolvedTrailingPercent by remember(trailingPercent) { mutableStateOf(trailingPercent) }
    var resolvedPeakPrice by remember(trailingPeakPrice) { mutableStateOf(trailingPeakPrice) }
    var resolvedLockPrice by remember(trailingLockPrice) { mutableStateOf(trailingLockPrice) }

    LaunchedEffect(tradeId, symbol, snapshot, exchange) {
        if (resolvedSnapshot == null) {
            withContext(Dispatchers.IO) {
                try {
                    val db = agu.analys.database.AppDatabase.getInstance()
                    val normSymbol = symbol.uppercase().replace("_", "")
                    val altSymbol = if (normSymbol.endsWith("IDR")) normSymbol else "${normSymbol}IDR"
                    val normExchange = exchange.uppercase().trim().ifBlank { "TOKOCRYPTO" }

                    // 1. Cari dari trade_history_records (hanya bursa yang sama)
                    val records: List<agu.analys.database.TradeHistoryRecordEntity> =
                        db.tradeHistoryRecordDao().getRecordsForSymbolAndExchange(normSymbol, altSymbol, normExchange)
                    val matchedRecord: agu.analys.database.TradeHistoryRecordEntity? = records.firstOrNull { rec: agu.analys.database.TradeHistoryRecordEntity ->
                        val buyDiff = java.lang.Math.abs(rec.buyTime - timestamp)
                        val sellDiff = java.lang.Math.abs((rec.sellTime ?: 0L) - timestamp)
                        (side.equals("BUY", true) && (rec.buyPrice == executionPrice || buyDiff < 600000L)) ||
                        (side.equals("SELL", true) && (rec.sellPrice == executionPrice || sellDiff < 600000L))
                    } ?: records.firstOrNull { it.signalSnapshotJson != null }

                    if (matchedRecord != null) {
                        if (resolvedSnapshot == null && matchedRecord.signalSnapshotJson != null) {
                            resolvedSnapshot = TradeSignalSnapshot.fromJsonString(matchedRecord.signalSnapshotJson)
                        }
                        if (resolvedStrategy.equals("MANUAL", true) && matchedRecord.strategyMode.isNotBlank()) {
                            resolvedStrategy = matchedRecord.strategyMode
                        }
                        if (resolvedDuration == null || resolvedDuration == 0L) {
                            resolvedDuration = matchedRecord.holdingDurationMs
                        }
                        if (resolvedEntryPrice == null || resolvedEntryPrice == 0.0) {
                            resolvedEntryPrice = matchedRecord.buyPrice
                        }
                        if (resolvedPnlIdr == null) {
                            resolvedPnlIdr = matchedRecord.pnlIdr
                        }
                        if (resolvedPnlPct == null) {
                            resolvedPnlPct = matchedRecord.pnlPercent
                        }
                        if (!resolvedTrailing && matchedRecord.isTrailingUsed) {
                            resolvedTrailing = true
                            resolvedTrailingPercent = matchedRecord.trailingPercent
                            resolvedPeakPrice = matchedRecord.peakPriceDuringHold
                            resolvedLockPrice = matchedRecord.trailingLockPrice
                        }
                    }

                    // 2. Jika snapshot masih null, periksa sinyal di signal_logs (hanya bursa yang sama)
                    if (resolvedSnapshot == null) {
                        val log = db.signalLogDao().getLatestLogForSymbolAndExchange(normSymbol, normExchange)
                        if (log != null) {
                            val reasons = mutableListOf<String>()
                            if (log.reasoning.isNotBlank()) reasons.add(log.reasoning)
                            if (log.sentiment.isNotBlank()) reasons.add("Sentimen: ${log.sentiment}")
                            if (log.targetPrice1 > 0) reasons.add("Target TP1: ${PriceFormatter.formatPrice(log.targetPrice1, quoteAsset = quote)}")
                            if (log.targetPrice2 > 0) reasons.add("Target TP2: ${PriceFormatter.formatPrice(log.targetPrice2, quoteAsset = quote)}")
                            if (log.stopLoss > 0) reasons.add("Stop Loss: ${PriceFormatter.formatPrice(log.stopLoss, quoteAsset = quote)}")
                            if (reasons.isEmpty()) reasons.add("Sinyal indikator teknikal terkonfirmasi")

                            resolvedSnapshot = TradeSignalSnapshot(
                                strategyMode = log.strategyMode,
                                confidenceScore = log.confidence,
                                reasons = reasons
                            )
                            if (resolvedStrategy.equals("MANUAL", true) && log.strategyMode.isNotBlank()) {
                                resolvedStrategy = log.strategyMode
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val activeSnapshot = resolvedSnapshot
    val activeStrategy = resolvedStrategy
    val activeDuration = resolvedDuration
    val activeEntry = resolvedEntryPrice
    val activePnl = resolvedPnlIdr
    val activePnlPct = resolvedPnlPct
    val activeTrailing = resolvedTrailing
    val activeTrailingPercent = resolvedTrailingPercent
    val activePeakPrice = resolvedPeakPrice
    val activeLockPrice = resolvedLockPrice

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = TvCardBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(sideColor.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                .border(1.dp, sideColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = side.uppercase(),
                                color = sideColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "$baseAsset / $quote",
                                color = TvTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val exchangeName = "Tokocrypto"
                            Text(
                                text = "Mode: ${activeStrategy.uppercase()} • ${if (isReal) "Real $exchangeName" else "Simulasi"}",
                                color = TvBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
                    }
                }

                HorizontalDivider(color = TvBorder, modifier = Modifier.padding(vertical = 12.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Section 1: Execution & PnL Summary
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "1. HASIL EKSEKUSI & PNL",
                                color = TvAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                InfoItem("Harga Eksekusi", PriceFormatter.formatPrice(executionPrice, quoteAsset = quote), modifier = Modifier.weight(1f))
                                InfoItem("Jumlah", "${PriceFormatter.formatRawDecimal(quantity)} $baseAsset", modifier = Modifier.weight(1f))
                                InfoItem("Total", PriceFormatter.formatPrice(totalIdr, quoteAsset = quote), modifier = Modifier.weight(1f))
                            }

                            HorizontalDivider(color = TvBorder.copy(alpha = 0.5f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                InfoItem(
                                    "Durasi Hold",
                                    TradeLogExporter.formatDuration(activeDuration),
                                    highlightColor = TvBlue,
                                    modifier = Modifier.weight(1f)
                                )
                                if (activeEntry != null && activeEntry > 0.0) {
                                    InfoItem("Entry Buy", PriceFormatter.formatPrice(activeEntry, quoteAsset = quote), modifier = Modifier.weight(1f))
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }

                            if (activePnl != null) {
                                val isProfit = activePnl >= 0
                                val pColor = if (isProfit) TvGreen else TvRed
                                val prefix = if (isProfit) "+" else ""
                                InfoItem(
                                    "Realized PnL",
                                    "$prefix${PriceFormatter.formatPrice(activePnl, quoteAsset = quote)} ($prefix${String.format(Locale.US, "%.2f", activePnlPct ?: 0.0)}%)",
                                    highlightColor = pColor,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (activeTrailing) {
                                HorizontalDivider(color = TvBorder.copy(alpha = 0.5f))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    InfoItem("Trailing Lock", "AKTIF (${activeTrailingPercent ?: 1.5}%)", highlightColor = TvGreen, modifier = Modifier.weight(1f))
                                    activePeakPrice?.let {
                                        InfoItem("Peak Price", PriceFormatter.formatPrice(it, quoteAsset = quote), modifier = Modifier.weight(1f))
                                    }
                                    activeLockPrice?.let {
                                        InfoItem("Lock Stop Price", PriceFormatter.formatPrice(it, quoteAsset = quote), modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    // Section 2: Technical Indicators Breakdown (Categorized)
                    if (activeSnapshot != null) {
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "2. METRIK SINYAL TEKNIKAL LENGKAP",
                                    color = TvAmber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // Cat A: Order Book & Liquidity
                                CategoryBox(
                                    title = "A. Order Book & Likuiditas",
                                    items = listOf(
                                        "Bid Ratio" to (activeSnapshot.bidRatioPct?.let { PriceFormatter.formatPercentage(it, includePlusSign = false, decimals = 1) } ?: "-"),
                                        "Ask Ratio" to (activeSnapshot.askRatioPct?.let { PriceFormatter.formatPercentage(it, includePlusSign = false, decimals = 1) } ?: "-"),
                                        "Pressure" to (activeSnapshot.orderBookPressure?.let { if (it > 0) "+$it% (Buyer)" else "$it% (Seller)" } ?: "Netral"),
                                        "Spread" to (activeSnapshot.spreadPct?.let { PriceFormatter.formatPercentage(it, includePlusSign = false, decimals = 3) } ?: "-"),
                                        "24h Vol (quote)" to (activeSnapshot.volume24h?.let { PriceFormatter.formatRawDecimal(it) } ?: "-")
                                    )
                                )

                                // Cat B: Momentum & Oscillators
                                CategoryBox(
                                    title = "B. Momentum & Osilator",
                                    items = listOf(
                                        "RSI (14)" to (activeSnapshot.rsi14?.let { String.format(Locale.US, "%.2f", it) } ?: "-"),
                                        "MACD Line" to (activeSnapshot.macd?.let { String.format(Locale.US, "%.4f", it) } ?: "-"),
                                        "MACD Signal" to (activeSnapshot.macdSignal?.let { String.format(Locale.US, "%.4f", it) } ?: "-"),
                                        "MACD Hist" to (activeSnapshot.macdHist?.let { String.format(Locale.US, "%.4f", it) } ?: "-"),
                                        "Momentum" to (activeSnapshot.momentum?.let { String.format(Locale.US, "%.2f", it) } ?: "-")
                                    )
                                )

                                // Cat C: Trend & Moving Averages
                                CategoryBox(
                                    title = "C. Tren & Moving Averages (EMA)",
                                    items = listOf(
                                        "EMA 20" to (activeSnapshot.ema20?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "EMA 50" to (activeSnapshot.ema50?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "EMA 200" to (activeSnapshot.ema200?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "Trend" to (activeSnapshot.trendStatus ?: "Konsolidasi")
                                    )
                                )

                                // Cat D: Volatility & Bollinger Bands
                                CategoryBox(
                                    title = "D. Volatilitas & Bollinger Bands",
                                    items = listOf(
                                        "BB Upper" to (activeSnapshot.bbUpper?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "BB Middle" to (activeSnapshot.bbMiddle?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "BB Lower" to (activeSnapshot.bbLower?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"),
                                        "Bandwidth" to (activeSnapshot.bbWidthPct?.let { PriceFormatter.formatPercentage(it, includePlusSign = false, decimals = 2) } ?: "-"),
                                        "ATR" to (activeSnapshot.atr?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-")
                                    )
                                )

                                // Cat E: AI Decision Reasoning
                                CategoryBox(
                                    title = "E. AI Evaluator Decision & Key Factors",
                                    items = buildList {
                                        add("Score" to (activeSnapshot.confidenceScore?.let { "$it / 100" } ?: "-"))
                                        add("Regime" to (activeSnapshot.marketRegime ?: "-"))
                                        add("Pola" to (activeSnapshot.patternDetected ?: "-"))
                                        activeSnapshot.scalpingSetup?.let { setupName ->
                                            add("Setup Scalping" to agu.analys.util.ScalpingLabels.setup(setupName))
                                            add("Skor Engine" to "${activeSnapshot.scalpingScore ?: "-"} / 100 (${activeSnapshot.scalpingScoreCategory ?: "-"})")
                                            activeSnapshot.scalpingScoreDetail?.let { add("Rincian Skor" to it) }
                                            activeSnapshot.scalpingRegime?.let { add("Regime Engine" to it) }
                                            val zl = activeSnapshot.entryZoneLow
                                            val zh = activeSnapshot.entryZoneHigh
                                            if (zl != null && zh != null) {
                                                add("Zona Entry" to "${PriceFormatter.formatPrice(zl, quoteAsset = quote)} – ${PriceFormatter.formatPrice(zh, quoteAsset = quote)}")
                                            }
                                        }
                                    }
                                )

                                if (activeSnapshot.reasons.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(TvCardBackground, RoundedCornerShape(6.dp))
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            text = "Alasan Trigger Sinyal:",
                                            color = TvTextSecondary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        activeSnapshot.reasons.forEach { reason ->
                                            Text(
                                                text = "• $reason",
                                                color = TvTextPrimary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val exchangeNameUpper = "TOKOCRYPTO"
                            val exchangeName = "Tokocrypto"
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "2. INFORMASI TRANSAKSI RESMI $exchangeNameUpper",
                                    color = TvAmber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Order ini dieksekusi secara instan dan disinkronkan langsung dari akun resmi $exchangeName via API resmi.",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp
                                )
                                HorizontalDivider(color = TvBorder.copy(alpha = 0.5f))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    InfoItem("Status Order", "TEREKSEKUSI (FILLED)", highlightColor = TvGreen, modifier = Modifier.weight(1f))
                                    InfoItem("Tipe Order", orderType, modifier = Modifier.weight(1f))
                                    InfoItem("Estimasi Fee", PriceFormatter.formatPrice(feeIdr, quoteAsset = quote), modifier = Modifier.weight(1f))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    InfoItem("ID Transaksi", tradeId.take(18), modifier = Modifier.weight(1f))
                                    InfoItem("Waktu Eksekusi", timeFormat.format(Date(timestamp)), modifier = Modifier.weight(1.5f))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Bottom Action Buttons: Copy for LLM & Share
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            TradeLogExporter.copyTradeMarkdownToClipboard(
                                context = context,
                                tradeId = tradeId,
                                symbol = symbol,
                                baseAsset = baseAsset,
                                quoteAsset = quoteAsset,
                                side = side,
                                orderType = orderType,
                                executionPrice = executionPrice,
                                quantity = quantity,
                                totalIdr = totalIdr,
                                feeIdr = feeIdr,
                                timestamp = timestamp,
                                isReal = isReal,
                                strategyMode = resolvedStrategy,
                                holdingDurationMs = resolvedDuration,
                                entryPrice = resolvedEntryPrice,
                                entryTimestamp = entryTimestamp,
                                pnlIdr = resolvedPnlIdr,
                                pnlPercent = resolvedPnlPct,
                                // P0-5 — pakai nilai yang sudah diresolusi dari record, bukan param mentah,
                                // supaya ekspor tidak lagi melaporkan trailing "NONAKTIF" palsu.
                                isTrailingUsed = resolvedTrailing,
                                trailingPercent = resolvedTrailingPercent,
                                trailingPeakPrice = resolvedPeakPrice,
                                trailingLockPrice = resolvedLockPrice,
                                snapshot = resolvedSnapshot
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TvBlue)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Salin Log (AI Audit)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            TradeLogExporter.shareTradeLog(
                                context = context,
                                tradeId = tradeId,
                                symbol = symbol,
                                baseAsset = baseAsset,
                                quoteAsset = quoteAsset,
                                side = side,
                                orderType = orderType,
                                executionPrice = executionPrice,
                                quantity = quantity,
                                totalIdr = totalIdr,
                                feeIdr = feeIdr,
                                timestamp = timestamp,
                                isReal = isReal,
                                strategyMode = resolvedStrategy,
                                holdingDurationMs = resolvedDuration,
                                entryPrice = resolvedEntryPrice,
                                entryTimestamp = entryTimestamp,
                                pnlIdr = resolvedPnlIdr,
                                pnlPercent = resolvedPnlPct,
                                isTrailingUsed = resolvedTrailing,
                                trailingPercent = resolvedTrailingPercent,
                                trailingPeakPrice = resolvedPeakPrice,
                                trailingLockPrice = resolvedLockPrice,
                                snapshot = resolvedSnapshot
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp), tint = TvTextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    highlightColor: Color? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = TvTextSecondary,
            fontSize = 10.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = highlightColor ?: TvTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            lineHeight = 14.sp
        )
    }
}

@Composable
private fun CategoryBox(
    title: String,
    items: List<Pair<String, String>>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TvCardBackground, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Text(
            text = title,
            color = TvTextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        val chunks = items.chunked(2)
        chunks.forEachIndexed { idx, rowItems ->
            if (idx > 0) Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { (k, v) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = k,
                            color = TvTextSecondary.copy(alpha = 0.8f),
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                        Text(
                            text = v,
                            color = TvTextPrimary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 13.sp
                        )
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
