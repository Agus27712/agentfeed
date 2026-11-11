package agu.analys.ui.components.dashboard

import agu.analys.config.AiProvider
import agu.analys.model.NewsScreenerResult
import agu.analys.model.NewsScreenerUiState
import agu.analys.model.ScreenerCoinPick
import agu.analys.model.TradingPair
import agu.analys.util.PriceFormatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun NewsAiScreenerModal(
    state: NewsScreenerUiState,
    provider: AiProvider,
    liveTicks: Map<String, agu.analys.model.MarketTick> = emptyMap(),
    onDismiss: () -> Unit,
    onRunScreener: (Boolean) -> Unit,
    onSelectCoin: (TradingPair) -> Unit
) {
    var showRawAnalysis by remember { mutableStateOf(false) }

    // Otomatis picu analisis pertama kali jika state masih Idle atau provider berubah
    LaunchedEffect(Unit) {
        if (state is NewsScreenerUiState.Idle) {
            onRunScreener(false)
        }
    }

    val providerName = "Gemini Flash"
    val providerColor = Color(0xFF2196F3)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = providerColor,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "AI News Screener",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Powered by $providerName",
                                style = MaterialTheme.typography.bodySmall,
                                color = providerColor
                            )
                        }
                    }
                    IconButton(onClick = { onRunScreener(true) }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (state) {
                        is NewsScreenerUiState.Idle -> {
                            // Initializing...
                        }
                        is NewsScreenerUiState.Loading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = providerColor)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = state.stage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                        is NewsScreenerUiState.Error -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(onClick = { onRunScreener(true) }) {
                                    Text("Coba Lagi")
                                }
                            }
                        }
                        is NewsScreenerUiState.Success -> {
                            ScreenerResultsContent(
                                result = state.result,
                                providerColor = providerColor,
                                liveTicks = liveTicks,
                                showRawAnalysis = showRawAnalysis,
                                onToggleRaw = { showRawAnalysis = !showRawAnalysis },
                                onSelectCoin = { pick ->
                                    val tradingPair = TradingPair(
                                        baseAsset = pick.baseSymbol,
                                        quoteAsset = "IDR",
                                        symbol = pick.pairSymbol,
                                        displayName = "${pick.baseSymbol}/IDR"
                                    )
                                    onSelectCoin(tradingPair)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }

                // Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Tutup")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenerResultsContent(
    result: NewsScreenerResult,
    providerColor: Color,
    liveTicks: Map<String, agu.analys.model.MarketTick> = emptyMap(),
    showRawAnalysis: Boolean,
    onToggleRaw: () -> Unit,
    onSelectCoin: (ScreenerCoinPick) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Info / Warning Banner jika terjadi fallback atau rate limit
        if (result.rawAnalysis.contains("⚠️") || result.rawAnalysis.contains("429") || result.providerUsed.contains("Heuristik") || result.providerUsed.contains("Failover")) {
            item {
                Surface(
                    color = Color(0xFFFF9800).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        val bannerText = when {
                            result.rawAnalysis.contains("429") -> "Kuota AI sedang padat / Rate Limit (HTTP 429). Menampilkan hasil kurasi heuristik berita Tokocrypto."
                            result.rawAnalysis.contains("API Key", ignoreCase = true) -> "API Key Gemini belum diisi di Pengaturan. Menampilkan rekomendasi berita heuristik."
                            else -> "Mode Heuristik aktif untuk menyaring kandidat koin berita Tokocrypto."
                        }
                        Text(
                            text = bannerText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Kandidat Bullish Terkuat:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "⚡ Fresh (< 24 Jam)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (result.picks.isEmpty()) {
            item {
                Text(
                    text = "Tidak ada koin yang lolos kriteria screening saat ini.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(result.picks) { pick ->
                ScreenerCoinCard(
                    pick = pick,
                    providerColor = providerColor,
                    liveTicks = liveTicks,
                    onSelect = { onSelectCoin(pick) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleRaw() }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Lihat Analisis Mentah (Raw Output)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (showRawAnalysis) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (showRawAnalysis) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = result.rawAnalysis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenerCoinCard(
    pick: ScreenerCoinPick,
    providerColor: Color,
    liveTicks: Map<String, agu.analys.model.MarketTick> = emptyMap(),
    onSelect: () -> Unit
) {
    val liveTick = liveTicks[pick.pairSymbol]
        ?: liveTicks[pick.altPair]
        ?: liveTicks[pick.baseSymbol]
        ?: liveTicks["${pick.baseSymbol}IDR"]
        ?: liveTicks["${pick.baseSymbol.lowercase()}_idr"]

    val effectivePrice = if (liveTick != null && liveTick.price > 0.0) liveTick.price else pick.currentPrice
    val effectiveChange = if (liveTick != null && !liveTick.change24h.isNaN()) liveTick.change24h else pick.change24h

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pick.baseSymbol,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "/IDR",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, top = 4.dp)
                    )
                }
                
                val priceChangeColor = if (effectiveChange >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = PriceFormatter.formatPrice(effectivePrice),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = PriceFormatter.formatPercentage(effectiveChange),
                        style = MaterialTheme.typography.bodySmall,
                        color = priceChangeColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = pick.sectorNarrative,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Surface(
                    color = providerColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = pick.sentimentGrade,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = providerColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = pick.mainCatalyst,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(8.dp))

            pick.reasons.take(2).forEach { reason ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Lihat Chart",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp).padding(start = 4.dp)
                )
            }
        }
    }
}
