package agu.analys.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.AISignalState
import agu.analys.model.CandleBar
import agu.analys.model.ConfluenceCheckpoint
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.Timeframe
import agu.analys.model.TradingPair
import agu.analys.ui.components.OrderBookAndTradesPanel
import agu.analys.ui.components.dashboard.AssetBadge
import agu.analys.ui.components.dashboard.DashboardColors
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvBlue
import agu.analys.ui.theme.TvCyan
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import agu.analys.viewmodel.TradingViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Layar Detail Koin (detailcoin) dengan Mini Chart dan Rincian Kalkulasi Engine (2026-11-12).
 *
 * Pengguna membuka layar ini saat mengklik pair dari dashboard/watchlist sebelum memutuskan
 * membuka fullscreen chart. Menyediakan mini-chart candle, statistik 24 jam, dan kalkulasi
 * mendalam dari 3 engine (Scalping, Intraday, Swing) serta indikator teknikal komprehensif.
 */
@Composable
fun DetailCoinScreen(
    viewModel: TradingViewModel,
    onOpenLandscapeChart: () -> Unit,
    onOpenSimulation: (TradingPair) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val selectedPair by viewModel.selectedPair.collectAsState()
    val tick by viewModel.currentTick.collectAsState()
    val recentCandles by viewModel.recentCandles.collectAsState()
    val signalState by viewModel.aiSignalState.collectAsState()
    val indicators by viewModel.currentIndicators.collectAsState()
    val selectedTf by viewModel.selectedTimeframe.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val isFav = favorites.contains(selectedPair.symbol)

    val bids by viewModel.orderBookBids.collectAsState()
    val asks by viewModel.orderBookAsks.collectAsState()
    val tradeStream by viewModel.tradeStream.collectAsState()

    var engineTab by remember { mutableIntStateOf(0) } // 0: Scalping, 1: Intraday, 2: Swing, 3: Indikator

    val price = tick?.price?.takeIf { it > 0.0 } ?: recentCandles.lastOrNull()?.close ?: 0.0
    val change = tick?.change24h ?: 0.0
    val isUp = change >= 0.0
    val changeColor = if (isUp) TvGreen else TvRed

    Scaffold(
        containerColor = DashboardColors.Background,
        topBar = {
            DetailTopBar(
                pair = selectedPair,
                isFavorite = isFav,
                onToggleFavorite = { viewModel.toggleFavorite(selectedPair.symbol) },
                onOpenLandscapeChart = onOpenLandscapeChart,
                onBack = onBack
            )
        },
        bottomBar = {
            DetailBottomActionBar(
                onOpenLandscapeChart = onOpenLandscapeChart,
                onOpenSimulation = { onOpenSimulation(selectedPair) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp)
        ) {
            // 1. Live Price & 24h Stats Card
            item(key = "price_stats_card") {
                PriceStatsCard(
                    pair = selectedPair,
                    price = price,
                    change = change,
                    changeColor = changeColor,
                    high24h = tick?.high24h ?: 0.0,
                    low24h = tick?.low24h ?: 0.0,
                    vol24h = tick?.volume24h ?: 0.0
                )
            }

            // 2. Mini Chart Card
            item(key = "mini_chart_card") {
                MiniChartCard(
                    candles = recentCandles,
                    currentTimeframe = selectedTf,
                    onSelectTimeframe = { viewModel.selectTimeframe(it) },
                    onOpenLandscapeChart = onOpenLandscapeChart
                )
            }

            // 3. Engine Calculations & Strategy Breakdown
            item(key = "engine_calculations_card") {
                EngineCalculationsCard(
                    selectedTab = engineTab,
                    onSelectTab = { engineTab = it },
                    signal = signalState,
                    indicators = indicators,
                    currentPrice = price,
                    pair = selectedPair
                )
            }

            // 4. Order Book & Trades
            item(key = "orderbook_trades_card") {
                OrderBookAndTradesPanel(
                    bids = bids,
                    asks = asks,
                    tradeStream = tradeStream,
                    quoteAsset = selectedPair.quoteAsset,
                    modifier = Modifier.testTag("detail_order_book")
                )
            }
        }
    }
}

@Composable
private fun DetailTopBar(
    pair: TradingPair,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenLandscapeChart: () -> Unit,
    onBack: () -> Unit
) {
    Surface(
        color = DashboardColors.Background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("detail_back_button")) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali ke Dashboard",
                    tint = TvTextPrimary
                )
            }

            AssetBadge(pair.baseAsset, TvCyan)
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pair.baseAsset,
                        color = TvTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "/${pair.quoteAsset}",
                        color = TvCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
                Text(
                    text = pair.displayName.ifBlank { pair.symbol },
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
            }

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.testTag("detail_favorite_button")
            ) {
                Icon(
                    if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isFavorite) "Hapus Favorit" else "Tambah Favorit",
                    tint = if (isFavorite) TvAmber else TvTextSecondary
                )
            }

            IconButton(
                onClick = onOpenLandscapeChart,
                modifier = Modifier.testTag("detail_open_fullscreen_chart")
            ) {
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = "Buka Fullscreen Chart",
                    tint = TvCyan
                )
            }
        }
    }
}

