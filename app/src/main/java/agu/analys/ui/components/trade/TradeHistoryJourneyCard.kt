package agu.analys.ui.components.trade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.database.TradeHistoryRecordEntity
import agu.analys.trading.TradeLogExporter
import agu.analys.trading.TradeSignalSnapshot
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TradeHistoryJourneyCard(
    record: TradeHistoryRecordEntity,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    var showDetailDialog by remember { mutableStateOf(false) }
    val isClosed = record.status == "CLOSED"
    val isProfit = (record.pnlIdr ?: 0.0) >= 0.0
    val pnlColor = if (isProfit) TvGreen else TvRed
    val pnlPrefix = if (isProfit) "+" else ""
    val timeFormat = remember { SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()) }

    val snapshot = remember(record.signalSnapshotJson) {
        TradeSignalSnapshot.fromJsonString(record.signalSnapshotJson)
    }
    // Estimasi biaya memakai tarif bursa + kuotasi yang benar (bukan angka tetap 0,3%).
    val estimatedFeeRate = if (isClosed) {
        agu.analys.config.FeeSchedule.sellRate(record.exchange, record.quoteAsset)
    } else {
        agu.analys.config.FeeSchedule.buyRate(record.exchange, record.quoteAsset)
    }

    if (showDetailDialog) {
        TradeLogDetailDialog(
            tradeId = record.id.toString(),
            symbol = record.symbol,
            baseAsset = record.baseAsset,
            quoteAsset = record.quoteAsset,
            side = if (isClosed) "SELL" else "BUY",
            orderType = record.buyOrderType,
            executionPrice = if (isClosed) (record.sellPrice ?: record.buyPrice) else record.buyPrice,
            quantity = record.buyQuantity,
            totalIdr = if (isClosed) (record.sellTotalIdr ?: record.buyTotalIdr) else record.buyTotalIdr,
            feeIdr = record.buyTotalIdr * estimatedFeeRate,
            timestamp = if (isClosed) (record.sellTimestamp ?: record.buyTimestamp) else record.buyTimestamp,
            isReal = record.isReal,
            strategyMode = record.strategyMode,
            holdingDurationMs = record.holdingDurationMs,
            entryPrice = record.buyPrice,
            entryTimestamp = record.buyTimestamp,
            pnlIdr = record.pnlIdr,
            pnlPercent = record.pnlPercent,
            isTrailingUsed = record.isTrailingUsed,
            trailingPercent = record.trailingPercent,
            trailingPeakPrice = record.peakPrice,
            trailingLockPrice = record.trailingLockPrice,
            snapshot = snapshot,
            exchange = record.exchange,
            onDismiss = { showDetailDialog = false }
        )
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = BorderStroke(1.dp, if (isClosed) (if (isProfit) TvGreen.copy(alpha = 0.35f) else TvRed.copy(alpha = 0.35f)) else TvBlue.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // HEADER BARIS 1: Symbol & Status Badge (Kiri) vs PnL / Hasil Akhir (Kanan)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Surface(
                        color = if (isClosed) TvAmber.copy(alpha = 0.15f) else TvGreen.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, if (isClosed) TvAmber.copy(alpha = 0.4f) else TvGreen.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = if (isClosed) "BUY ➔ SELL" else "HOLDING",
                            color = if (isClosed) TvAmber else TvGreen,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = "${record.baseAsset}/${record.quoteAsset}",
                        color = TvTextPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        softWrap = false,
                        maxLines = 1
                    )
                    // P2-11 — penanda visual record contoh, supaya tidak tertukar dengan trade nyata.
                    if (record.isSample) {
                        Surface(
                            color = TvAmber.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(0.5.dp, TvAmber.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "SAMPEL",
                                color = TvAmber,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                if (isClosed && record.pnlIdr != null && record.pnlPercent != null) {
                    Text(
                        text = "$pnlPrefix${PriceFormatter.formatPrice(record.pnlIdr, quoteAsset = record.quoteAsset)} ($pnlPrefix${String.format(Locale.US, "%.2f", record.pnlPercent)}%)",
                        color = pnlColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        softWrap = false,
                        maxLines = 1
                    )
                } else {
                    Surface(
                        color = TvCyan.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, TvCyan.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "Peak: +${String.format(Locale.US, "%.2f", record.maxProfitPercent)}%",
                            color = TvCyan,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // HEADER BARIS 2: Strategy Badge, Real/Simulasi Badge, dan Waktu Trade
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Strategy Mode Badge
                    Surface(
                        color = TvBlue.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, TvBlue.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = record.strategyMode.uppercase(),
                            color = TvBlue,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    // Real Trade vs Simulasi Badge (Lebar bebas, tidak tertekan)
                    Surface(
                        color = if (record.isReal) TvGreen.copy(alpha = 0.15f) else TvCyan.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, if (record.isReal) TvGreen.copy(alpha = 0.35f) else TvCyan.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = if (record.isReal) "1:1 REAL" else "SIMULASI",
                            color = if (record.isReal) TvGreen else TvCyan,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    // Exchange Badge
                    Surface(
                        color = TvBlueSoft.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, TvBlueSoft.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = record.exchange.uppercase(),
                            color = TvBlueSoft,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    // Order Type (LIMIT / MARKET)
                    Surface(
                        color = TvSurfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, TvBorder.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = record.buyOrderType,
                            color = TvTextSecondary,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Medium,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = timeFormat.format(Date(record.buyTimestamp)),
                    color = TvTextSecondary.copy(alpha = 0.75f),
                    fontSize = 8.5.sp,
                    softWrap = false,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(10.dp))

            // 4-STAGE LIFECYCLE SUMMARY ROW
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                JourneyMiniStage(
                    stepNumber = "1",
                    title = "Sinyal Buy",
                    value = PriceFormatter.formatPrice(record.signalPrice, quoteAsset = record.quoteAsset),
                    sub = "Skor: ${record.signalConfidence}%",
                    modifier = Modifier.weight(1f)
                )
                JourneyMiniStage(
                    stepNumber = "2",
                    title = "User Buy",
                    value = PriceFormatter.formatPrice(record.buyPrice, quoteAsset = record.quoteAsset),
                    sub = PriceFormatter.formatPrice(record.buyTotalIdr, quoteAsset = record.quoteAsset),
                    modifier = Modifier.weight(1f)
                )
                JourneyMiniStage(
                    stepNumber = "3",
                    title = "Durasi Hold",
                    value = TradeLogExporter.formatDuration(record.holdingDurationMs),
                    sub = "Peak: +${String.format(Locale.US, "%.1f", record.maxProfitPercent)}%",
                    highlightColor = TvBlue,
                    modifier = Modifier.weight(1f)
                )
                JourneyMiniStage(
                    stepNumber = "4",
                    title = if (isClosed) "Sell Exit" else "Target TP",
                    value = if (isClosed && record.sellPrice != null) PriceFormatter.formatPrice(record.sellPrice, quoteAsset = record.quoteAsset) else PriceFormatter.formatPrice(record.targetPrice1, quoteAsset = record.quoteAsset),
                    sub = if (isClosed) (record.sellReason ?: "CLOSED") else "SL: ${PriceFormatter.formatPrice(record.stopLossPrice, quoteAsset = record.quoteAsset)}",
                    highlightColor = if (isClosed) pnlColor else TvAmber,
                    modifier = Modifier.weight(1f)
                )
            }

            // EXPANDED 4-STAGE DEEP DIVE (Perhitungan Mode, Alasan, Indikator Lengkap)
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = TvBorder.copy(alpha = 0.5f))

                    // Detail Tahap 1: Pengeluaran Sinyal Buy (Mode & Perhitungan Indikator)
                    StageDetailSection(
                        stepNumber = "TAHAP 1",
                        title = "PENGELUARAN SINYAL BUY DI DATABASE",
                        color = TvAmber
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MiniField("Mode Strategi", record.strategyMode, modifier = Modifier.weight(1f))
                                MiniField("Waktu Sinyal", timeFormat.format(Date(record.signalTimestamp)), modifier = Modifier.weight(1f))
                                MiniField("Tingkat Keyakinan", "${record.signalConfidence}%", highlightColor = TvGreen, modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Perhitungan Indikator:",
                                color = TvTextSecondary,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                color = TvSurfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    // P1-7/P1-8 — dulu ada string contoh yang di-hardcode
                                    // ("RSI(14)=38.2 • MACD Golden Cross …") sehingga record tanpa
                                    // perhitungan tampak punya angka teknikal palsu. Sekarang jujur "-".
                                    text = record.technicalBreakdown.ifBlank { "-" },
                                    color = TvTextPrimary,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                            if (record.signalReasoning.isNotBlank()) {
                                Text(
                                    text = "Log Alasan: ${record.signalReasoning}",
                                    color = TvTextSecondary,
                                    fontSize = 9.5.sp,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }

                    // Detail Tahap 2: Eksekusi User Buy
                    StageDetailSection(
                        stepNumber = "TAHAP 2",
                        title = "EKSEKUSI ORDER BUY OLEH USER",
                        color = TvGreen
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MiniField("Waktu Buy", timeFormat.format(Date(record.buyTimestamp)), modifier = Modifier.weight(1f))
                            MiniField("Harga Buy", PriceFormatter.formatPrice(record.buyPrice, quoteAsset = record.quoteAsset), modifier = Modifier.weight(1f))
                            MiniField("Jumlah Koin", "${PriceFormatter.formatRawDecimal(record.buyQuantity)} ${record.baseAsset}", modifier = Modifier.weight(1f))
                            MiniField("Total Modal", PriceFormatter.formatPrice(record.buyTotalIdr, quoteAsset = record.quoteAsset), modifier = Modifier.weight(1f))
                        }
                    }

                    // Detail Tahap 3: Durasi Hold & Tracking Perjalanan
                    StageDetailSection(
                        stepNumber = "TAHAP 3",
                        title = "DURASI HOLD & TRACKING PERJALANAN HARGA",
                        color = TvBlue
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MiniField("Durasi Menahan", TradeLogExporter.formatDuration(record.holdingDurationMs), highlightColor = TvBlue, modifier = Modifier.weight(1f))
                                // P0-6 — sebutkan sumber Peak/Trough agar tidak dikira selalu akurat.
                                MiniField(
                                    if (record.peakTrackedFromCandle) "Peak (candle)" else "Peak (dari tick)",
                                    PriceFormatter.formatPrice(record.peakPrice, quoteAsset = record.quoteAsset),
                                    modifier = Modifier.weight(1f)
                                )
                                MiniField("Drawdown Terendah", "-${String.format(Locale.US, "%.2f", kotlin.math.abs(record.maxDrawdownPercent))}%", highlightColor = TvRed, modifier = Modifier.weight(1f))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MiniField(
                                    "Trailing Stop Lock",
                                    if (record.isTrailingUsed) "AKTIF (${record.trailingPercent ?: 0.0}%)" else "NONAKTIF",
                                    highlightColor = if (record.isTrailingUsed) TvGreen else TvTextSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                                record.trailingLockPrice?.let {
                                    MiniField("Lock Stop Price", PriceFormatter.formatPrice(it, quoteAsset = record.quoteAsset), modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    // Detail Tahap 4: Eksekusi Sell & Realized Profit/Loss
                    StageDetailSection(
                        stepNumber = "TAHAP 4",
                        title = "EKSEKUSI SELL & HASIL AKHIR (PROFIT / LOSS)",
                        color = if (isProfit) TvGreen else TvRed
                    ) {
                        if (isClosed && record.sellPrice != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val sellTimeStr = record.sellTimestamp?.let { timeFormat.format(Date(it)) } ?: "-"
                                    MiniField(label = "Waktu Sell", value = sellTimeStr, modifier = Modifier.weight(1f))
                                    MiniField("Harga Sell", PriceFormatter.formatPrice(record.sellPrice, quoteAsset = record.quoteAsset), modifier = Modifier.weight(1f))
                                    MiniField("Pemicu Jual", record.sellReason ?: "MANUAL", highlightColor = TvAmber, modifier = Modifier.weight(1f))
                                }
                                Surface(
                                    color = if (isProfit) TvGreen.copy(alpha = 0.12f) else TvRed.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (isProfit) TvGreen.copy(alpha = 0.3f) else TvRed.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(text = "HASIL AKHIR REALIZED PNL", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                text = "$pnlPrefix${PriceFormatter.formatPrice(record.pnlIdr ?: 0.0, quoteAsset = record.quoteAsset)}",
                                                color = pnlColor,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        }
                                        Surface(
                                            color = if (isProfit) TvGreen else TvRed,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "$pnlPrefix${String.format(Locale.US, "%.2f", record.pnlPercent ?: 0.0)}%",
                                                color = Color.Black,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Black,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Surface(
                                color = TvBlue.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Posisi saat ini sedang berjalan (HOLDING). PnL final akan terkalkulasi saat user menjual koin atau saat Trailing Stop / TP tercapai.",
                                    color = TvBlue,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { showDetailDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, TvBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Analytics, contentDescription = null, tint = TvCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Detail Lengkap & Audit AI", color = TvTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .size(38.dp)
                                .border(1.dp, TvBorder, RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus Log", tint = TvRed, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Expand / Collapse prompt
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isExpanded) "Tutup Rincian Siklus ▲" else "Lihat Detail Sinyal ➔ Buy ➔ Hold ➔ Sell ▼",
                    color = TvTextSecondary,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun JourneyMiniStage(
    stepNumber: String,
    title: String,
    value: String,
    sub: String,
    highlightColor: Color? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TvSurfaceVariant,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.5.dp, TvBorder.copy(alpha = 0.5f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(TvBorder),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = stepNumber, color = TvTextPrimary, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(3.dp))
                Text(
                    text = title,
                    color = TvTextSecondary,
                    fontSize = 8.sp,
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                color = highlightColor ?: TvTextPrimary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                color = TvTextSecondary.copy(alpha = 0.8f),
                fontSize = 7.5.sp,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StageDetailSection(
    stepNumber: String,
    title: String,
    color: Color,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TvCardBackground, RoundedCornerShape(8.dp))
            .border(0.5.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = color.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = stepNumber,
                    color = color,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun MiniField(
    label: String,
    value: String,
    highlightColor: Color? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(text = label, color = TvTextSecondary, fontSize = 8.5.sp, maxLines = 1)
        Text(
            text = value,
            color = highlightColor ?: TvTextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
