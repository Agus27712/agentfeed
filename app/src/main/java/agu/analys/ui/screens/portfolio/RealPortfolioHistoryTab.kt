package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.database.AppDatabase
import agu.analys.database.RealTradeEntity
import agu.analys.database.TradeHistoryRecordEntity
import agu.analys.trading.TradeLogExporter
import agu.analys.trading.TradeSignalSnapshot
import agu.analys.ui.components.trade.TradeLogDetailDialog
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun LazyListScope.realPortfolioHistorySection(
    realTrades: List<RealTradeEntity>
) {
    if (realTrades.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvCardBackground, RoundedCornerShape(10.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Belum ada riwayat transaksi ter-cache (Mulai terkumpul seiring refresh/trade).",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    } else {
        items(realTrades, key = { "real_trade_${it.id}_${it.symbol}" }) { trade ->
            RealTradeHistoryItemCard(trade = trade)
        }
    }
}

@Composable
fun RealTradeHistoryItemCard(
    trade: RealTradeEntity,
    modifier: Modifier = Modifier
) {
    var showDetailDialog by remember { mutableStateOf(false) }
    val symbolUpper = trade.symbol.uppercase()
    val sideColor = if (trade.isBuyer) TvGreen else TvRed
    val quoteAsset = when {
        symbolUpper.endsWith("USDT") || symbolUpper.contains("_USDT") -> "USDT"
        symbolUpper.endsWith("BIDR") || symbolUpper.contains("_BIDR") -> "BIDR"
        else -> "IDR"
    }
    val baseAsset = symbolUpper
        .replace("USDT", "")
        .replace("BIDR", "")
        .replace("IDR", "")
        .replace("_", "")
        .replace("/", "")
        .ifEmpty { symbolUpper }

    val snapshot = remember(trade.signalSnapshotJson) {
        TradeSignalSnapshot.fromJsonString(trade.signalSnapshotJson)
    }

    val matchingRecord by produceState<TradeHistoryRecordEntity?>(initialValue = null, key1 = trade.id) {
        value = withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getInstance()
                val norm = trade.symbol.uppercase().replace("_", "")
                val alt = if (norm.endsWith("IDR")) norm else "${norm}IDR"
                // Batasi pada bursa yang sama agar catatan bursa lain tidak tertukar.
                val records: List<TradeHistoryRecordEntity> =
                    db.tradeHistoryRecordDao().getRecordsForSymbolAndExchange(norm, alt, trade.exchange)
                records.firstOrNull { rec: TradeHistoryRecordEntity ->
                    val buyDiff = java.lang.Math.abs(rec.buyTime - trade.time)
                    val sellDiff = java.lang.Math.abs((rec.sellTime ?: 0L) - trade.time)
                    (trade.isBuyer && (rec.buyPrice == trade.price || buyDiff < 600000L)) ||
                    (!trade.isBuyer && (rec.sellPrice == trade.price || sellDiff < 600000L))
                } ?: records.firstOrNull { it.signalSnapshotJson != null }
            } catch (_: Exception) {
                null
            }
        }
    }

    val effectiveSnapshot = snapshot ?: matchingRecord?.signalSnapshotJson?.let { TradeSignalSnapshot.fromJsonString(it) }
    val effectiveStrategy = if (trade.strategyMode != "MANUAL") trade.strategyMode else (matchingRecord?.strategyMode ?: "SWING")
    val effectiveDuration = trade.holdingDurationMs ?: matchingRecord?.holdingDurationMs
    val effectiveEntry = trade.entryPrice ?: matchingRecord?.buyPrice
    val effectivePnlIdr = trade.pnlIdr ?: matchingRecord?.pnlIdr
    val effectivePnlPercent = trade.pnlPercent ?: matchingRecord?.pnlPercent
    val effectiveTrailing = trade.isTrailingUsed || (matchingRecord?.isTrailingUsed == true)
    val effectiveTrailingPercent = trade.trailingPercent ?: matchingRecord?.trailingPercent
    val effectivePeakPrice = trade.trailingPeakPrice ?: matchingRecord?.peakPriceDuringHold
    val effectiveLockPrice = trade.trailingLockPrice ?: matchingRecord?.trailingLockPrice
    // Estimasi biaya memakai tarif bursa + kuotasi yang benar (bukan angka tetap 0,3%).
    val estimatedFeeRate = if (trade.isBuyer) {
        agu.analys.config.FeeSchedule.buyRate(trade.exchange, quoteAsset)
    } else {
        agu.analys.config.FeeSchedule.sellRate(trade.exchange, quoteAsset)
    }

    if (showDetailDialog) {
        TradeLogDetailDialog(
            tradeId = trade.id,
            symbol = trade.symbol,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            side = trade.side,
            orderType = "LIMIT",
            executionPrice = trade.price,
            quantity = trade.qty,
            totalIdr = trade.amount,
            feeIdr = trade.amount * estimatedFeeRate,
            timestamp = trade.time,
            isReal = true,
            strategyMode = effectiveStrategy,
            holdingDurationMs = effectiveDuration,
            entryPrice = effectiveEntry,
            entryTimestamp = trade.entryTimestamp ?: matchingRecord?.buyTime,
            pnlIdr = effectivePnlIdr,
            pnlPercent = effectivePnlPercent,
            isTrailingUsed = effectiveTrailing,
            trailingPercent = effectiveTrailingPercent,
            trailingPeakPrice = effectivePeakPrice,
            trailingLockPrice = effectiveLockPrice,
            snapshot = effectiveSnapshot,
            exchange = trade.exchange,
            onDismiss = { showDetailDialog = false }
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showDetailDialog = true },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = sideColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = trade.side.uppercase(),
                            color = sideColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$baseAsset / $quoteAsset",
                        color = TvTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(TvBlue.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = effectiveStrategy.uppercase(),
                            color = TvBlue,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(TvGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "REAL",
                            color = TvGreen,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                if (effectivePnlIdr != null && effectivePnlPercent != null) {
                    val isProfit = effectivePnlIdr >= 0
                    val pnlSign = if (isProfit) "+" else "-"
                    Text(
                        text = "$pnlSign${PriceFormatter.formatPrice(kotlin.math.abs(effectivePnlIdr), quoteAsset = quoteAsset)} (${String.format(Locale.US, "%.2f", effectivePnlPercent)}%)",
                        color = if (isProfit) TvGreen else TvRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Harga Eksekusi", color = TvTextSecondary, fontSize = 10.sp, maxLines = 1)
                    Text(
                        text = PriceFormatter.formatPrice(trade.price, quoteAsset = quoteAsset),
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Jumlah", color = TvTextSecondary, fontSize = 10.sp, maxLines = 1)
                    Text(
                        text = "${PriceFormatter.formatRawDecimal(trade.qty)} $baseAsset",
                        color = TvBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(text = "Total Nominal", color = TvTextSecondary, fontSize = 10.sp, maxLines = 1)
                    Text(
                        text = PriceFormatter.formatPrice(trade.amount, quoteAsset = quoteAsset),
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Metadata Row: Duration, Trailing, Snapshot, Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (trade.holdingDurationMs != null && trade.holdingDurationMs > 0L) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TvSurfaceVariant)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "⏱ ${TradeLogExporter.formatDuration(trade.holdingDurationMs)}",
                                color = TvTextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (trade.isTrailingUsed) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TvGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "🔒 Trailing Lock",
                                color = TvGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (snapshot != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TvAmber.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "📊 Sinyal OK",
                                color = TvAmber,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    text = "Detail & AI Log ›",
                    color = TvBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(4.dp))

            val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
            val dateStr = remember(trade.time) { dateFormat.format(Date(trade.time)) }
            Text(
                text = dateStr,
                color = TvTextSecondary.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }
    }
}