@Composable
private fun PriceStatsCard(
    pair: TradingPair,
    price: Double,
    change: Double,
    changeColor: Color,
    high24h: Double,
    low24h: Double,
    vol24h: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("detail_price_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (price > 0.0) PriceFormatter.formatPriceFull(price, pair.quoteAsset) else "Memuat…",
                        color = TvTextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Harga Pasar Terkini · Tokocrypto",
                        color = TvTextSecondary,
                        fontSize = 10.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = changeColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, changeColor.copy(alpha = 0.4f))
                ) {
                    val changeStr = if (change >= 0.0) {
                        String.format(Locale.US, "+%.2f%%", change)
                    } else {
                        String.format(Locale.US, "%.2f%%", change)
                    }
                    Text(
                        text = changeStr,
                        color = changeColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DashboardColors.Surface, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatColumn("Tinggi 24j", if (high24h > 0.0) PriceFormatter.formatPriceFull(high24h, pair.quoteAsset) else "—")
                StatColumn("Rendah 24j", if (low24h > 0.0) PriceFormatter.formatPriceFull(low24h, pair.quoteAsset) else "—")
                StatColumn("Volume 24j", formatAbbrev(vol24h))
            }
        }
    }
}

@Composable
private fun MiniChartCard(
    candles: List<CandleBar>,
    currentTimeframe: Timeframe,
    onSelectTimeframe: (Timeframe) -> Unit,
    onOpenLandscapeChart: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("detail_mini_chart_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Timeframe Selector & Fullscreen trigger
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(Timeframe.entries) { tf ->
                        val isSelected = tf == currentTimeframe
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) TvCyan else DashboardColors.Surface)
                                .clickable { onSelectTimeframe(tf) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = tf.label,
                                color = if (isSelected) Color.Black else TvTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(TvBlue.copy(alpha = 0.15f))
                        .clickable { onOpenLandscapeChart() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Fullscreen, null, tint = TvCyan, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Penuh", color = TvCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Interactive Mini Candlestick Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(DashboardColors.Surface, RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                if (candles.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Memuat candle...", color = TvTextSecondary, fontSize = 12.sp)
                    }
                } else {
                    MiniCandleCanvas(
                        candles = candles.takeLast(45),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniCandleCanvas(
    candles: List<CandleBar>,
    modifier: Modifier = Modifier
) {
    val greenColor = TvGreen
    val redColor = TvRed
    val cyanColor = TvCyan

    Canvas(modifier = modifier) {
        if (candles.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height

        val high = candles.maxOfOrNull { it.high } ?: 1.0
        val low = candles.minOfOrNull { it.low } ?: 0.0
        val range = (high - low).takeIf { it > 0.0 } ?: 1.0

        val n = candles.size
        val barWidth = (w / n) * 0.7f
        val gap = w / n

        // Draw candles
        candles.forEachIndexed { i, bar ->
            val isGreen = bar.close >= bar.open
            val color = if (isGreen) greenColor else redColor

            val x = i * gap + (gap - barWidth) / 2f + barWidth / 2f
            val yHigh = h - ((bar.high - low) / range * (h * 0.88f) + h * 0.06f).toFloat()
            val yLow = h - ((bar.low - low) / range * (h * 0.88f) + h * 0.06f).toFloat()

            val yOpen = h - ((bar.open - low) / range * (h * 0.88f) + h * 0.06f).toFloat()
            val yClose = h - ((bar.close - low) / range * (h * 0.88f) + h * 0.06f).toFloat()

            // Wick
            drawLine(
                color = color,
                start = Offset(x, yHigh),
                end = Offset(x, yLow),
                strokeWidth = 1.5f
            )

            // Body
            val topBody = min(yOpen, yClose)
            val bodyH = max(abs(yOpen - yClose), 2f)

            drawRect(
                color = color,
                topLeft = Offset(x - barWidth / 2f, topBody),
                size = Size(barWidth, bodyH)
            )
        }

        // Draw watermark latest price dotted line
        candles.lastOrNull()?.let { last ->
            val lastY = h - ((last.close - low) / range * (h * 0.88f) + h * 0.06f).toFloat()
            drawLine(
                color = cyanColor.copy(alpha = 0.5f),
                start = Offset(0f, lastY),
                end = Offset(w, lastY),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
            )
        }
    }
}

@Composable
private fun EngineCalculationsCard(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    signal: AISignalState,
    indicators: TechnicalIndicators,
    currentPrice: Double,
    pair: TradingPair
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("detail_engine_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(
                text = "Kalkulasi Multi-Engine",
                color = TvTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Evaluasi strategi real-time Tokocrypto yang dibaca agent OMP",
                color = TvTextSecondary,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(12.dp))

            // Strategy Tab Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DashboardColors.Surface, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf("⚡ Scalp", "⏱️ Intraday", "📈 Swing", "📊 Indikator")
                tabs.forEachIndexed { idx, label ->
                    val isSel = selectedTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) DashboardColors.Card else Color.Transparent)
                            .clickable { onSelectTab(idx) }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSel) TvCyan else TvTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            when (selectedTab) {
                0 -> ScalpEngineView(signal, currentPrice, pair)
                1 -> IntradayEngineView(signal, currentPrice, pair)
                2 -> SwingEngineView(signal, indicators, currentPrice, pair)
                3 -> IndicatorsDetailView(indicators)
            }
        }
    }
}

