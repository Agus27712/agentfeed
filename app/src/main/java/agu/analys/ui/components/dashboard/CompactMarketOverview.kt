package agu.analys.ui.components.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.engine.global.GlobalMarketContext
import agu.analys.engine.global.GlobalRegime
import agu.analys.model.MarketTick
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MarketRankingTab(val label: String, val badge: String) {
    WATCHLIST("📋 Pantauan", "📋 PANTAUAN"),
    FAVORITE("⭐ Favorit", "⭐ FAVORIT"),
    TOP_GAINERS("🚀 Top Gainer", "🚀 TOP GAINER"),
    TOP_LOSERS("🔻 Top Loser", "🔻 TOP LOSER"),
    TOP_VOLUME("🔥 24H Volume", "🔥 TOP VOLUME")
}

/**
 * Top Stat Header & Exchange Source & Mode & Redesigned Tabs:
 * - Title: Watchlist Tokocrypto
 * - Synchronized Refresh Button with smooth rotation during loading
 * - 24H VOL | AVG VOL | STRATEGI MODE (SCALPING / 2ND-WAVE / SWING)
 * - Realtime status indicator
 * - Tab bar: [📋 Watchlist | ⭐ Favorit]
 */
@Composable
fun DashboardMockupHeader(
    allTicks: Map<String, MarketTick>,
    marketDataSource: MarketDataSource = MarketDataSource.TOKOCRYPTO,
    strategyMode: StrategyMode = StrategyMode.SCALPING,
    globalContext: GlobalMarketContext = GlobalMarketContext(),
    isConnected: Boolean,
    isRefreshing: Boolean = false,
    selectedTab: MarketRankingTab = MarketRankingTab.WATCHLIST,
    onSelectTab: (MarketRankingTab) -> Unit,
    onRefresh: () -> Unit,
    onMenuClick: () -> Unit = {},
    onAddAsset: () -> Unit = {},
    onEditStrategy: () -> Unit = {},
    onOpenLogcat: () -> Unit = {}
) {
    val totalVolume = allTicks.values.sumOf { it.volume24h }
    val avgVolume = if (allTicks.isNotEmpty()) totalVolume / allTicks.size else 0.0
    val quoteAsset = marketDataSource.defaultQuoteAsset

    // Waktu realtime server/aplikasi berjalan terus saat terhubung (LIVE).
    // Jika koneksi terputus/lama tidak tersambung, waktu berhenti dan mencatat jam berapa terputusnya.
    var currentTime by remember {
        mutableStateOf(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
    }
    var lastDisconnectTime by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isConnected) {
        if (!isConnected && lastDisconnectTime == null) {
            lastDisconnectTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        } else if (isConnected) {
            lastDisconnectTime = null
        }
    }

    LaunchedEffect(isConnected) {
        if (isConnected) {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            while (true) {
                currentTime = sdf.format(Date())
                kotlinx.coroutines.delay(500L)
            }
        }
    }

    // Rotasi animasi tombol refresh yang tersinkronisasi dengan isRefreshing state
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_infinite")
    val spinningRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refresh_spin"
    )
    val manualRotation = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val currentRotationAngle = if (isRefreshing) spinningRotation else manualRotation.value

    val ledColor = if (isConnected) TvGreen else TvRed
    val displayTime = if (isConnected) currentTime else (lastDisconnectTime ?: currentTime)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Top Nav: Judul Bersih "Watchlist Tokocrypto" + Status Live Terintegrasi + Refresh
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Watchlist ${marketDataSource.label} ${marketDataSource.defaultQuoteAsset}",
                    color = TvTextPrimary,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.3).sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Sleek Live Status Pill dengan LED & Jam
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(26.dp)
                        .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
                        .border(0.8.dp, if (isConnected) TvBorder else TvRed.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(ledColor.copy(alpha = 0.28f), CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(5.5.dp)
                                .background(ledColor, CircleShape)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isConnected) "LIVE $displayTime" else "OFFLINE $displayTime",
                        color = if (isConnected) TvGreen else TvRed,
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (selectedTab == MarketRankingTab.FAVORITE) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(TvSurfaceVariant)
                            .border(0.8.dp, TvBorder, RoundedCornerShape(6.dp))
                            .clickable(onClick = onAddAsset),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Tambah ke Favorit",
                            tint = TvAmber,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(TvSurfaceVariant)
                        .border(
                            0.8.dp,
                            if (isRefreshing) TvBlue.copy(alpha = 0.6f) else TvBorder,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable(
                            enabled = !isRefreshing,
                            onClick = {
                                coroutineScope.launch {
                                    manualRotation.snapTo(0f)
                                    manualRotation.animateTo(
                                        targetValue = 360f,
                                        animationSpec = tween(durationMillis = 600, easing = LinearEasing)
                                    )
                                }
                                onRefresh()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Data Pasar",
                        tint = if (isRefreshing) TvBlueSoft else TvTextPrimary,
                        modifier = Modifier
                            .size(15.dp)
                            .rotate(currentRotationAngle)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(TvSurfaceVariant)
                        .border(0.8.dp, TvBorder, RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenLogcat),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Buka Logcat & Diagnostik Lengkap",
                        tint = TvGreen,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Compact Unified Stat Strip: 24H VOL | AVG VOL | STRATEGI
        val (modeBg, modeBorder, modeColor, modeLabel) = when (strategyMode) {
            StrategyMode.SCALPING -> listOf(Color(0xFF123D2A), Color(0xFF1B5E38), TvGreen, "SCALPING")
            StrategyMode.SWING -> listOf(Color(0xFF122840), Color(0xFF1E3A5F), Color(0xFF72B7FF), "SWING")
            StrategyMode.OFFICE_DAILY -> listOf(Color(0xFF1F2448), Color(0xFF3730A3), Color(0xFFA5B4FC), "INTRADAY")
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TvSurface, RoundedCornerShape(8.dp))
                .border(0.8.dp, TvBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Stat 1: 24H VOL
            Column(
                modifier = Modifier.weight(1.0f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "24H VOL",
                    color = TvTextSecondary,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = PriceFormatter.formatVolume(totalVolume, quoteAsset = quoteAsset),
                    color = TvTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(TvBorder)
            )

            // Stat 2: GLOBAL SHIELD (Shield di kiri, 3 baris keterangan di kanan)
            val btcTick = remember(allTicks) {
                allTicks.entries.firstOrNull { entry ->
                    val k = entry.key.lowercase().replace("_", "")
                    k == "btcidr" || k == "btc"
                }?.value
            }
            val usdtTick = remember(allTicks) {
                allTicks.entries.firstOrNull { entry ->
                    val k = entry.key.lowercase().replace("_", "")
                    k == "usdtidr" || k == "usdt"
                }?.value
            }
            val btcPriceIdr = remember(btcTick) {
                btcTick?.price ?: 0.0
            }
            val btcChangePct = remember(btcTick) {
                btcTick?.change24h ?: 0.0
            }
            val btcFormattedIdr = if (btcPriceIdr > 0) {
                "BTC ${PriceFormatter.formatPrice(btcPriceIdr, showSymbol = true, quoteAsset = "IDR")}"
            } else {
                "BTC --"
            }

            LaunchedEffect(btcTick, usdtTick) {
                if (btcTick != null && btcTick.price > 0) {
                    agu.analys.engine.global.GlobalContextManager.updateFallbackFromTicker(
                        priceIdr = btcTick.price,
                        changePct = btcTick.change24h,
                        // Fallback berantai: tick USDT/IDR live → rate ter-cache di
                        // ExchangeRateManager → 0.0 (pembaruan dibatalkan, tanpa hardcode).
                        usdtRate = usdtTick?.price
                            ?: agu.analys.util.ExchangeRateManager.currentRate()
                    )
                }
            }

            val shieldIcon: ImageVector
            val shieldColor: Color
            val shieldText: String
            when {
                !globalContext.isConnected -> {
                    shieldIcon = Icons.Default.Info
                    shieldColor = TvTextSecondary
                    shieldText = "Menghubungkan..."
                }
                globalContext.isVetoActive -> {
                    shieldIcon = Icons.Default.Warning
                    shieldColor = TvRed
                    shieldText = "VETO AKTIF"
                }
                globalContext.regime == GlobalRegime.BULLISH -> {
                    shieldIcon = Icons.Default.Security
                    shieldColor = TvGreen
                    shieldText = "SHIELD AMAN"
                }
                globalContext.regime == GlobalRegime.BEARISH -> {
                    shieldIcon = Icons.Default.Security
                    shieldColor = TvAmber
                    shieldText = "STANDBY (BEARISH)"
                }
                else -> {
                    shieldIcon = Icons.Default.Security
                    shieldColor = TvBlue
                    shieldText = "STANDBY (SIDEWAYS)"
                }
            }

            Row(
                modifier = Modifier
                    .weight(1.9f)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Shield di sebelah kiri (diperbesar agar matching dengan 3 baris keterangan)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(shieldColor.copy(alpha = 0.15f), CircleShape)
                        .border(0.8.dp, shieldColor.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = shieldIcon,
                        contentDescription = "Market Shield",
                        tint = shieldColor,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                // Keterangan 3 baris di sebelah kanan shield (jarak baris rapat dan proporsional)
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    // Baris 1: Keterangan status (tukar posisi dengan nilai BTC)
                    Text(
                        text = shieldText,
                        color = shieldColor,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 11.sp,
                        maxLines = 1
                    )
                    // Baris 2: Sumber data & Persentase BTC di tengah sejajar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "• Tokocrypto",
                            color = TvTextSecondary,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 10.sp,
                            maxLines = 1
                        )
                        val changePrefix = if (btcChangePct > 0) "+" else ""
                        Text(
                            text = "($changePrefix${String.format(Locale.US, "%.2f", btcChangePct)}%)",
                            color = if (btcChangePct >= 0) TvGreen else TvRed,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 10.sp,
                            maxLines = 1
                        )
                    }
                    // Baris 3: Nilai BTC konversi ke IDR dengan pemisah ribuan
                    Text(
                        text = btcFormattedIdr,
                        color = TvTextPrimary,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 11.sp,
                        maxLines = 1
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(TvBorder)
            )

            // Stat 3: TOMBOL EDIT STRATEGI (NAMA STRATEGI)
            Column(
                modifier = Modifier
                    .weight(1.0f)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onEditStrategy() }
                    .padding(vertical = 1.dp),
                horizontalAlignment = Alignment.End
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "STRATEGI",
                        color = TvTextSecondary,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Strategi",
                        tint = TvTextSecondary.copy(alpha = 0.7f),
                        modifier = Modifier.size(9.dp)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .background(modeBg as Color, RoundedCornerShape(4.dp))
                        .border(0.7.dp, modeBorder as Color, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = modeLabel as String,
                        color = modeColor as Color,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // TAB BAR (Modern Sleek Segmented Control with Horizontal Scroll for 5 Tabs)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(TvSurfaceVariant)
                .border(0.8.dp, TvBorder, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MarketRankingTab.values().forEach { tab ->
                val isSelected = selectedTab == tab
                val (tabActiveBg, tabActiveBorder, tabActiveTextColor) = when (tab) {
                    MarketRankingTab.WATCHLIST -> Triple(TvBlue.copy(alpha = 0.22f), TvBlue.copy(alpha = 0.85f), TvBlueSoft)
                    MarketRankingTab.FAVORITE -> Triple(TvAmber.copy(alpha = 0.22f), TvAmber.copy(alpha = 0.85f), TvAmber)
                    MarketRankingTab.TOP_GAINERS -> Triple(TvGreen.copy(alpha = 0.22f), TvGreen.copy(alpha = 0.85f), TvGreen)
                    MarketRankingTab.TOP_LOSERS -> Triple(TvRed.copy(alpha = 0.22f), TvRed.copy(alpha = 0.85f), TvRed)
                    MarketRankingTab.TOP_VOLUME -> Triple(Color(0xFF8B5CF6).copy(alpha = 0.22f), Color(0xFF8B5CF6).copy(alpha = 0.85f), Color(0xFFA78BFA))
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) tabActiveBg else Color.Transparent)
                        .then(
                            if (isSelected) Modifier.border(0.8.dp, tabActiveBorder, RoundedCornerShape(6.dp))
                            else Modifier
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 6.dp, horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        color = if (isSelected) tabActiveTextColor else TvTextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}