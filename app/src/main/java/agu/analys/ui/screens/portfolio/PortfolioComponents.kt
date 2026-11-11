package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.ui.components.dashboard.AssetAvatar
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HoldingCoinCard(
    item: HoldingItem,
    onTrade: () -> Unit,
    onViewChart: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Prefix `$` vs `Rp` ditentukan oleh kuotasi posisi, bukan asumsi.
    val quoteAsset = item.quoteAsset
    val isUsdt = item.isUsdtPosition
    val quoteColor = if (isUsdt) TvGreen else TvBlue

    // PnL ditampilkan dalam kuotasi posisi: posisi USDT → `$`, posisi IDR → `Rp`.
    val pnlDisplayValue = if (isUsdt) item.pnlInQuote else item.pnlIdr
    val pnlDisplayQuote = if (isUsdt) quoteAsset else "IDR"

    val isProfit = pnlDisplayValue >= 0
    val pnlColor = if (isProfit) TvGreen else TvRed
    val pnlBg = if (isProfit) TvGreen.copy(alpha = 0.15f) else TvRed.copy(alpha = 0.15f)
    val pnlBorder = if (isProfit) TvGreen.copy(alpha = 0.4f) else TvRed.copy(alpha = 0.4f)
    val pnlPrefix = if (isProfit) "+" else ""

    // Ekuivalen Rupiah hanya ditampilkan bila kurs tersedia (tanpa hardcode).
    val valueRpText = if (isUsdt) {
        agu.analys.util.PriceFormatter.formatIdrEquivalent(item.totalValueInQuote, item.usdtIdrRate)
    } else {
        null
    }
    val pnlRpText = if (isUsdt) {
        agu.analys.util.PriceFormatter.formatIdrEquivalent(item.pnlInQuote, item.usdtIdrRate)
    } else {
        null
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Row 1: Asset Info & Total Value
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssetAvatar(
                        baseAsset = item.baseAsset,
                        size = 38.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${item.baseAsset} / ${quoteAsset.uppercase()}",
                                color = TvTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            if (item.isRealMirror) {
                                Box(
                                    modifier = Modifier
                                        .background(TvGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .border(0.5.dp, TvGreen, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("1:1 REAL", color = TvGreen, fontSize = 8.sp, fontWeight = FontWeight.Black)
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .background(TvBlue.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .border(0.5.dp, TvBlue, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("SIM ONLY", color = TvBlue, fontSize = 8.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                        Text(
                            text = "Miliki: ${PriceFormatter.formatRawDecimal(item.quantity)} ${item.baseAsset}",
                            color = TvBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Total Nilai Aset — dalam kuotasi posisi ($ atau Rp), plus ekuivalen Rp bila USDT.
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isUsdt) {
                            PriceFormatter.formatPrice(item.totalValueInQuote, quoteAsset = quoteAsset)
                        } else {
                            PriceFormatter.formatPrice(item.totalValueIdr, quoteAsset = "IDR")
                        },
                        color = TvTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                    if (valueRpText != null) {
                        Text(
                            text = valueRpText,
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "Total Nilai Aset",
                        color = TvTextSecondary,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Row 2: Rincian Komparasi Harga Saat Beli vs Harga Pasar Saat Ini
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TvSurfaceVariant)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "HARGA SAAT BELI",
                            color = TvTextSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = PriceFormatter.formatPrice(item.avgBuyPrice, quoteAsset = quoteAsset),
                            color = quoteColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "HARGA PASAR SAAT INI",
                            color = TvTextSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = PriceFormatter.formatPrice(item.currentPrice, quoteAsset = quoteAsset),
                            color = TvTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isUsdt) {
                            Text(
                                text = "pair ${item.baseAsset}/USDT",
                                color = TvTextSecondary,
                                fontSize = 8.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Highlight Status Keuntungan (Profit / Loss)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(pnlBg)
                        .border(1.dp, pnlBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isProfit) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                            contentDescription = null,
                            tint = pnlColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isProfit) "ESTIMASI PROFIT" else "ESTIMASI FLOATING LOSS",
                            color = pnlColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "$pnlPrefix${PriceFormatter.formatPrice(pnlDisplayValue, quoteAsset = pnlDisplayQuote)} ($pnlPrefix${String.format(Locale.US, "%.2f", item.pnlPercent)}%)",
                            color = pnlColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                        if (pnlRpText != null) {
                            Text(
                                text = pnlRpText,
                                color = pnlColor.copy(alpha = 0.75f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Tombol Aksi: Trade / Jual & Buka Chart
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onViewChart,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TvBlue),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.ShowChart, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Lihat Chart", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onTrade,
                    modifier = Modifier.weight(1.3f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isProfit) TvGreen else TvRed
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = if (isProfit) Icons.Default.PointOfSale else Icons.AutoMirrored.Filled.CompareArrows,
                        contentDescription = null,
                        tint = if (isProfit) Color.Black else Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isProfit) "Jual (Ambil Profit)" else "Trade / Jual Koin",
                        color = if (isProfit) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

@Composable
fun TradeHistoryItemCard(
    trade: SimulationTradeHistoryItem,
    modifier: Modifier = Modifier
) {
    var showDetailDialog by remember { mutableStateOf(false) }
    val isBuy = trade.side == SimulationOrderSide.BUY
    val sideColor = if (isBuy) TvGreen else TvRed
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
    val formattedTime = remember(trade.timestamp) { dateFormat.format(Date(trade.timestamp)) }

    if (showDetailDialog) {
        agu.analys.ui.components.trade.TradeLogDetailDialog(
            tradeId = trade.id,
            symbol = trade.symbol,
            baseAsset = trade.baseAsset,
            quoteAsset = trade.quoteAsset,
            side = trade.side.name,
            orderType = trade.type.displayName,
            executionPrice = trade.executionPrice,
            quantity = trade.quantity,
            totalIdr = trade.totalIdr,
            feeIdr = trade.feeIdr,
            timestamp = trade.timestamp,
            isReal = trade.isRealMirror,
            strategyMode = trade.strategyMode,
            holdingDurationMs = trade.holdingDurationMs,
            entryPrice = trade.entryPrice,
            entryTimestamp = trade.entryTimestamp,
            pnlIdr = trade.pnlIdr,
            pnlPercent = trade.pnlPercent,
            isTrailingUsed = trade.isTrailingUsed,
            trailingPercent = trade.trailingPercent,
            trailingPeakPrice = trade.trailingPeakPrice,
            trailingLockPrice = trade.trailingLockPrice,
            snapshot = trade.signalSnapshot,
            onDismiss = { showDetailDialog = false }
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showDetailDialog = true },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
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
                            .background(sideColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = trade.side.displayName.uppercase(),
                            color = sideColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${trade.baseAsset} / ${trade.quoteAsset}",
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
                            text = trade.strategyMode.uppercase(),
                            color = TvBlue,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    if (trade.isRealMirror) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .background(TvGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .border(0.5.dp, TvGreen, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("1:1 REAL", color = TvGreen, fontSize = 8.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
                Text(
                    text = formattedTime,
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Harga Eksekusi", color = TvTextSecondary, fontSize = 10.sp, maxLines = 1)
                    Text(
                        PriceFormatter.formatPrice(trade.executionPrice, quoteAsset = trade.quoteAsset),
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Jumlah", color = TvTextSecondary, fontSize = 10.sp, maxLines = 1)
                    Text(
                        "${PriceFormatter.formatRawDecimal(trade.quantity)} ${trade.baseAsset}",
                        color = TvBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    val isUsdt = PriceFormatter.isUsdtQuote(trade.quoteAsset)
                    Text(if (isUsdt) "Total (${trade.quoteAsset})" else "Total Rupiah", color = TvTextSecondary, fontSize = 10.sp, maxLines = 1)
                    Text(
                        PriceFormatter.formatPrice(trade.totalIdr, quoteAsset = trade.quoteAsset),
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                }
            }

            if (trade.pnlIdr != null && trade.pnlIdr != 0.0) {
                Spacer(Modifier.height(6.dp))
                val pnlColor = if (trade.pnlIdr >= 0) TvGreen else TvRed
                val pnlPrefix = if (trade.pnlIdr >= 0) "+" else ""
                Text(
                    text = "Realized PnL: $pnlPrefix${PriceFormatter.formatPrice(trade.pnlIdr, quoteAsset = trade.quoteAsset)} (${pnlPrefix}${String.format(Locale.US, "%.2f", trade.pnlPercent ?: 0.0)}%)",
                    color = pnlColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(6.dp))

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
                                text = "⏱ ${agu.analys.trading.TradeLogExporter.formatDuration(trade.holdingDurationMs)}",
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

                    if (trade.signalSnapshot != null) {
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
        }
    }
}

@Composable
fun EmptyHoldingsCard(
    onNavigateToSimulation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Savings,
                contentDescription = null,
                tint = TvTextSecondary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Belum Ada Koin yang Dimiliki",
                color = TvTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Gunakan saldo Rupiah yang tersedia untuk mulai membeli koin pada simulasi trading.",
                color = TvTextSecondary,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onNavigateToSimulation,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
            ) {
                Icon(Icons.AutoMirrored.Filled.CompareArrows, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Beli Koin Pertama", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun EmptyHistoryCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = TvTextSecondary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Belum Ada Riwayat Transaksi",
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Setiap order beli atau jual yang berhasil dieksekusi akan tercatat otomatis di sini.",
                color = TvTextSecondary,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}