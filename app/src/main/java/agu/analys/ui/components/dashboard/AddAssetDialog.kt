package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import agu.analys.data.TokocryptoSymbolRepository
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvBlue
import agu.analys.ui.theme.TvCyan
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

/**
 * Dialog cari/tambah pasar. Sejak 2026-11-12 setiap hasil pencarian **langsung
 * menampilkan mini-chart** dan klik baris membuka pratinjau mini-chart sebelum
 * chart penuh. `onAddPair` tetap disediakan sebagai jalur cepat "tambah favorit".
 */
@Composable
fun AddAssetDialog(
    currentFavorites: Set<String> = emptySet(),
    marketDataSource: agu.analys.config.MarketDataSource = agu.analys.config.MarketDataSource.TOKOCRYPTO,
    ticks: Map<String, MarketTick> = emptyMap(),
    candlesProvider: (String) -> List<CandleBar> = { emptyList() },
    onDismiss: () -> Unit,
    onAddPair: (TradingPair) -> Unit,
    onPreviewPair: ((TradingPair) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember(marketDataSource) { mutableStateOf("USDT") }
    // Tokocrypto menyediakan pair USDT maupun IDR, jadi kedua tab tersedia.
    val tabs = listOf("USDT", "IDR", "SEMUA")

    // Ambil daftar pair dari dynamic symbol repository Tokocrypto
    val availablePairs = remember(searchQuery, selectedTab, marketDataSource) {
        val quoteFilter = if (selectedTab == "SEMUA") null else selectedTab
        val results = if (searchQuery.isNotBlank()) {
            TokocryptoSymbolRepository.searchSymbols(searchQuery, quoteFilter)
        } else {
            TokocryptoSymbolRepository.getAllTradingPairs(quoteFilter)
        }
        if (results.isNotEmpty()) results else TradingPair.popularPairsForSource(marketDataSource)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DashboardColors.Surface),
            border = BorderStroke(1.dp, DashboardColors.Border)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = TvCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "Tambah Pasar ${marketDataSource.label}",
                                color = TvTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                "Ditemukan ${availablePairs.size} pair aktif",
                                color = TvTextSecondary,
                                fontSize = 10.5.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = TvTextSecondary)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Search input
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Cari koin (cth: BTC, SOL, PEPE) — klik untuk mini-chart", color = TvTextSecondary, fontSize = 11.5.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = TvCyan, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TvTextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_asset_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvCyan,
                        unfocusedBorderColor = DashboardColors.Border,
                        focusedTextColor = TvTextPrimary,
                        unfocusedTextColor = TvTextPrimary,
                        cursorColor = TvCyan
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(10.dp))

                // Quote Tabs (IDR / USDT / SEMUA)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(tabs) { tab ->
                        val isSelected = selectedTab == tab
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) TvCyan else DashboardColors.Card,
                            border = BorderStroke(1.dp, if (isSelected) TvCyan else DashboardColors.Border),
                            modifier = Modifier.clickable { selectedTab = tab }
                        ) {
                            Text(
                                text = tab,
                                color = if (isSelected) Color.Black else TvTextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // List of pairs
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availablePairs, key = { it.symbol }) { pair ->
                        val isAdded = currentFavorites.contains(pair.symbol)
                        // Tick dicoba beberapa ejaan simbol karena peta dashboard
                        // menyimpan kunci BTCIDR, BTC_IDR, maupun BTCUSDT.
                        val tick = ticks[pair.symbol]
                            ?: ticks[pair.symbol.replace("_", "")]
                            ?: ticks[pair.symbol.replace("_", "").lowercase()]
                            ?: ticks[pair.baseAsset.uppercase()]
                        val change = tick?.change24h ?: 0.0
                        val garis = if (change >= 0.0) TvGreen else TvRed
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Ketik koin -> MASUK DULU KE MINI-CHART.
                                    if (onPreviewPair != null) onPreviewPair(pair) else onAddPair(pair)
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
                            border = BorderStroke(1.dp, if (isAdded) TvCyan.copy(alpha = 0.5f) else DashboardColors.Border)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssetBadge(pair.baseAsset, if (isAdded) TvCyan else TvGreen)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            pair.baseAsset,
                                            color = TvTextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "/${pair.quoteAsset}",
                                            color = TvCyan,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(pair.displayName, color = TvTextSecondary, fontSize = 10.sp)
                                }
                                // Mini-chart pratinjau langsung pada hasil pencarian.
                                Box(
                                    modifier = Modifier
                                        .width(68.dp)
                                        .height(30.dp)
                                        .padding(end = 8.dp)
                                ) {
                                    MiniSparkline(
                                        tick = tick,
                                        candles = candlesProvider(pair.symbol),
                                        lineColor = garis
                                    )
                                }
                                if (isAdded) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = null,
                                            tint = TvAmber,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            "Favorit",
                                            color = TvAmber,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    Button(
                                        onClick = { onAddPair(pair) },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvCyan),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(
                                            "+ Tambah",
                                            color = Color.Black,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
