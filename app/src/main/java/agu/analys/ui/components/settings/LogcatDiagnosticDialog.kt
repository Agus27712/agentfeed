package agu.analys.ui.components.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import agu.analys.trading.SpotPosition
import agu.analys.trading.SpotPositionStore
import agu.analys.ui.theme.*
import agu.analys.util.AppLogEntry
import agu.analys.util.AppLogManager
import agu.analys.util.PriceFormatter
import agu.analys.util.LogCategory
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatDiagnosticDialog(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf(LogCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var refreshTrigger by remember { mutableStateOf(0) }
    var nativeLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingNative by remember { mutableStateOf(false) }

    val posStore = remember { SpotPositionStore(context) }
    val allPositions = remember(refreshTrigger) { posStore.getAllPositions() }
    val activeTrailingSymbols = remember(refreshTrigger) { posStore.getAllActiveTrailingSymbols() }
    val logVersion by AppLogManager.logVersion.collectAsState()

    val logs = remember(selectedCategory, searchQuery, refreshTrigger, logVersion) {
        AppLogManager.getLogs(selectedCategory, searchQuery)
    }

    LaunchedEffect(selectedCategory, refreshTrigger) {
        if (selectedCategory == LogCategory.SYSTEM_LOGCAT) {
            isLoadingNative = true
            nativeLogs = AppLogManager.fetchNativeLogcat(300)
            isLoadingNative = false
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = TvBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                                .border(1.dp, TvBorder, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = TvGreen, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text(
                                text = "Diagnostik & Logcat Lengkap",
                                color = TvTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Pemeriksaan State Trailing, Data Pasar & Log Error",
                                color = TvTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Action Toolbar: Export, Copy, Refresh, Clear
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val dump = AppLogManager.buildDiagnosticStateDump(context)
                            AppLogManager.exportAndShareLog(context, dump)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("Ekspor & Bagikan", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    OutlinedButton(
                        onClick = {
                            val dump = AppLogManager.buildDiagnosticStateDump(context)
                            val ok = AppLogManager.copyToClipboard(context, dump)
                            Toast.makeText(
                                context,
                                if (ok) "Diagnostik lengkap disalin ke Clipboard!" else "Gagal menyalin",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TvTextPrimary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = TvTextPrimary)
                        Spacer(Modifier.width(4.dp))
                        Text("Salin Dump", fontSize = 11.sp, color = TvTextPrimary)
                    }

                    OutlinedButton(
                        onClick = {
                            refreshTrigger++
                            Toast.makeText(context, "State diperbarui", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TvTextPrimary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = TvGreen)
                        Spacer(Modifier.width(2.dp))
                        Text("Segarkan", fontSize = 11.sp, color = TvTextPrimary)
                    }

                    OutlinedButton(
                        onClick = {
                            AppLogManager.clearLogs()
                            refreshTrigger++
                            Toast.makeText(context, "Log in-memory dibersihkan", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TvRed),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(14.dp), tint = TvRed)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Search Box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Cari pesan log, tag, atau nama koin...", color = TvTextSecondary, fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TvTextSecondary, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TvTextSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = TvSurface,
                        unfocusedContainerColor = TvSurface,
                        focusedBorderColor = TvBlue,
                        unfocusedBorderColor = TvBorder,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary
                    ),
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                // Categories Horizontal Tab Bar
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(LogCategory.values()) { cat ->
                        val isSelected = selectedCategory == cat
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { selectedCategory = cat },
                            color = if (isSelected) TvBlue.copy(alpha = 0.2f) else TvSurface,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) TvBlue else TvBorder
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(cat.emoji, fontSize = 11.sp)
                                Text(
                                    cat.displayName,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) TvBlueSoft else TvTextPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Content View based on category
                if (selectedCategory == LogCategory.TRAILING) {
                    // Trailing Stops Inspector View with realtime trailing logs
                    TrailingStateInspectionView(
                        allPositions = allPositions,
                        activeTrailingSymbols = activeTrailingSymbols,
                        searchQuery = searchQuery,
                        logs = logs
                    )
                } else if (selectedCategory == LogCategory.SYSTEM_LOGCAT) {
                    // Native Logcat View
                    if (isLoadingNative) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = TvBlue, modifier = Modifier.size(28.dp))
                        }
                    } else {
                        NativeLogcatView(nativeLogs = nativeLogs, searchQuery = searchQuery)
                    }
                } else {
                    // App Log List View
                    AppLogListView(logs = logs)
                }
            }
        }
    }
}

@Composable
private fun TrailingStateInspectionView(
    allPositions: Map<String, SpotPosition>,
    activeTrailingSymbols: List<String>,
    searchQuery: String,
    logs: List<AppLogEntry>
) {
    val filtered = allPositions.filter { (sym, pos) ->
        searchQuery.isBlank() || sym.contains(searchQuery, ignoreCase = true) ||
                pos.lastTrailingOrderId?.contains(searchQuery, ignoreCase = true) == true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "🛡️ Ringkasan Trailing Stop Terdaftar",
                        color = TvTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Total Koin Tersimpan: ${allPositions.size} | Trailing Aktif: ${activeTrailingSymbols.size} (${if (activeTrailingSymbols.isEmpty()) "Tidak Ada" else activeTrailingSymbols.joinToString(", ")})",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        if (filtered.isNotEmpty()) {
            items(filtered.entries.toList(), key = { it.key }) { (symbol, pos) ->
                TrailingCoinCard(symbol = symbol, pos = pos)
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "📜 Riwayat Log Event Trailing (${logs.size})",
                    color = TvTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (logs.isNotEmpty()) {
                    Text(
                        text = "● Realtime Feed",
                        color = TvGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = TvSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Belum ada log pergerakan trailing stop. Log tercatat otomatis saat harga koin bergerak, peak naik, atau stop limit diperbarui.",
                            color = TvTextSecondary,
                            fontSize = 11.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(logs) { entry ->
                AppLogEntryRow(entry = entry)
            }
        }
    }
}

@Composable
private fun TrailingCoinCard(symbol: String, pos: SpotPosition) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = TvSurface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (pos.isTrailingEnabled) TvGreen.copy(alpha = 0.6f) else TvBorder
        )
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(symbol, color = TvTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Surface(
                        color = if (pos.isReal) TvGreen.copy(alpha = 0.15f) else TvAmber.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            if (pos.isReal) "TOKOCRYPTO REAL" else "SIMULASI",
                            color = if (pos.isReal) TvGreen else TvAmber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                Surface(
                    color = if (pos.isTrailingEnabled) TvGreen.copy(alpha = 0.2f) else TvSurfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        0.8.dp,
                        if (pos.isTrailingEnabled) TvGreen else TvBorder
                    )
                ) {
                    Text(
                        if (pos.isTrailingEnabled) "TRAILING AKTIF" else "TRAILING NONAKTIF",
                        color = if (pos.isTrailingEnabled) TvGreen else TvTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            val quoteAsset = PriceFormatter.extractQuote(symbol)

            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Harga Beli (Entry):", color = TvTextSecondary, fontSize = 11.sp)
                        Text(PriceFormatter.formatPrice(pos.entryPrice, showSymbol = true, quoteAsset = quoteAsset), color = TvTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Harga Tertinggi (Peak):", color = TvTextSecondary, fontSize = 11.sp)
                        Text(PriceFormatter.formatPrice(pos.peakPrice, showSymbol = true, quoteAsset = quoteAsset), color = TvGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Trailing Stop Limit Price:", color = TvTextSecondary, fontSize = 11.sp)
                        Text(PriceFormatter.formatPrice(pos.trailingStopPrice, showSymbol = true, quoteAsset = quoteAsset), color = TvAmber, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Persentase Trailing:", color = TvTextSecondary, fontSize = 11.sp)
                        Text("Base: ${pos.trailingPercent}% | Efektif: ${pos.activeTrailingPercent}% (Tiered: ${pos.isTieredTrailingEnabled})", color = TvTextPrimary, fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Jumlah Aset / Kuantitas:", color = TvTextSecondary, fontSize = 11.sp)
                        Text("${pos.quantity} (Modal: ${PriceFormatter.formatPrice(pos.investedAmount, showSymbol = true, quoteAsset = quoteAsset)})", color = TvTextPrimary, fontSize = 11.sp)
                    }
                    if (pos.isAutoSellEnabled) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Target Auto-Sell TP:", color = TvTextSecondary, fontSize = 11.sp)
                            Text("TP1: ${PriceFormatter.formatPrice(pos.tp1Price, showSymbol = true, quoteAsset = quoteAsset)} (${pos.tp1Percent}%) | TP2: ${PriceFormatter.formatPrice(pos.tp2Price, showSymbol = true, quoteAsset = quoteAsset)} (${pos.tp2Percent}%)", color = TvBlueSoft, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLogListView(logs: List<AppLogEntry>) {
    if (logs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Tidak ada baris log yang tercatat.", color = TvTextSecondary, fontSize = 12.sp)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logs) { entry ->
                AppLogEntryRow(entry = entry)
            }
        }
    }
}

@Composable
private fun AppLogEntryRow(entry: AppLogEntry) {
    val levelColor = when (entry.priority) {
        android.util.Log.VERBOSE -> TvTextSecondary
        android.util.Log.DEBUG -> TvBlueSoft
        android.util.Log.INFO -> TvGreen
        android.util.Log.WARN -> TvAmber
        android.util.Log.ERROR, android.util.Log.ASSERT -> TvRed
        else -> TvTextPrimary
    }

    SelectionContainer {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = TvSurface,
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, TvBorder)
        ) {
            Column(Modifier.padding(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            entry.formattedTime,
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Surface(
                            color = levelColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(3.dp)
                        ) {
                            Text(
                                entry.levelName,
                                color = levelColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        entry.tag,
                        color = TvTextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = entry.message,
                    color = TvTextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                if (entry.throwable != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = android.util.Log.getStackTraceString(entry.throwable),
                        color = TvRed,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeLogcatView(nativeLogs: List<String>, searchQuery: String) {
    val filtered = remember(nativeLogs, searchQuery) {
        if (searchQuery.isBlank()) nativeLogs else nativeLogs.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    if (filtered.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Tidak ada logcat Android sistem yang sesuai.", color = TvTextSecondary, fontSize = 12.sp)
        }
    } else {
        SelectionContainer {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TvBackground, RoundedCornerShape(6.dp))
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filtered) { line ->
                    val color = when {
                        line.contains(" E/") || line.contains(" E ") -> TvRed
                        line.contains(" W/") || line.contains(" W ") -> TvAmber
                        line.contains(" I/") || line.contains(" I ") -> TvGreen
                        line.contains(" D/") || line.contains(" D ") -> TvBlueSoft
                        else -> TvTextSecondary
                    }
                    Text(
                        text = line,
                        color = color,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 13.sp
                    )
                }
            }
        }
    }
}
