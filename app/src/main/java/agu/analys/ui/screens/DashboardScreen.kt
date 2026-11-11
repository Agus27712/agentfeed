package agu.analys.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.MarketConnectionState
import agu.analys.model.SignalAction
import agu.analys.model.TradingPair
import agu.analys.engine.intraday.IntradayScreener
import agu.analys.service.TokocryptoMarketService
import agu.analys.ui.components.dashboard.*
import agu.analys.ui.components.settings.LogcatDiagnosticDialog
import agu.analys.ui.theme.*
import agu.analys.viewmodel.*

@Composable
fun DashboardScreen(
    viewModel: TradingViewModel,
    onOpenChart: (TradingPair) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenLandscapeChart: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val marketDataSource by viewModel.marketDataSource.collectAsState()
    val worthCoins by viewModel.worthCoins.collectAsState()
    val hotCoins by viewModel.hotCoins.collectAsState()
    val gainersCoins by viewModel.gainersCoins.collectAsState()
    val losersCoins by viewModel.losersCoins.collectAsState()
    val topVolumeCoins by viewModel.topVolumeCoins.collectAsState()
    val usdtIdrRate by viewModel.usdtIdrRate.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val dashboardTicks by viewModel.dashboardTicks.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val coinBadges by viewModel.coinBadges.collectAsState()
    val strategyMode by viewModel.strategyMode.collectAsState()
    val holdingStatuses by viewModel.holdingStatuses.collectAsState()
    val spotPosition by viewModel.spotPosition.collectAsState()
    val currentTick by viewModel.currentTick.collectAsState()
    val newsScreenerState by viewModel.newsScreenerState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val dashboardAllLimit by viewModel.dashboardAllLimit.collectAsState()

    val selectedQuickFilter by viewModel.dashboardQuickFilter.collectAsState()
    var currentTab by remember { mutableStateOf(NavTab.WATCHLIST) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showNewsScreener by remember { mutableStateOf(false) }
    var showLogcatDialog by remember { mutableStateOf(false) }
    // Hasil pencarian koin SELALU masuk ke mini-chart dulu sebelum chart penuh.
    var previewPair by remember { mutableStateOf<TradingPair?>(null) }

    // Siapkan candle 1 jam begitu pratinjau dibuka, supaya mini-chart tidak
    // hanya mengandalkan turunan 24 jam dari tick. Cache bukan StateFlow, jadi
    // satu tick versi dipakai untuk memicu pembacaan ulang setelah fetch selesai.
    var previewCandleVersion by remember { mutableStateOf(0) }
    LaunchedEffect(previewPair?.symbol) {
        val target = previewPair ?: return@LaunchedEffect
        viewModel.ensureH1Candles(target.symbol)
        kotlinx.coroutines.delay(900)
        previewCandleVersion++
    }

    val defaultQuote = marketDataSource.defaultQuoteAsset

    // Gabungkan seluruh data ticks real-time (SSOT dengan Detail / WebSocket aktif)
    val allTicks = remember(dashboardTicks, currentTick, hotCoins, gainersCoins, losersCoins, topVolumeCoins) {
        val base = dashboardTicks +
            hotCoins.associateBy { it.symbol } +
            gainersCoins.associateBy { it.symbol } +
            losersCoins.associateBy { it.symbol } +
            topVolumeCoins.associateBy { it.symbol }
        if (currentTick != null) {
            val ct = currentTick!!
            base + (ct.symbol to ct) +
                   (ct.symbol.uppercase() to ct) +
                   (ct.symbol.lowercase() to ct)
        } else {
            base
        }
    }

    val worthBySymbol = remember(worthCoins) { worthCoins.associateBy { it.pair.symbol } }
    val isConnected = connectionState is MarketConnectionState.Connected

    // Kumpulan pasangan koin dasar dari pasar
    val basePopular = remember(marketDataSource) {
        TradingPair.popularPairsForSource(marketDataSource)
    }

    // Satu entri per simbol: peta tick menyimpan 1 koin di beberapa kunci (BTCIDR, BTC_IDR, btc_idr)
    // K14: saring sekali di awal dengan gate listing bursa aktif, supaya simbol di luar daftar
    // resmi tidak bisa bocor lewat jalur tick mana pun (highVol, swingCandidates, intradayCandidates).
    val uniqueTicks = remember(allTicks, marketDataSource) {
        allTicks.values
            .distinctBy { it.symbol.uppercase().replace("_", "").replace("/", "") }
            .filter { agu.analys.util.DashboardRanking.symbolListed(marketDataSource, it.symbol) }
    }

    // Watchlist/favorit dicocokkan per simbol penuh (BTCIDR != BTCUSDT), konsisten dengan cara daftar dibangun
    val watchSymbols = remember(watchlist, favorites, defaultQuote) {
        (watchlist + favorites).map { TradingPair.fromCustomSymbol(it, defaultQuote).symbol }.toSet()
    }
    val favSymbols = remember(favorites, defaultQuote) {
        favorites.map { TradingPair.fromCustomSymbol(it, defaultQuote).symbol }.toSet()
    }

    // Prioritaskan daftar koin fokus sesuai Mode Strategi yang aktif (Focus Mode)
    val strategyPairs = remember(
        strategyMode,
        gainersCoins,
        hotCoins,
        topVolumeCoins,
        losersCoins,
        uniqueTicks,
        watchlist,
        favorites,
        basePopular,
        usdtIdrRate,
        marketDataSource
    ) {
        // Volume dibandingkan dalam IDR (USDT dikonversi). Tanpa floor harga absolut untuk pair USDT.
        fun volIdr(t: agu.analys.model.MarketTick) =
            agu.analys.util.DashboardRanking.volumeInIdr(t.symbol, t.volume24h, usdtIdrRate)
        fun priceOk(t: agu.analys.model.MarketTick) =
            agu.analys.util.DashboardRanking.passesPriceFloor(t) &&
                agu.analys.util.DashboardRanking.isRankable(marketDataSource, t)
        // Fail-closed khusus Tokocrypto: pair yang tidak berstatus TRADING di discovery
        // resmi (delisted / tak dikenal) tidak boleh bocor lewat jalur statis maupun gainers.
        // K14: satu gate untuk kedua bursa lewat DashboardRanking.symbolListed.
        fun pairListed(p: TradingPair) =
            agu.analys.util.DashboardRanking.symbolListed(marketDataSource, p.symbol)
        // Tokocrypto: daftar discovery berisi semua pair, jadi hanya ambil 15 teratas sebagai cadangan
        val popularHead = if (marketDataSource == MarketDataSource.TOKOCRYPTO) {
            basePopular.filter { pairListed(it) }.take(agu.analys.util.DashboardRanking.PAGE_SIZE)
        } else basePopular
        val watchAndFav = (watchlist.map { TradingPair.fromCustomSymbol(it, defaultQuote) } +
            favorites.map { TradingPair.fromCustomSymbol(it, defaultQuote) }).filter { pairListed(it) }

        when (strategyMode) {
            StrategyMode.SCALPING -> {
                val highVol = uniqueTicks
                    .filter { priceOk(it) && volIdr(it) >= 200_000_000.0 }
                    .sortedWith(compareByDescending<agu.analys.model.MarketTick> { it.change24h > 0 }.thenByDescending { volIdr(it) })
                    .take(30)
                    .map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
                val explicit = (gainersCoins.map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) } +
                    hotCoins.map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }).filter { pairListed(it) }
                (watchAndFav + explicit + highVol + popularHead).distinctBy { it.symbol }
            }
            StrategyMode.SWING -> {
                val swingCandidates = uniqueTicks
                    .filter { priceOk(it) && volIdr(it) >= 500_000_000.0 && it.change24h in -3.0..8.0 }
                    .sortedByDescending { volIdr(it) }
                    .take(25)
                    .map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
                (watchAndFav + swingCandidates + popularHead).distinctBy { it.symbol }
            }
            StrategyMode.OFFICE_DAILY -> {
                val intradayCandidates = uniqueTicks
                    .filter { priceOk(it) && volIdr(it) >= 300_000_000.0 && it.change24h >= -3.5 }
                    .sortedWith(
                        compareByDescending<agu.analys.model.MarketTick> { IntradayScreener.evaluateFast(it).score }
                            .thenByDescending { volIdr(it) }
                    )
                    .take(30)
                    .map { TradingPair.fromCustomSymbol(it.symbol, defaultQuote) }
                (watchAndFav + intradayCandidates + popularHead).distinctBy { it.symbol }
            }
        }
    }

    // 1. Kumpulan seluruh aset bursa aktif yang diurutkan murni berdasarkan Volume 24H Tertinggi (USDT dinormalisasi ke IDR)
    val allVolumeSortedPairs = remember(
        allTicks,
        marketDataSource,
        usdtIdrRate,
        basePopular
    ) {
        val validTicks = agu.analys.util.DashboardRanking.rankByVolume(allTicks.values, usdtIdrRate) {
            agu.analys.util.DashboardRanking.isRankable(marketDataSource, it)
        }
        if (validTicks.isNotEmpty()) {
            validTicks
                .map { TradingPair.fromCustomSymbol(it.symbol) }
                .distinctBy { it.symbol }
        } else {
            // Fail-closed: fallback statis pun wajib lolos gate listing bursa aktif.
            basePopular.filter {
                agu.analys.util.DashboardRanking.symbolListed(marketDataSource, it.symbol)
            }.take(agu.analys.util.DashboardRanking.PAGE_SIZE)
        }
    }

    // Terapkan Quick Filter Chips [Semua] [Signal Kuat ⭐] [💼 Holding] [⭐ Watchlist]
    val filteredFocusPairs = remember(
        strategyPairs,
        selectedQuickFilter,
        holdingStatuses,
        watchlist,
        favorites,
        worthBySymbol,
        coinBadges,
        allVolumeSortedPairs,
        watchSymbols
    ) {
        when (selectedQuickFilter) {
            DashboardQuickFilter.ALL -> strategyPairs
            DashboardQuickFilter.STRONG_SIGNAL -> {
                // Cari di daftar fokus + seluruh aset terurut volume; kenaikan harga saja bukan sinyal.
                (strategyPairs + allVolumeSortedPairs).distinctBy { it.symbol }.filter { pair ->
                    val worth = worthBySymbol[pair.symbol]
                    val badges = coinBadges[pair.symbol] ?: emptyList()
                    val engineSignal = viewModel.getEngineSignal(pair.symbol)
                    val isAiBuy = engineSignal != null && engineSignal.action == SignalAction.BUY && engineSignal.confidence >= 55
                    val isWorth = worth != null && worth.isWorthIt && worth.worthScore >= 70
                    val isBreakout = badges.any { it.label == "BREAKOUT" || it.label == "MOMENTUM" }
                    isAiBuy || isWorth || isBreakout
                }
            }
            DashboardQuickFilter.HOLDING -> {
                val holdingPairs = holdingStatuses.filter { it.value.isHolding && it.value.quantity > 0.00000001 }
                    .keys
                    .map { TradingPair.fromCustomSymbol(it) }
                val matchedStrategyPairs = strategyPairs.filter { pair ->
                    val status = holdingStatuses[pair.symbol]
                    status != null && status.isHolding && status.quantity > 0.00000001
                }
                (holdingPairs + matchedStrategyPairs).distinctBy { it.symbol }
            }
            DashboardQuickFilter.WATCHLIST -> {
                // K14: watchlist/favorit yang tampil wajib lolos gate listing yang sama dengan
                // daftar fokus — entri lama yang tidak ada di bursa aktif tetap disaring di sini.
                val watchListPairs = (watchlist.map { TradingPair.fromCustomSymbol(it, defaultQuote) } +
                    favorites.map { TradingPair.fromCustomSymbol(it, defaultQuote) })
                    .filter { agu.analys.util.DashboardRanking.symbolListed(marketDataSource, it.symbol) }
                val matchedStrategyPairs = strategyPairs.filter { pair -> pair.symbol in watchSymbols }
                (watchListPairs + matchedStrategyPairs).distinctBy { it.symbol }
            }
        }
    }

    // Data posisi holding aktif real (SSOT: posisi spot realtime + chart sparkline 1 jam yang mencerminkan detail)
    val activeHoldingList = remember(holdingStatuses, allTicks, spotPosition, strategyPairs) {
        val holdingEntries = holdingStatuses.filter { it.value.isHolding && it.value.quantity > 0.00000001 }

        holdingEntries.mapNotNull { (symbol, status) ->
            if (symbol.isBlank()) return@mapNotNull null
            // Dapatkan TradingPair asli dari symbol holding tanpa mutasi ke defaultQuote (cth: BTCUSDT tetap BTCUSDT, BTCIDR tetap BTCIDR)
            val pair = TradingPair.fromCustomSymbol(symbol)

            val pos = if (viewModel.isMatchingSymbol(symbol, spotPosition.symbol) || viewModel.isMatchingSymbol(pair.symbol, spotPosition.symbol)) {
                spotPosition
            } else {
                viewModel.positionCoordinator.getPosition(pair.symbol)
            }
            val tick = allTicks[pair.symbol] ?: allTicks[symbol] ?: allTicks[pair.baseAsset.uppercase()]
            val candles1h = viewModel.getH1Candles(pair.symbol)
            ActiveHoldingItemData(
                pair = pair,
                holding = status,
                position = pos,
                tick = tick,
                candles1h = candles1h
            )
        }.distinctBy { it.pair.symbol }
    }

    // Prefetch/sync candle 1H untuk semua holding aktif secara background agar chart sparkline selalu ready
    LaunchedEffect(activeHoldingList.map { it.pair.symbol }) {
        while (true) {
            activeHoldingList.forEach { item ->
                viewModel.ensureH1Candles(item.pair.symbol)
            }
            kotlinx.coroutines.delay(10 * 60 * 1000L) // ensureH1Candles hanya fetch bila data > 2 jam
        }
    }

    // 2. Pasangan koin yang ditampilkan (Tab SEMUA dipaginasi 15 chunk bertahap, tab filter lain tanpa perubahan)
    val totalAvailablePairs = remember(selectedQuickFilter, allVolumeSortedPairs.size, filteredFocusPairs.size) {
        if (selectedQuickFilter == DashboardQuickFilter.ALL) {
            allVolumeSortedPairs.size
        } else {
            filteredFocusPairs.size
        }
    }

    val displayedPairs = remember(
        selectedQuickFilter,
        allVolumeSortedPairs,
        filteredFocusPairs,
        dashboardAllLimit
    ) {
        when (selectedQuickFilter) {
            DashboardQuickFilter.ALL -> allVolumeSortedPairs.take(dashboardAllLimit)
            DashboardQuickFilter.STRONG_SIGNAL,
            DashboardQuickFilter.HOLDING,
            DashboardQuickFilter.WATCHLIST -> filteredFocusPairs
        }
    }

    // Maksimal volume untuk rasio mini volume bar
    val maxVolume = remember(displayedPairs, allTicks, usdtIdrRate) {
        displayedPairs.maxOfOrNull {
            allTicks[it.symbol]?.let { t ->
                agu.analys.util.DashboardRanking.volumeInIdr(t.symbol, t.volume24h, usdtIdrRate)
            } ?: 0.0
        }?.takeIf { it > 0 } ?: 1.0
    }

    val focusListTitle = remember(marketDataSource, strategyMode, selectedQuickFilter) {
        if (selectedQuickFilter == DashboardQuickFilter.ALL) {
            "TOP VOLUME 24H SPOT — ${marketDataSource.label.uppercase()}"
        } else {
            "FOCUS LIST — ${strategyMode.name.replace('_', ' ')} MODE"
        }
    }

    val listState = rememberLazyListState()

    // Infinite scroll listener: saat user scroll up (melihat ke bawah) mendekati akhir list, muat 15 aset lagi
    val shouldLoadMore by remember(selectedQuickFilter, displayedPairs.size, totalAvailablePairs) {
        derivedStateOf {
            if (selectedQuickFilter != DashboardQuickFilter.ALL) return@derivedStateOf false
            if (displayedPairs.size >= totalAvailablePairs) return@derivedStateOf false
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf false
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= totalItems - 2
        }
    }

    LaunchedEffect(shouldLoadMore, displayedPairs.size) {
        if (shouldLoadMore) {
            viewModel.loadMoreDashboardPairs()
        }
    }

    LaunchedEffect(selectedQuickFilter) {
        if (selectedQuickFilter == DashboardQuickFilter.ALL) {
            listState.scrollToItem(0)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TvBackground)
        ) {
            // 1. Modern Header (Clean: Logo + Title + Status + Refresh + Signal Logs + Logcat)
            DashboardModernHeader(
                marketDataSource = marketDataSource,
                isConnected = isConnected,
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refreshWorthCoinsFromMarket(resetPagination = true) },
                onOpenLogcat = { showLogcatDialog = true },
                onOpenSignalLogs = { viewModel.openSignalLogs() }
            )

            // Offline banner jika koneksi terputus
            if (connectionState is MarketConnectionState.ConnectionLost) {
                val lost = connectionState as MarketConnectionState.ConnectionLost
                OfflineBanner(lost.reason) { viewModel.retryConnection() }
            }

            // 2. Section HOLDING AKTIF (Sticky di atas, collapsible)
            ActiveHoldingSection(
                items = activeHoldingList,
                onCoinClick = { pair ->
                    viewModel.selectPair(pair)
                    onOpenChart(pair)
                }
            )

            // 3. Quick Filter Chips [Semua] [Signal Kuat ⭐] [💼 Holding] [⭐ Watchlist]
            QuickFilterChips(
                selectedFilter = selectedQuickFilter,
                onSelectFilter = { viewModel.setDashboardQuickFilter(it) }
            )

            // 4. Focus List Section Header (Dinamis sesuai Strategy Mode)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = focusListTitle,
                    color = TvCyan,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = if (selectedQuickFilter == DashboardQuickFilter.ALL) {
                        "${displayedPairs.size} dari $totalAvailablePairs aset"
                    } else {
                        "${displayedPairs.size} aset"
                    },
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 5. LazyColumn Focus List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (displayedPairs.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = TvCardBackground,
                            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterListOff,
                                    contentDescription = null,
                                    tint = TvTextSecondary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Tidak ada koin yang sesuai filter saat ini.",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Pilih kategori filter lain atau ubah mode strategi.",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { viewModel.setDashboardQuickFilter(DashboardQuickFilter.ALL) },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Reset Filter ke Semua", color = TvCyan, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else {
                    items(displayedPairs, key = { "focus_pair_${it.symbol}" }) { pair ->
                        val tick = allTicks[pair.symbol]
                        val effectiveBadges = remember(coinBadges, pair.symbol, tick, strategyMode) {
                            val fromState = coinBadges[pair.symbol]
                            if (!fromState.isNullOrEmpty()) {
                                fromState
                            } else if (tick != null) {
                                agu.analys.engine.badge.CoinBadgeEvaluator.evaluateBadges(
                                    pair = pair,
                                    tick = tick,
                                    activeStrategy = strategyMode,
                                    maxBadges = 1
                                )
                            } else {
                                emptyList()
                            }
                        }

                        FocusCoinCard(
                            data = FocusCoinCardData(
                                pair = pair,
                                tick = tick,
                                worth = worthBySymbol[pair.symbol],
                                aiSignal = viewModel.getEngineSignal(pair.symbol),
                                badges = effectiveBadges,
                                isFavorite = favSymbols.contains(pair.symbol),
                                maxVolume = maxVolume,
                                isTopPicked = (pair.symbol == displayedPairs.firstOrNull()?.symbol)
                            ),
                            onToggleFavorite = { viewModel.toggleFavorite(pair.symbol) },
                            onClick = {
                                viewModel.selectPair(pair)
                                onOpenChart(pair)
                            }
                        )
                    }

                    // Indikator Paginasi Infinite Scroll (Khusus tab ALL)
                    if (selectedQuickFilter == DashboardQuickFilter.ALL) {
                        if (displayedPairs.size < totalAvailablePairs) {
                            item(key = "load_more_indicator") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                        .testTag("load_more_indicator"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = TvCyan
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Memuat 15 aset berikutnya (${displayedPairs.size} dari $totalAvailablePairs)...",
                                        color = TvTextSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        } else if (totalAvailablePairs > 15) {
                            item(key = "all_loaded_indicator") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✓ Seluruh $totalAvailablePairs aset bervolume tinggi telah dimuat",
                                        color = TvTextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // 6. Bottom Navigation Bar (5 Tab: Watchlist, Portfolio, Chart, Simulation, Settings)
            AppBottomNavigationBar(
                currentTab = currentTab,
                onSelectTab = { tab ->
                    currentTab = tab
                    when (tab) {
                        NavTab.WATCHLIST -> { /* Tetap di Watchlist */ }
                        NavTab.PORTOFOLIO -> {
                            viewModel.openPortfolio()
                        }
                        NavTab.SIMULASI -> {
                            val firstPair = filteredFocusPairs.firstOrNull() ?: basePopular.first()
                            viewModel.openSimulation(firstPair)
                        }
                        NavTab.SETTINGS -> {
                            onOpenSettings()
                        }
                    }
                }
            )
        }

        // Draggable AI Screener Shortcut Button
        var fabOffsetX by remember { mutableStateOf(0f) }
        var fabOffsetY by remember { mutableStateOf(0f) }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 68.dp)
                .offset { IntOffset(fabOffsetX.roundToInt(), fabOffsetY.roundToInt()) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragDistance = 0f
                        var isDown = true
                        while (isDown) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                isDown = false
                                if (dragDistance < 15f) {
                                    showNewsScreener = true
                                }
                            } else {
                                val delta = change.positionChange()
                                if (delta != androidx.compose.ui.geometry.Offset.Zero) {
                                    dragDistance += kotlin.math.abs(delta.x) + kotlin.math.abs(delta.y)
                                    if (dragDistance > 6f) {
                                        change.consume()
                                        fabOffsetX += delta.x
                                        fabOffsetY += delta.y
                                    }
                                }
                            }
                        }
                    }
                }
                .testTag("floating_ai_screener_btn")
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1E1B4B),
                shadowElevation = 5.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color(0xFF818CF8), Color(0xFF38BDF8))
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF312E81), Color(0xFF0369A1))
                            )
                        )
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("✨", fontSize = 11.sp)
                    Text(
                        text = "AI Screener",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }

        // Dialogs
        if (showAddDialog) {
            AddAssetDialog(
                currentFavorites = favorites,
                marketDataSource = marketDataSource,
                ticks = allTicks,
                candlesProvider = { symbol -> viewModel.getH1Candles(symbol) },
                onDismiss = { showAddDialog = false },
                // Klik baris hasil pencarian: pratinjau mini-chart dulu.
                onPreviewPair = { pair ->
                    showAddDialog = false
                    previewPair = pair
                },
                // Tombol "+ Tambah" tetap jalur cepat favorit.
                onAddPair = { pair ->
                    if (!favorites.contains(pair.symbol)) {
                        viewModel.toggleFavorite(pair.symbol)
                    }
                    showAddDialog = false
                }
            )
        }

        previewPair?.let { pair ->
            val previewTick = allTicks[pair.symbol]
                ?: allTicks[pair.symbol.replace("_", "")]
                ?: allTicks[pair.symbol.replace("_", "").lowercase()]
                ?: allTicks[pair.baseAsset.uppercase()]
            val previewCandles = remember(previewCandleVersion, pair.symbol) {
                viewModel.getH1Candles(pair.symbol)
            }
            CoinMiniChartPreview(
                pair = pair,
                tick = previewTick,
                candles = previewCandles,
                isFavorite = favorites.contains(pair.symbol),
                onOpenFullChart = {
                    viewModel.selectPair(pair)
                    onOpenChart(pair)
                    previewPair = null
                },
                onToggleFavorite = { viewModel.toggleFavorite(pair.symbol) },
                onDismiss = { previewPair = null }
            )
        }

        if (showNewsScreener) {
            NewsAiScreenerModal(
                state = newsScreenerState,
                provider = viewModel.prefs.aiProvider,
                liveTicks = allTicks,
                onDismiss = { showNewsScreener = false },
                onRunScreener = { force ->
                    viewModel.runNewsAiScreener(forceRefresh = force)
                },
                onSelectCoin = { pair ->
                    viewModel.selectPair(pair)
                    onOpenChart(pair)
                }
            )
        }

        if (showLogcatDialog) {
            LogcatDiagnosticDialog(
                onDismissRequest = { showLogcatDialog = false }
            )
        }
    }
}
