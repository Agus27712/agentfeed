package agu.analys.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.model.AppScreen
import agu.analys.model.TradingPair
import agu.analys.ui.components.dashboard.AppBottomNavigationBar
import agu.analys.ui.components.dashboard.NavTab
import agu.analys.ui.components.security.SecurityPinDialog
import agu.analys.ui.components.simulation.CurrencyConversionDialog
import agu.analys.ui.components.simulation.SimulationTopUpModal
import agu.analys.ui.screens.portfolio.HoldingItem
import agu.analys.ui.screens.portfolio.PortfolioTab
import agu.analys.ui.screens.portfolio.RealPortfolioView
import agu.analys.ui.screens.portfolio.SimulationPortfolioView
import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.ui.theme.*
import agu.analys.viewmodel.*

/**
 * Screen Utama Portofolio (Coordinator):
 * Memisahkan secara bersih antara Portofolio Simulasi dan Portofolio Real Tokocrypto.
 */
@Composable
fun PortfolioScreen(
    viewModel: TradingViewModel,
    onOpenChart: (TradingPair) -> Unit,
    onNavigateToSimulation: (TradingPair) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wallet by viewModel.simulationWallet.collectAsStateWithLifecycle()
    val history by viewModel.simulationHistory.collectAsStateWithLifecycle()
    val openOrders by viewModel.simulationOpenOrders.collectAsStateWithLifecycle()
    val dashboardTicks by viewModel.dashboardTicks.collectAsStateWithLifecycle()
    val currentTick by viewModel.currentTick.collectAsStateWithLifecycle()
    val selectedPair by viewModel.selectedPair.collectAsStateWithLifecycle()

    val isRealBuyMode by viewModel.isRealBuyMode.collectAsStateWithLifecycle()
    val isPinUnlocked by viewModel.isPinUnlocked.collectAsStateWithLifecycle()
    val realBalance by viewModel.realSpotBalance.collectAsStateWithLifecycle()
    val realFreeBalance by viewModel.realFreeBalance.collectAsStateWithLifecycle()
    val realLockedBalance by viewModel.realLockedBalance.collectAsStateWithLifecycle()
    val realOpenOrders by viewModel.realOpenOrders.collectAsStateWithLifecycle()
    val realTrades by viewModel.realTrades.collectAsStateWithLifecycle()
    val realAvgBuyPrices by viewModel.realAvgBuyPrices.collectAsStateWithLifecycle()
    val isFetchingRealBalance by viewModel.isFetchingRealBalance.collectAsStateWithLifecycle()
    val realTradeStatus by viewModel.realTradeStatus.collectAsStateWithLifecycle()
    val isRealSimSyncEnabled by viewModel.isRealSimSyncEnabled.collectAsStateWithLifecycle()

    // Kurs USDT/IDR real-time dari ExchangeRateManager (satu-satunya sumber kurs, tanpa hardcode).
    val usdtIdrRate by viewModel.usdtIdrRateState.collectAsStateWithLifecycle()

    var showRealPortfolioMode by remember(isRealBuyMode) { mutableStateOf(isRealBuyMode) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinDialogError by remember { mutableStateOf<String?>(null) }

    // Refresh saldo saat PIN unlock atau saat tab Real dibuka
    LaunchedEffect(isPinUnlocked, showRealPortfolioMode) {
        if (isPinUnlocked && showRealPortfolioMode && viewModel.hasRealCredentialsConfigured()) {
            viewModel.fetchRealBalance(force = true)
        }
    }

    var selectedTab by remember { mutableStateOf(PortfolioTab.HOLDINGS) }
    var showTopUpModal by remember { mutableStateOf(false) }
    // Dialog konversi Rupiah ⇄ USDT (fitur konversi di halaman Portofolio).
    var showConvertDialog by remember { mutableStateOf(false) }

    val realIdr = realBalance["idr"] ?: viewModel.prefs.getSavedRealBalance()["idr"] ?: 0.0

    // Hitung Koin Dimiliki & Metrik Khusus Portofolio Simulasi (Murni Simulasi, Tidak Tercampur Real)
    //
    // PENTING: kuotasi posisi dibaca dari wallet (coinQuoteAssets) — posisi yang dibeli di
    // pair USDT dihitung dalam `$` (BTCUSDT), posisi di pair IDR dalam `Rp` (BTCIDR).
    // Hanya saat perlu agregasi total portofolio nilainya dinormalisasi ke Rupiah
    // memakai kurs live, sehingga tampilan per-koin tidak pernah salah prefix.
    // Bursa aktif dipakai untuk menyelesaikan tarif fee saat menghitung P&L net (lihat di bawah).
    val exchangeName = viewModel.prefs.marketDataSource.name

    val holdings = remember(wallet, dashboardTicks, currentTick, usdtIdrRate, exchangeName) {
        wallet.coinBalances.filter { it.value > 0.00000001 }
            .entries
            .groupBy { it.key.uppercase() }
            .map { (baseAssetUpper, entries) ->
                val totalQty = entries.sumOf { it.value }
                val quoteAsset = wallet.quoteForCoin(baseAssetUpper)
                val isUsdt = agu.analys.util.PriceFormatter.isUsdtQuote(quoteAsset)
                val symbol = if (isUsdt) "${baseAssetUpper}USDT" else "${baseAssetUpper}IDR"

                // Harga pasar diambil dari pair yang SESUAI kuotasi posisi.
                val price = when {
                    symbol.equals(currentTick?.symbol, ignoreCase = true) -> currentTick?.price ?: 0.0
                    dashboardTicks.containsKey(symbol) -> dashboardTicks[symbol]?.price ?: 0.0
                    dashboardTicks.containsKey("${baseAssetUpper.lowercase()}_idr") -> dashboardTicks["${baseAssetUpper.lowercase()}_idr"]?.price ?: 0.0
                    dashboardTicks.containsKey("${baseAssetUpper.lowercase()}_usdt") -> dashboardTicks["${baseAssetUpper.lowercase()}_usdt"]?.price ?: 0.0
                    dashboardTicks.containsKey(baseAssetUpper) -> dashboardTicks[baseAssetUpper]?.price ?: 0.0
                    else -> 0.0
                }
                // Harga rata-rata beli tersimpan dalam mata uang kuotasi posisi.
                val avgPrice = wallet.avgBuyPrices[baseAssetUpper]
                    ?: wallet.avgBuyPrices[baseAssetUpper.lowercase()]
                    ?: 0.0
                val effectivePrice = if (price > 0.0) price else avgPrice

                // Normalisasi ke Rupiah hanya untuk agregasi.
                val rate = if (isUsdt) usdtIdrRate else 1.0
                val toIdr = { v: Double -> if (isUsdt && rate <= 0.0) 0.0 else v * rate }
                val totalValueIdr = toIdr(totalQty * effectivePrice)
                // Basis biaya tunggal (2026-10-15): **taker + slippage dua sisi**, sama persis
                // dengan FSM paper trade. Sebelumnya baris ini memakai selisih harga **bruto**
                // tanpa fee sama sekali, sehingga halaman ini selalu melaporkan angka paling
                // optimistis di aplikasi — untuk koin yang sama bisa berbeda ~0,82 poin persen
                // dari FSM, dan selisih itu terbaca sebagai bug oleh pengguna.
                val costBasis = if (avgPrice > 0.0) {
                    agu.analys.config.FeeSchedule.costBasisNet(
                        quantity = totalQty,
                        entry = avgPrice,
                        buyFeePct = agu.analys.config.FeeSchedule.buyRate(exchangeName, quoteAsset) * 100.0
                    )
                } else {
                    0.0
                }
                val netValue = agu.analys.config.FeeSchedule.netSellValue(
                    quantity = totalQty,
                    price = effectivePrice,
                    sellFeePct = agu.analys.config.FeeSchedule.sellRate(exchangeName, quoteAsset) * 100.0
                )
                val pnlIdr = if (avgPrice > 0.0) toIdr(netValue - costBasis) else 0.0
                val pnlPct = if (avgPrice > 0.0 && costBasis > 0.0) ((netValue - costBasis) / costBasis) * 100.0 else 0.0

                val pair = TradingPair.fromCustomSymbol(symbol, quoteAsset)
                HoldingItem(
                    baseAsset = baseAssetUpper,
                    quantity = totalQty,
                    avgBuyPrice = avgPrice,
                    currentPrice = effectivePrice,
                    totalValueIdr = totalValueIdr,
                    pnlIdr = pnlIdr,
                    pnlPercent = pnlPct,
                    tradingPair = pair,
                    isRealMirror = false,
                    quoteAsset = quoteAsset,
                    usdtIdrRate = if (isUsdt) rate else 0.0
                )
            }.sortedByDescending { it.totalValueIdr }
    }

    val totalCoinValueIdr = remember(holdings) { holdings.sumOf { it.totalValueIdr } }
    val totalKasSimulasi = wallet.idrBalance
    // Kas USDT ikut dihitung sebagai aset (dikonversi ke Rupiah dengan kurs live).
    val totalKasUsdtIdr = remember(wallet.idrBalance, wallet.usdtBalance, usdtIdrRate) {
        if (usdtIdrRate > 0.0) wallet.usdtBalance * usdtIdrRate else 0.0
    }
    val totalPortfolioValueIdr = remember(totalKasSimulasi, totalKasUsdtIdr, totalCoinValueIdr) {
        totalKasSimulasi + totalKasUsdtIdr + totalCoinValueIdr
    }
    val totalUnrealizedPnlIdr = remember(holdings) { holdings.sumOf { it.pnlIdr } }
    val totalCostBasis = remember(holdings) { holdings.sumOf { it.quantity * it.avgBuyPrice } }
    val totalUnrealizedPnlPct = remember(totalCostBasis, totalUnrealizedPnlIdr) {
        if (totalCostBasis > 0.0) (totalUnrealizedPnlIdr / totalCostBasis) * 100.0 else 0.0
    }
    val displayHistory = history

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        // TOP APP BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TvBackground)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Kembali",
                        tint = TvTextPrimary
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        text = "PORTOFOLIO SAYA",
                        color = TvTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = if (showRealPortfolioMode) "Aset Riil ${viewModel.prefs.marketDataSource.label} Terhubung" else "Simulasi Akun & Manajemen Aset",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!showRealPortfolioMode) {
                    IconButton(
                        onClick = { showTopUpModal = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircleOutline,
                            contentDescription = "Top Up / Reset",
                            tint = TvGreen
                        )
                    }
                }
            }
        }

        // SEGMENT SWITCHER: SIMULASI VS REAL TOKOCRYPTO
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TvBackground)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
                    .padding(3.dp)
            ) {
                // Tab 1: SIMULASI
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!showRealPortfolioMode) TvCardBackground else Color.Transparent)
                        .clickable { showRealPortfolioMode = false }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = if (!showRealPortfolioMode) TvBlue else TvTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Portofolio Simulasi",
                            color = if (!showRealPortfolioMode) TvTextPrimary else TvTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Tab 2: REAL TOKOCRYPTO
                // JANGAN auto-refresh di sini — cuma switch UI + minta PIN kalau locked.
                val context = androidx.compose.ui.platform.LocalContext.current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (showRealPortfolioMode) TvCardBackground else Color.Transparent)
                        .clickable {
                            if (!isRealBuyMode) {
                                android.widget.Toast.makeText(context, "Mode Real Trade dinonaktifkan di Pengaturan", android.widget.Toast.LENGTH_SHORT).show()
                                return@clickable
                            }
                            showRealPortfolioMode = true
                            if (!isPinUnlocked) {
                                if (!viewModel.hasSecurityPin()) {
                                    onOpenSettings()
                                } else {
                                    showPinDialog = true
                                }
                            }
                            // sudah unlock → pakai cache saldo, refresh manual via tombol ↻
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (!isRealBuyMode) Icons.Default.Lock else if (isPinUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (showRealPortfolioMode) TvGreen else if (!isRealBuyMode) TvTextSecondary.copy(alpha = 0.5f) else TvTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Portofolio Real (Tokocrypto)",
                            color = if (showRealPortfolioMode) TvGreen else if (!isRealBuyMode) TvTextSecondary.copy(alpha = 0.5f) else TvTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // CONTENT SECTION: REAL VS SIMULATION
        if (showRealPortfolioMode) {
            RealPortfolioView(
                isPinUnlocked = isPinUnlocked,
                realBalance = realBalance,
                realFreeBalance = realFreeBalance,
                realLockedBalance = realLockedBalance,
                realOpenOrders = realOpenOrders,
                realTrades = realTrades,
                realAvgBuyPrices = realAvgBuyPrices,
                isFetchingRealBalance = isFetchingRealBalance,
                dashboardTicks = dashboardTicks,
                currentTick = currentTick,
                realTradeStatus = realTradeStatus,
                isTokocrypto = viewModel.prefs.marketDataSource == agu.analys.config.MarketDataSource.TOKOCRYPTO,
                onUnlockPin = {
                    if (!viewModel.hasSecurityPin()) {
                        onOpenSettings()
                    } else {
                        pinDialogError = null
                        showPinDialog = true
                    }
                },
                onRefreshRealBalance = { viewModel.fetchRealBalance(force = true) },
                onEditAvgBuyPrice = { coin, newAvg, newInv ->
                    viewModel.updateRealAvgBuyPrice(coin, newAvg, newInv)
                },
                onCancelRealOrder = { symbol, orderId ->
                    viewModel.executeCancelRealOrder(symbol, orderId) { _, _ -> }
                },
                onOpenChart = onOpenChart,
                onSelectPair = { viewModel.selectPair(it) },
                modifier = Modifier.weight(1f)
            )
        } else {
            SimulationPortfolioView(
                wallet = wallet,
                history = displayHistory,
                openOrders = openOrders,
                holdings = holdings,
                totalPortfolioValueIdr = totalPortfolioValueIdr,
                totalUnrealizedPnlIdr = totalUnrealizedPnlIdr,
                totalUnrealizedPnlPct = totalUnrealizedPnlPct,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onOpenTopUp = { showTopUpModal = true },
                onOpenChart = onOpenChart,
                onNavigateToSimulation = onNavigateToSimulation,
                onCancelOrder = { orderId -> viewModel.cancelSimulationOrder(orderId) },
                onCancelAllOrders = { symbol -> viewModel.cancelAllSimulationOrders(symbol) },
                realIdrBalance = 0.0,
                isRealSimSyncEnabled = false,
                usdtIdrRate = usdtIdrRate,
                onOpenConvert = { showConvertDialog = true },
                modifier = Modifier.weight(1f)
            )
        }

        // BOTTOM NAVIGATION BAR
        AppBottomNavigationBar(
            currentTab = NavTab.PORTOFOLIO,
            onSelectTab = { tab ->
                when (tab) {
                    NavTab.WATCHLIST -> viewModel.navigateTo(AppScreen.DASHBOARD)
                    NavTab.PORTOFOLIO -> { /* Sudah di Portofolio */ }
                    NavTab.SIMULASI -> viewModel.openSimulation()
                    NavTab.SETTINGS -> onOpenSettings()
                }
            }
        )
    }

    if (showTopUpModal) {
        SimulationTopUpModal(
            wallet = wallet,
            onTopUp = { amount ->
                viewModel.topUpSimulationBalance(amount)
                showTopUpModal = false
            },
            onSetBalance = { amount ->
                viewModel.setSimulationBalance(amount)
                showTopUpModal = false
            },
            onReset = {
                viewModel.resetSimulationAccount()
                showTopUpModal = false
            },
            onDismiss = { showTopUpModal = false }
        )
    }

    if (showConvertDialog) {
        CurrencyConversionDialog(
            wallet = wallet,
            onConvertIdrToUsdt = { amount -> viewModel.convertSimulationIdrToUsdt(amount).message },
            onConvertUsdtToIdr = { amount -> viewModel.convertSimulationUsdtToIdr(amount).message },
            onRefreshRate = { viewModel.refreshUsdtIdrRate() },
            onDismiss = { showConvertDialog = false }
        )
    }

    if (showPinDialog) {
        val exchangeLabel = viewModel.prefs.marketDataSource.label
        SecurityPinDialog(
            title = "VERIFIKASI PIN PORTOFOLIO REAL",
            subtitle = "Masukkan 6-digit PIN untuk membuka akses Portofolio $exchangeLabel.",
            isSetupMode = false,
            errorMessage = pinDialogError,
            onPinSubmitted = { enteredPin ->
                val ok = viewModel.verifyPin(enteredPin)
                if (ok) {
                    showPinDialog = false
                    pinDialogError = null
                    // Satu kali refresh setelah unlock PIN (cooldown di coordinator tetap berlaku)
                    viewModel.fetchRealBalance()
                } else {
                    pinDialogError = "PIN Keamanan Salah. Silakan coba lagi."
                }
            },
            onDismiss = {
                showPinDialog = false
                pinDialogError = null
            }
        )
    }
}