package agu.analys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.AISignalState
import agu.analys.model.SignalAction
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_HISTORY_ITEMS = 30

@Composable
fun SignalHistoryPanel(
    history: List<AISignalState>,
    currentSymbol: String,
    position: SpotPosition = SpotPosition(),
    onOpenAllLogs: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentQuote = remember(currentSymbol) {
        val clean = currentSymbol.uppercase().replace("_", "").trim()
        when {
            clean.endsWith("USDT") -> "USDT"
            clean.endsWith("USDC") -> "USDC"
            clean.endsWith("BUSD") -> "BUSD"
            clean.endsWith("BIDR") -> "BIDR"
            clean.endsWith("IDR") -> "IDR"
            else -> "IDR"
        }
    }
    val visibleHistory = remember(history, currentSymbol) {
        history
            .filter { it.marketSymbol.equals(currentSymbol, ignoreCase = true) }
            .sortedByDescending { it.timestamp }
    }
    val recentHistory = remember(visibleHistory) { visibleHistory.take(MAX_HISTORY_ITEMS) }
    val buyCount = visibleHistory.count { it.action == SignalAction.BUY }
    val sellCount = visibleHistory.count { it.action == SignalAction.SELL }
    val openCount = visibleHistory.count { it.action != SignalAction.HOLD && hasPositionLevels(it) }
    val timeFormat = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.US) }
    val ownershipLabel = if (position.isHolding) "HOLDING" else "NO POSITION"
    val ownershipColor = if (position.isHolding) TvGreen else TvTextSecondary
    val context = androidx.compose.ui.platform.LocalContext.current
    val positionStore = remember(context) { SpotPositionStore(context) }
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground)
    ) {
        Column(Modifier.padding(18.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("RIWAYAT & LOG SINYAL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary, letterSpacing = 1.2.sp)
                    Text(
                        if (visibleHistory.isEmpty()) "Belum ada sinyal untuk koin ini."
                        else if (expanded) "Detail sinyal terbaru"
                        else "Ringkasan saja, detail disembunyikan",
                        fontSize = 9.sp,
                        color = TvTextSecondary
                    )
                }
                Text("${visibleHistory.size} sinyal", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TvGreen)
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { if (visibleHistory.isNotEmpty()) expanded = !expanded },
                    enabled = visibleHistory.isNotEmpty(),
                    modifier = Modifier.padding(0.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Sembunyikan riwayat" else "Tampilkan riwayat",
                        tint = if (visibleHistory.isNotEmpty()) TvTextPrimary else TvTextSecondary
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ownershipColor.copy(alpha = 0.10f))
                    .border(1.dp, ownershipColor.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("STATUS POSISI", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = ownershipColor, letterSpacing = 0.6.sp)
                    Text(
                        if (position.isHolding) "Punya $currentSymbol ${if (position.isReal) "di Tokocrypto" else "di Simulasi"}"
                        else "Belum punya $currentSymbol",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TvTextPrimary
                    )
                    if (position.isHolding && position.entryPrice > 0.0) {
                        Text("Harga beli: ${PriceFormatter.formatPrice(position.entryPrice, quoteAsset = currentQuote)}", fontSize = 9.sp, color = TvTextSecondary)
                    }
                }
                Text(ownershipLabel, fontSize = 10.sp, fontWeight = FontWeight.Black, color = ownershipColor)
            }

            if (visibleHistory.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    HistorySummary("BELI", buyCount, TvGreen, Modifier.weight(1f))
                    HistorySummary("JUAL", sellCount, TvRed, Modifier.weight(1f))
                    HistorySummary("SETUP", openCount, TvAmber, Modifier.weight(1f))
                }
            }

            if (expanded && visibleHistory.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("30 SINYAL TERBARU", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary, letterSpacing = 0.8.sp)
                Spacer(Modifier.height(5.dp))

                recentHistory.forEach { signal ->
                    val color = when (signal.action) {
                        SignalAction.BUY -> TvGreen
                        SignalAction.SELL -> TvRed
                        SignalAction.HOLD -> TvAmber
                    }
                    val actionLabel = when (signal.action) {
                        SignalAction.BUY -> "BELI"
                        SignalAction.SELL -> "JUAL"
                        SignalAction.HOLD -> "TAHAN"
                    }
                    val historicalPosition = positionStore.getAt(currentSymbol, signal.timestamp, isReal = position.isReal)
                    val historicalHolding = if (position.isHolding && (historicalPosition.isHolding || position.openedAt == 0L || signal.timestamp >= position.openedAt)) true else historicalPosition.isHolding
                    val historicalOwnershipLabel = if (historicalHolding) "PUNYA" else "TIDAK PUNYA"
                    val historicalOwnershipColor = if (historicalHolding) TvGreen else TvTextSecondary
                    val levelLifecycle = if (hasPositionLevels(signal)) "LEVEL LENGKAP" else "LEVEL TIDAK LENGKAP"
                    val signalQuote = remember(signal.marketSymbol, currentSymbol) {
                        val raw = if (signal.marketSymbol.isNotBlank()) signal.marketSymbol else currentSymbol
                        val clean = raw.uppercase().replace("_", "").trim()
                        when {
                            clean.endsWith("USDT") -> "USDT"
                            clean.endsWith("USDC") -> "USDC"
                            clean.endsWith("BUSD") -> "BUSD"
                            clean.endsWith("BIDR") -> "BIDR"
                            clean.endsWith("IDR") -> "IDR"
                            else -> "IDR"
                        }
                    }
                    val advice = ownershipAdvice(signal.action, historicalHolding)

                    Box(
                        Modifier.fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TvCardBackground)
                            .border(1.dp, TvBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                        Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Black, color = color)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(currentSymbol, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
                                        Text("Entry ${PriceFormatter.formatPrice(signal.entryPrice, quoteAsset = signalQuote)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TvTextPrimary)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Skor ${signal.confidence}/100", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color)
                                    Text(timeFormat.format(Date(signal.timestamp)), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TvTextSecondary)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PriceBox("TP1", signal.targetPrice1, TvGreen, quoteAsset = signalQuote, modifier = Modifier.weight(1f))
                                PriceBox("TP2", signal.targetPrice2, TvGreen, quoteAsset = signalQuote, modifier = Modifier.weight(1f))
                                PriceBox("SL", signal.stopLoss, TvRed, quoteAsset = signalQuote, modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(7.dp))
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(historicalOwnershipColor.copy(alpha = 0.09f))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("STATUS SAAT SINYAL", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = historicalOwnershipColor)
                                    Text(advice, fontSize = 9.sp, color = TvTextSecondary, maxLines = 2)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(historicalOwnershipLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = historicalOwnershipColor)
                                    Text(levelLifecycle, fontSize = 8.sp, color = TvTextSecondary)
                                }
                            }
                        }
                    }
                }

                if (visibleHistory.size > MAX_HISTORY_ITEMS) {
                    Spacer(Modifier.height(5.dp))
                    Text("Menampilkan 30 sinyal terbaru. Riwayat lama tetap tersimpan, tetapi tidak dimuat ke UI sekaligus.", fontSize = 9.sp, color = TvTextSecondary)
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onOpenAllLogs,
                modifier = Modifier.fillMaxWidth().height(36.dp),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvCyan.copy(alpha = 0.5f))
            ) {
                Icon(
                    Icons.Default.Assessment,
                    contentDescription = null,
                    tint = TvCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Buka Database & Evaluasi Reliabilitas (Room DB)",
                    color = TvCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun hasPositionLevels(signal: AISignalState): Boolean =
    signal.action != SignalAction.HOLD &&
        signal.entryPrice > 0.0 &&
        signal.targetPrice1 > 0.0 &&
        signal.targetPrice2 > 0.0 &&
        signal.stopLoss > 0.0

private fun ownershipAdvice(action: SignalAction, isHolding: Boolean): String = when {
    !isHolding && action == SignalAction.BUY -> "Belum punya koin → sinyal beli bisa jadi pertimbangan untuk masuk."
    !isHolding && action == SignalAction.SELL -> "Belum punya koin → sinyal jual tidak relevan karena belum ada yang dijual."
    !isHolding && action == SignalAction.HOLD -> "Belum punya koin → tunggu sinyal beli yang lebih jelas."
    isHolding && action == SignalAction.SELL -> "Sudah punya koin → sinyal jual bisa jadi pertimbangan untuk keluar."
    isHolding && action == SignalAction.BUY -> "Sudah punya koin → jangan menambah posisi hanya karena ada sinyal beli."
    isHolding && action == SignalAction.HOLD -> "Sudah punya koin → tahan selama belum ada alasan kuat untuk keluar."
    else -> "Sesuaikan dengan kondisi posisi saat itu."
}

@Composable
private fun HistorySummary(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Column {
            Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = color)
            Text(count.toString(), fontSize = 12.sp, fontWeight = FontWeight.Black, color = TvTextPrimary)
        }
    }
}

@Composable
private fun PriceBox(
    label: String,
    value: Double,
    color: Color,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    Column(modifier.clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.08f)).padding(horizontal = 6.dp, vertical = 5.dp)) {
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = color)
        Text(if (value > 0) PriceFormatter.formatPrice(value, quoteAsset = quoteAsset) else "-", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TvTextPrimary, maxLines = 1)
    }
}
