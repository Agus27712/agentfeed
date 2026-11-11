package agu.analys.ui.components.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun OpenOrderItemCard(
    order: SimulationOrder,
    onCancel: () -> Unit
) {
    val isBuy = order.side == SimulationOrderSide.BUY
    val quote = order.quoteAsset.ifBlank { "IDR" }
    val timeFormat = remember { SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isBuy) TvGreen.copy(alpha = 0.2f) else TvRed.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isBuy) "Beli" else "Jual",
                            color = if (isBuy) TvGreen else TvRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${order.baseAsset}/${quote}",
                        color = TvTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "(${order.type.displayName})",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, TvRed.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .clickable { onCancel() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "Batal",
                        color = TvRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "Harga Order", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        text = PriceFormatter.formatPrice(order.limitPrice, quoteAsset = quote),
                        color = TvTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (order.type == SimulationOrderType.STOP_LIMIT) {
                        Text(
                            text = "Stop: ${PriceFormatter.formatPrice(order.stopPrice, quoteAsset = quote)}",
                            color = TvOrange,
                            fontSize = 10.sp
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Jumlah", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        text = "${PriceFormatter.formatQuantity(order.quantity)} ${order.baseAsset}",
                        color = TvTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "Total ($quote)", color = TvTextSecondary, fontSize = 10.sp)
                    Text(
                        text = PriceFormatter.formatPrice(order.totalIdr, quoteAsset = quote),
                        color = TvTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFormat.format(Date(order.createdAt)),
                    color = TvTextSecondary.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
                Text(
                    text = if (order.type == SimulationOrderType.STOP_LIMIT && !order.isStopTriggered) "Menunggu Trigger" else "Menunggu Match",
                    color = if (order.type == SimulationOrderType.STOP_LIMIT && !order.isStopTriggered) TvOrange else TvBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun TradeHistoryItemCard(history: SimulationTradeHistoryItem) {
    var showDetailDialog by remember { mutableStateOf(false) }
    val isBuy = history.side == SimulationOrderSide.BUY
    val quote = history.quoteAsset.ifBlank { "IDR" }
    val timeFormat = remember { SimpleDateFormat("dd/MM/yy HH:mm:ss", Locale.getDefault()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    if (showDetailDialog) {
        agu.analys.ui.components.trade.TradeLogDetailDialog(
            tradeId = history.id,
            symbol = history.symbol,
            baseAsset = history.baseAsset,
            quoteAsset = history.quoteAsset,
            side = history.side.name,
            orderType = history.type.displayName,
            executionPrice = history.executionPrice,
            quantity = history.quantity,
            totalIdr = history.totalIdr,
            feeIdr = history.feeIdr,
            timestamp = history.timestamp,
            isReal = history.isRealMirror,
            strategyMode = history.strategyMode,
            holdingDurationMs = history.holdingDurationMs,
            entryPrice = history.entryPrice,
            entryTimestamp = history.entryTimestamp,
            pnlIdr = history.pnlIdr,
            pnlPercent = history.pnlPercent,
            isTrailingUsed = history.isTrailingUsed,
            trailingPercent = history.trailingPercent,
            trailingPeakPrice = history.trailingPeakPrice,
            trailingLockPrice = history.trailingLockPrice,
            snapshot = history.signalSnapshot,
            onDismiss = { showDetailDialog = false }
        )
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDetailDialog = true }
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
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isBuy) TvGreen.copy(alpha = 0.2f) else TvRed.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isBuy) "Beli" else "Jual",
                            color = if (isBuy) TvGreen else TvRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${history.baseAsset}/${quote}",
                        color = TvTextPrimary,
                        fontSize = 12.5.sp,
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
                            text = history.strategyMode.uppercase(),
                            color = TvBlue,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    if (history.isRealMirror) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TvGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "1:1 REAL",
                                color = TvGreen,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                if (history.pnlIdr != null && history.pnlPercent != null) {
                    val isProfit = history.pnlIdr >= 0
                    val pnlSign = if (isProfit) "+" else "-"
                    Text(
                        text = "$pnlSign${PriceFormatter.formatPrice(kotlin.math.abs(history.pnlIdr), quoteAsset = quote)} (${String.format(Locale.US, "%.2f", history.pnlPercent)}%)",
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
                        text = PriceFormatter.formatPrice(history.executionPrice, quoteAsset = quote),
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Jumlah Koin", color = TvTextSecondary, fontSize = 10.sp, maxLines = 1)
                    Text(
                        text = "${PriceFormatter.formatQuantity(history.quantity)} ${history.baseAsset}",
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(text = "Total Eksekusi", color = TvTextSecondary, fontSize = 10.sp, maxLines = 1)
                    Text(
                        text = PriceFormatter.formatPrice(history.totalIdr, quoteAsset = quote),
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Metadata Row: Holding Duration, Trailing Lock, Signal Snapshot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (history.holdingDurationMs != null && history.holdingDurationMs > 0L) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TvSurfaceVariant)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "⏱ ${agu.analys.trading.TradeLogExporter.formatDuration(history.holdingDurationMs)}",
                                color = TvTextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (history.isTrailingUsed) {
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

                    if (history.signalSnapshot != null) {
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Detail & AI Log ›",
                        color = TvBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFormat.format(Date(history.timestamp)),
                    color = TvTextSecondary.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
                Text(
                    text = "Fee: ${PriceFormatter.formatPrice(history.feeIdr, quoteAsset = quote)}",
                    color = TvTextSecondary.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun SimulationEmptyStateView(
    icon: ImageVector,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TvTextSecondary,
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            color = TvTextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}