@Composable
private fun ScalpEngineView(
    signal: AISignalState,
    currentPrice: Double,
    pair: TradingPair
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Scalp Status Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Tahapan Scalping", color = TvTextSecondary, fontSize = 11.sp)
                Text(
                    text = signal.scalpingStage.displayName,
                    color = when (signal.action) {
                        SignalAction.BUY -> TvGreen
                        SignalAction.SELL -> TvRed
                        SignalAction.HOLD -> TvAmber
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text("Skor Scalping", color = TvTextSecondary, fontSize = 11.sp)
                Text(
                    text = "${signal.scalpingScore}/100 · ${signal.scalpingScoreCategory.ifBlank { "STANDAR" }}",
                    color = TvCyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        LinearProgressIndicator(
            progress = { (signal.scalpingScore / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (signal.scalpingScore >= 70) TvGreen else TvCyan,
            trackColor = DashboardColors.Surface
        )

        // Setup & Regime
        if (signal.scalpingSetup.isNotBlank() || signal.scalpingRegime.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DashboardColors.Surface, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Setup: ${signal.scalpingSetup.ifBlank { "Standar" }}",
                    color = TvTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Regim: ${signal.scalpingRegime.ifBlank { "Netral" }}",
                    color = TvAmber,
                    fontSize = 11.sp
                )
            }
        }

        // Targets & Stop Loss
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricBox("TP1 Target", if (signal.targetPrice1 > 0.0) PriceFormatter.formatPriceFull(signal.targetPrice1, pair.quoteAsset) else "—", TvGreen, Modifier.weight(1f))
            MetricBox("TP2 Target", if (signal.targetPrice2 > 0.0) PriceFormatter.formatPriceFull(signal.targetPrice2, pair.quoteAsset) else "—", TvGreen, Modifier.weight(1f))
            MetricBox("Stop Loss", if (signal.stopLoss > 0.0) PriceFormatter.formatPriceFull(signal.stopLoss, pair.quoteAsset) else "—", TvRed, Modifier.weight(1f))
        }

        // 6 MTF Checkpoints
        Text(
            text = "6 Checkpoint Konfluensi MTF:",
            color = TvTextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )

        val checkpoints = signal.mtf.resolvedCheckpoints()
        checkpoints.forEach { cp ->
            CheckpointRow(cp)
        }
    }
}

@Composable
private fun IntradayEngineView(
    signal: AISignalState,
    currentPrice: Double,
    pair: TradingPair
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Aksi Intraday", color = TvTextSecondary, fontSize = 11.sp)
                Text(
                    text = signal.action.name,
                    color = if (signal.action == SignalAction.BUY) TvGreen else TvAmber,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Keyakinan (Confidence)", color = TvTextSecondary, fontSize = 11.sp)
                Text(
                    text = "${signal.confidence}%",
                    color = TvCyan,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricBox("Target H4", if (signal.targetPrice1 > 0.0) PriceFormatter.formatPriceFull(signal.targetPrice1, pair.quoteAsset) else "—", TvGreen, Modifier.weight(1f))
            MetricBox("Cut Loss", if (signal.stopLoss > 0.0) PriceFormatter.formatPriceFull(signal.stopLoss, pair.quoteAsset) else "—", TvRed, Modifier.weight(1f))
            MetricBox("Risk/Reward", signal.riskRewardRatio, TvCyan, Modifier.weight(1f))
        }

        if (signal.reasoning.isNotEmpty()) {
            Text("Dasar Analisis Intraday:", color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            signal.reasoning.take(4).forEach { reason ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("•", color = TvCyan, modifier = Modifier.padding(end = 6.dp))
                    Text(reason, color = TvTextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SwingEngineView(
    signal: AISignalState,
    indicators: TechnicalIndicators,
    currentPrice: Double,
    pair: TradingPair
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Bias Swing Tren", color = TvTextSecondary, fontSize = 11.sp)
                Text(
                    text = signal.sentiment.displayName,
                    color = TvTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Sinyal Swing", color = TvTextSecondary, fontSize = 11.sp)
                Text(
                    text = signal.action.name,
                    color = if (signal.action == SignalAction.BUY) TvGreen else TvAmber,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val support = indicators.bbLower.takeIf { !it.isNaN() } ?: 0.0
            val resistance = indicators.bbUpper.takeIf { !it.isNaN() } ?: 0.0
            MetricBox("Support D1", if (support > 0.0) PriceFormatter.formatPriceFull(support, pair.quoteAsset) else "—", TvCyan, Modifier.weight(1f))
            MetricBox("Resistensi D1", if (resistance > 0.0) PriceFormatter.formatPriceFull(resistance, pair.quoteAsset) else "—", TvAmber, Modifier.weight(1f))
            MetricBox("Target Swing", if (signal.targetPrice1 > 0.0) PriceFormatter.formatPriceFull(signal.targetPrice1, pair.quoteAsset) else "—", TvGreen, Modifier.weight(1f))
        }

        if (signal.reasoning.isNotEmpty()) {
            Text("Rasional Swing:", color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            signal.reasoning.take(4).forEach { reason ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("•", color = TvCyan, modifier = Modifier.padding(end = 6.dp))
                    Text(reason, color = TvTextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun IndicatorsDetailView(indicators: TechnicalIndicators) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val rsiText = if (!indicators.rsi14.isNaN()) String.format(Locale.US, "%.2f", indicators.rsi14) else "—"
        IndicatorRow("RSI (14)", rsiText, when {
            indicators.rsi14.isNaN() -> TvTextSecondary
            indicators.rsi14 < 30.0 -> TvGreen
            indicators.rsi14 > 70.0 -> TvRed
            else -> TvTextPrimary
        })
        IndicatorRow("MACD Line", if (!indicators.macd.isNaN()) String.format(Locale.US, "%.4f", indicators.macd) else "—", TvCyan)
        IndicatorRow("MACD Signal", if (!indicators.macdSignal.isNaN()) String.format(Locale.US, "%.4f", indicators.macdSignal) else "—", TvAmber)
        IndicatorRow("MACD Hist", if (!indicators.macdHist.isNaN()) String.format(Locale.US, "%.4f", indicators.macdHist) else "—", if (indicators.macdHist >= 0) TvGreen else TvRed)
        IndicatorRow("EMA 20", if (!indicators.ema20.isNaN()) String.format(Locale.US, "%.4f", indicators.ema20) else "—", TvTextPrimary)
        IndicatorRow("EMA 50", if (!indicators.ema50.isNaN()) String.format(Locale.US, "%.4f", indicators.ema50) else "—", TvTextPrimary)
        IndicatorRow("EMA 200", if (!indicators.ema200.isNaN()) String.format(Locale.US, "%.4f", indicators.ema200) else "—", TvTextPrimary)
        IndicatorRow("ATR", if (!indicators.atr.isNaN()) String.format(Locale.US, "%.4f", indicators.atr) else "—", TvCyan)
        IndicatorRow("RVOL (Relative Vol)", if (!indicators.rvol.isNaN()) String.format(Locale.US, "%.2f×", indicators.rvol) else "—", if (indicators.rvol >= 1.5) TvGreen else TvTextPrimary)
        IndicatorRow("VWAP", if (!indicators.vwap.isNaN() && indicators.vwap > 0.0) String.format(Locale.US, "%.4f", indicators.vwap) else "—", TvAmber)
    }
}

@Composable
private fun IndicatorRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashboardColors.Surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TvTextSecondary, fontSize = 12.sp)
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CheckpointRow(cp: ConfluenceCheckpoint) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashboardColors.Surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (cp.isOk) Icons.Default.CheckCircle else Icons.Default.Close,
            contentDescription = null,
            tint = if (cp.isOk) TvGreen else TvTextSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = cp.label,
            color = TvTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = cp.metricValue.ifBlank { if (cp.isOk) "OK" else "Tunggu" },
            color = if (cp.isOk) TvGreen else TvTextSecondary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun MetricBox(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(DashboardColors.Surface, RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = TvTextSecondary, fontSize = 10.sp)
            Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column {
        Text(label, color = TvTextSecondary, fontSize = 10.sp)
        Text(value, color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DetailBottomActionBar(
    onOpenLandscapeChart: () -> Unit,
    onOpenSimulation: () -> Unit
) {
    Surface(
        color = DashboardColors.Card,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onOpenLandscapeChart,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .testTag("detail_btn_fullscreen_chart"),
                colors = ButtonDefaults.buttonColors(containerColor = TvCyan),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Fullscreen, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Buka Chart Penuh", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            }

            OutlinedButton(
                onClick = onOpenSimulation,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .testTag("detail_btn_simulation"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, TvBlue.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = TvBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Trade Simulasi", color = TvBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatAbbrev(v: Double): String {
    val a = abs(v)
    return when {
        a >= 1_000_000_000.0 -> String.format(Locale.US, "%.2fM", a / 1_000_000_000.0)
        a >= 1_000_000.0 -> String.format(Locale.US, "%.2fJt", a / 1_000_000.0)
        a >= 1_000.0 -> String.format(Locale.US, "%.1fRb", a / 1_000.0)
        a > 0.0 -> String.format(Locale.US, "%.0f", a)
        else -> "—"
    }
}
