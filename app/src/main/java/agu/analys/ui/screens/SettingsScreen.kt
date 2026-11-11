package agu.analys.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.config.AiProvider
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.engine.intraday.IntradayRuntimeConfig
import agu.analys.engine.intraday.IntradaySensitivityPreset
import agu.analys.engine.scalping.ScalpingRuntimeConfig
import agu.analys.engine.scalping.SensitivityPreset
import agu.analys.engine.swing.SwingRuntimeConfig
import agu.analys.engine.swing.SwingSensitivityPreset
import agu.analys.ui.components.security.SecurityPinDialog
import agu.analys.ui.components.security.SetupRealApiDialog
import agu.analys.ui.components.settings.*
import agu.analys.bridge.OmpBridgeInstaller
import agu.analys.ui.theme.*
import agu.analys.util.AppPreferences
import agu.analys.viewmodel.*

@Composable
fun SettingsScreen(viewModel: TradingViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val currentMarketSource by viewModel.marketDataSource.collectAsStateWithLifecycle()
    var selectedSource by remember(currentMarketSource) { mutableStateOf(currentMarketSource) }
    var strategyMode by remember { mutableStateOf(prefs.strategyMode) }
    var provider by remember { mutableStateOf(prefs.aiProvider) }
    var gemini by remember { mutableStateOf(prefs.geminiApiKey) }
    var buyMakerFee by remember { mutableStateOf(prefs.tradingFees.buyMakerPct.toString()) }
    var buyTakerFee by remember { mutableStateOf(prefs.tradingFees.buyTakerPct.toString()) }
    var sellMakerFee by remember { mutableStateOf(prefs.tradingFees.sellMakerPct.toString()) }
    var sellTakerFee by remember { mutableStateOf(prefs.tradingFees.sellTakerPct.toString()) }
    val isRealBuyMode by viewModel.isRealBuyMode.collectAsStateWithLifecycle()
    val isPinUnlocked by viewModel.isPinUnlocked.collectAsStateWithLifecycle()
    val userPublicIp by viewModel.userPublicIp.collectAsStateWithLifecycle()
    val failedPinAttempts by viewModel.failedPinAttempts.collectAsState(initial = 0)
    var hasPin by remember { mutableStateOf(viewModel.hasSecurityPin()) }

    val currentThemeStyle by viewModel.themeStyle.collectAsStateWithLifecycle()
    val currentAccentPreset by viewModel.accentColorPreset.collectAsStateWithLifecycle()
    val currentCandleStyle by viewModel.candleColorStyle.collectAsStateWithLifecycle()
    val currentAnimationSpeed by viewModel.animationSpeed.collectAsStateWithLifecycle()
    val currentPriceAnimationMode by viewModel.priceAnimationMode.collectAsStateWithLifecycle()
    val isPriceTickPulseEnabled by viewModel.isPriceTickPulseEnabled.collectAsStateWithLifecycle()
    val isSmoothChartEnabled by viewModel.isSmoothChartEnabled.collectAsStateWithLifecycle()
    val watchlistPairs by viewModel.watchlist.collectAsStateWithLifecycle()
    val dashboardTicks by viewModel.dashboardTicks.collectAsStateWithLifecycle()

    var activeCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    // Slider sensitifitas mode hidup di dalam kartu mode (AUDIT-2026-11-08.md): kartu yang terbuka
    // dihoist ke sini supaya back perangkat menutup kartu dulu sebelum keluar kategori.
    var expandedMode by remember { mutableStateOf<StrategyMode?>(null) }
    val activeScalpingConfig by viewModel.scalpingRuntimeConfig.collectAsStateWithLifecycle()
    val activeScalpingPreset by viewModel.scalpingSensitivityPreset.collectAsStateWithLifecycle()
    var draftScalpingConfig by remember { mutableStateOf(activeScalpingConfig) }
    var draftScalpingPreset by remember { mutableStateOf(activeScalpingPreset) }
    // Slider mode INTRADAY — terpisah dari scalping (aturan §79.1 app/AGENTS.md).
    val activeIntradayConfig by viewModel.intradayRuntimeConfig.collectAsStateWithLifecycle()
    val activeIntradayPreset by viewModel.intradaySensitivityPreset.collectAsStateWithLifecycle()
    var draftIntradayConfig by remember { mutableStateOf(activeIntradayConfig) }
    var draftIntradayPreset by remember { mutableStateOf(activeIntradayPreset) }
    // Slider mode SWING — pattern sama; config & holder-nya sendiri.
    val activeSwingConfig by viewModel.swingRuntimeConfig.collectAsStateWithLifecycle()
    val activeSwingPreset by viewModel.swingSensitivityPreset.collectAsStateWithLifecycle()
    var draftSwingConfig by remember { mutableStateOf(activeSwingConfig) }
    var draftSwingPreset by remember { mutableStateOf(activeSwingPreset) }
    // P2 — draft pengaturan auto-buy / auto-exit paper trade
    var draftPaperAuto by remember { mutableStateOf(viewModel.paperAutoTradeConfig.value) }

    var showSetupRealApiDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var ompPrepared by remember { mutableStateOf<agu.analys.bridge.OmpBridgeInstaller.Prepared?>(null) }
    var pinDialogAction by remember { mutableStateOf(PinDialogAction.TOGGLE_REAL_BUY) }
    var pinDialogError by remember { mutableStateOf<String?>(null) }
    var pendingRealBuyToggle by remember { mutableStateOf(false) }
    var updateRepo by remember { mutableStateOf(prefs.updateRepo) }
    var updateToken by remember { mutableStateOf(prefs.updateGitHubToken) }
    var priceFeedThrottleMs by remember { mutableStateOf(prefs.priceFeedThrottleMs) }
    var saved by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }

    val completedLessons = remember { prefs.getCompletedLearningLessons() }
    val releaseInfo by viewModel.githubReleaseInfo.collectAsStateWithLifecycle()
    val checkingUpdate by viewModel.isCheckingUpdate.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateCheckStatus.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.updateDownloadProgress.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val isNotificationsEnabled by viewModel.isNotificationsEnabled.collectAsState()
    val isNotifyCandidateBuyEnabled by viewModel.isNotifyCandidateBuyEnabled.collectAsState()
    val isNotifyPriceAlertsEnabled by viewModel.isNotifyPriceAlertsEnabled.collectAsState()
    val isNotifyTrailingStopEnabled by viewModel.isNotifyTrailingStopEnabled.collectAsState()
    val isNotifyEmergencyExitEnabled by viewModel.isNotifyEmergencyExitEnabled.collectAsState()

    fun saveAllSettings(showToast: Boolean = true) {
        val isSourceChanged = selectedSource != prefs.marketDataSource
        if (isSourceChanged) {
            viewModel.setMarketDataSource(selectedSource, forceHardStop = true)
        }
        prefs.strategyMode = strategyMode
        prefs.isScalpingMode = (strategyMode == StrategyMode.SCALPING)
        prefs.aiProvider = provider
        prefs.geminiApiKey = gemini
        prefs.updateRepo = updateRepo
        prefs.updateGitHubToken = updateToken
        prefs.priceFeedThrottleMs = priceFeedThrottleMs
        viewModel.setUiPriceThrottleMs(priceFeedThrottleMs)
        val currentFees = prefs.tradingFees
        val updatedFees = currentFees.copy(
            buyMakerPct = buyMakerFee.toDoubleOrNull() ?: currentFees.buyMakerPct,
            buyTakerPct = buyTakerFee.toDoubleOrNull() ?: currentFees.buyTakerPct,
            sellMakerPct = sellMakerFee.toDoubleOrNull() ?: currentFees.sellMakerPct,
            sellTakerPct = sellTakerFee.toDoubleOrNull() ?: currentFees.sellTakerPct
        )
        prefs.tradingFees = updatedFees
        viewModel.updateTradingFees(updatedFees)
        viewModel.setStrategyMode(strategyMode)
        saved = true
        if (showToast) {
            val msg = if (isSourceChanged) {
                "Pengaturan disimpan. Jalur koneksi di-reset & cache dibersihkan untuk ${selectedSource.label}."
            } else {
                "Pengaturan berhasil disimpan"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun saveScalpingEngineSettings() {
        // Commit the draft first so the engine receives the exact values shown in the sliders.
        viewModel.updateScalpingRuntimeConfig(draftScalpingConfig, draftScalpingPreset)
        viewModel.updatePaperAutoTradeConfig(draftPaperAuto)
        viewModel.setStrategyMode(StrategyMode.SCALPING)
        strategyMode = StrategyMode.SCALPING
        saved = true
        Toast.makeText(context, "Konfigurasi Engine Scalping disimpan dan diaktifkan.", Toast.LENGTH_SHORT).show()
    }

    fun saveIntradayEngineSettings() {
        viewModel.updateIntradayRuntimeConfig(draftIntradayConfig, draftIntradayPreset)
        viewModel.setStrategyMode(StrategyMode.OFFICE_DAILY)
        strategyMode = StrategyMode.OFFICE_DAILY
        saved = true
        Toast.makeText(context, "Konfigurasi Engine Intraday disimpan dan diaktifkan.", Toast.LENGTH_SHORT).show()
    }

    fun saveSwingEngineSettings() {
        viewModel.updateSwingRuntimeConfig(draftSwingConfig, draftSwingPreset)
        viewModel.setStrategyMode(StrategyMode.SWING)
        strategyMode = StrategyMode.SWING
        saved = true
        Toast.makeText(context, "Konfigurasi Engine Swing disimpan dan diaktifkan.", Toast.LENGTH_SHORT).show()
    }

    BackHandler(enabled = expandedMode != null || activeCategory != null) {
        if (expandedMode != null) expandedMode = null else activeCategory = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        // TOP APP BAR
        Surface(
            color = TvSurface,
            shadowElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (expandedMode != null) {
                            expandedMode = null
                        } else if (activeCategory != null) {
                            activeCategory = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali",
                            tint = TvTextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            text = activeCategory?.title ?: "Pengaturan",
                            color = TvTextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (activeCategory != null) "Sub-pengaturan kategori" else "Sistem, tema, watchlist & akun",
                            color = TvTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                if (activeCategory != null) {
                    Button(
                        onClick = { saveAllSettings(true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (saved) TvGreen.copy(alpha = 0.2f) else TvGreen
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            if (saved) Icons.Default.CheckCircle else Icons.Default.Save,
                            contentDescription = null,
                            tint = if (saved) TvGreen else Color.Black,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (saved) "Tersimpan" else "Simpan",
                            color = if (saved) TvGreen else Color.Black,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // MAIN CONTENT
        AnimatedContent(
            targetState = activeCategory,
            transitionSpec = {
                if (targetState != null) {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> -width } + fadeOut()
                } else {
                    slideInHorizontally { width -> -width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> width } + fadeOut()
                }
            },
            modifier = Modifier.weight(1f)
        ) { category ->
            if (category == null) {
                // OVERVIEW PREFERENCE GROUPS
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    // Quick Status Banner
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "RINGKASAN STATUS SISTEM",
                                    color = TvTextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(if (isRealBuyMode) TvRed else TvGreen, CircleShape)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (isRealBuyMode) "MODE REAL ${selectedSource.label.uppercase()}" else "MODE SIMULASI MURNI",
                                        color = if (isRealBuyMode) TvRed else TvGreen,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Tema: ${currentThemeStyle.displayName.substringBefore(" ")}", color = TvBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "${strategyMode.name} · ${watchlistPairs.size} Pair Watchlist",
                                    color = TvTextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // GROUP 1: STRATEGI & TRADING
                    AndroidSettingsGroup(title = "TRADING & PASAR") {
                        AndroidPreferenceItem(
                            icon = Icons.Default.TrendingUp,
                            iconTint = TvBlue,
                            iconBackground = TvBlue.copy(alpha = 0.15f),
                            title = "Strategi & Sumber Pasar",
                            subtitle = "Sumber: ${selectedSource.label} · Mode ${strategyMode.name}",
                            onClick = { activeCategory = SettingsCategory.TRADING }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.FormatListBulleted,
                            iconTint = Color(0xFF00BCD4),
                            iconBackground = Color(0xFF00BCD4).copy(alpha = 0.15f),
                            title = "Pair Watchlist (Daftar Pantau)",
                            subtitle = "${watchlistPairs.size} Pair dipantau · Cari koin & preset cepat",
                            onClick = { activeCategory = SettingsCategory.WATCHLIST }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.ReceiptLong,
                            iconTint = Color(0xFFFFB300),
                            iconBackground = Color(0xFFFFB300).copy(alpha = 0.15f),
                            title = "Biaya Transaksi (Fee)",
                            subtitle = "Maker ${buyMakerFee}% · Taker ${buyTakerFee}%",
                            onClick = { activeCategory = SettingsCategory.TRADING }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.Assessment,
                            iconTint = TvGreen,
                            iconBackground = TvGreen.copy(alpha = 0.15f),
                            title = "Log Sinyal & Evaluasi Reliabilitas (Room DB)",
                            subtitle = "Rekaman sinyal, skor keyakinan & akurasi performa nyata",
                            onClick = { viewModel.openSignalLogs() }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.MenuBook,
                            iconTint = Color(0xFF00BCD4),
                            iconBackground = Color(0xFF00BCD4).copy(alpha = 0.15f),
                            title = "Modul Belajar Analisis Pasar",
                            subtitle = "${completedLessons.size}/11 Materi selesai dipelajari",
                            onClick = { viewModel.openLearning() }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // GROUP 2: TAMPILAN, TEMA & ANIMASI
                    AndroidSettingsGroup(title = "TAMPILAN, TEMA & ANIMASI") {
                        AndroidPreferenceItem(
                            icon = Icons.Default.Palette,
                            iconTint = Color(0xFFFF9800),
                            iconBackground = Color(0xFFFF9800).copy(alpha = 0.15f),
                            title = "Pilihan Warna & Tema (Theming)",
                            subtitle = "Tema: ${currentThemeStyle.displayName} · Aksen: ${currentAccentPreset.displayName}",
                            onClick = { activeCategory = SettingsCategory.APPEARANCE }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.MotionPhotosAuto,
                            iconTint = TvBlue,
                            iconBackground = TvBlue.copy(alpha = 0.15f),
                            title = "Pilihan Animasi & Motion",
                            subtitle = "Kecepatan: ${currentAnimationSpeed.displayName.substringBefore(" ")} · Efek Pulse & Chart",
                            onClick = { activeCategory = SettingsCategory.APPEARANCE }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceSwitchItem(
                            icon = if (isNotificationsEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                            iconTint = Color(0xFFFF7043),
                            iconBackground = Color(0xFFFF7043).copy(alpha = 0.15f),
                            title = "Notifikasi Trading & Sinyal",
                            subtitle = if (isNotificationsEnabled) "Push notifikasi aktif" else "Hemat daya & tanpa push notifikasi",
                            checked = isNotificationsEnabled,
                            onCheckedChange = { viewModel.setNotificationsEnabled(it) }
                        )
                        if (isNotificationsEnabled) {
                            HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                            AndroidPreferenceItem(
                                icon = Icons.Default.Notifications,
                                iconTint = Color(0xFFFF7043),
                                iconBackground = Color(0xFFFF7043).copy(alpha = 0.15f),
                                title = "Kustomisasi Kategori Notifikasi",
                                subtitle = "Kelola suara, getaran, lencana, & prioritas saluran sistem",
                                onClick = { activeCategory = SettingsCategory.NOTIFICATIONS }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // GROUP 3: KEAMANAN & AKUN
                    AndroidSettingsGroup(title = "KEAMANAN & API ${selectedSource.label.uppercase()}") {
                        AndroidPreferenceItem(
                            icon = Icons.Default.Shield,
                            iconTint = if (isRealBuyMode) TvRed else TvGreen,
                            iconBackground = (if (isRealBuyMode) TvRed else TvGreen).copy(alpha = 0.15f),
                            title = "Mode Beli Real & PIN Keamanan",
                            subtitle = if (isRealBuyMode)
                                "Mode Real ${selectedSource.label} Aktif · PIN Terpasang"
                            else
                                "Mode Simulasi Aktif · ${if (hasPin) "PIN Terproteksi" else "PIN Belum Dibuat"}",
                            onClick = { activeCategory = SettingsCategory.SECURITY }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // GROUP 4: AI ASSISTANT
                    AndroidSettingsGroup(title = "KECERDASAN BUATAN (AI)") {
                        AndroidPreferenceItem(
                            icon = Icons.Default.SmartToy,
                            iconTint = Color(0xFFAB47BC),
                            iconBackground = Color(0xFFAB47BC).copy(alpha = 0.15f),
                            title = "AI Assistant & Engine",
                            subtitle = "Provider: Google Gemini · Kelola API Key",
                            onClick = { activeCategory = SettingsCategory.AI_ASSISTANT }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // GROUP 5: SISTEM & DIAGNOSTIK
                    AndroidSettingsGroup(title = "SISTEM & PEMELIHARAAN") {
                        AndroidPreferenceItem(
                            icon = Icons.Default.Terminal,
                            iconTint = TvGreen,
                            iconBackground = TvGreen.copy(alpha = 0.15f),
                            title = "Logcat & Diagnostik Sistem",
                            subtitle = "Periksa log trailing, koin, error, filter & ekspor log",
                            onClick = { activeCategory = SettingsCategory.SYSTEM }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.SystemUpdate,
                            iconTint = TvBlue,
                            iconBackground = TvBlue.copy(alpha = 0.15f),
                            title = "Pembaruan & Versi Aplikasi",
                            subtitle = "Versi ${agu.analys.BuildConfig.VERSION_NAME} (Build ${agu.analys.BuildConfig.VERSION_CODE}) · Periksa rilis GitHub & cache",
                            onClick = { activeCategory = SettingsCategory.SYSTEM }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        // Sakelar mengikuti keadaan server yang sebenarnya, bukan nilai preferensi:
                        // preferensi bisa "aktif" sementara server gagal menyala (port dipakai).
                        val agentServer = remember { agu.analys.bridge.AgentHttpServer.getInstance(context) }
                        val agentState by agentServer.state.collectAsStateWithLifecycle()
                        var isAgentEnabled by remember { mutableStateOf(prefs.isAgentOutputEnabled) }
                        LaunchedEffect(agentState) {
                            when (val s = agentState) {
                                is agu.analys.bridge.AgentBridgeServerState.Running -> isAgentEnabled = true
                                is agu.analys.bridge.AgentBridgeServerState.Stopped -> {
                                    if (!prefs.isAgentOutputEnabled) isAgentEnabled = false
                                }
                                is agu.analys.bridge.AgentBridgeServerState.Failed -> {
                                    // Kembalikan sakelar ke mati; jangan biarkan UI mengklaim aktif.
                                    prefs.isAgentOutputEnabled = false
                                    isAgentEnabled = false
                                    Toast.makeText(
                                        context,
                                        "Agent Bridge gagal menyala: ${s.reason}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                is agu.analys.bridge.AgentBridgeServerState.Starting,
                                is agu.analys.bridge.AgentBridgeServerState.Stopping -> Unit
                            }
                        }
                        AndroidPreferenceSwitchItem(
                            icon = Icons.Default.Sensors,
                            iconTint = Color(0xFFFFB300),
                            iconBackground = Color(0xFFFFB300).copy(alpha = 0.15f),
                            title = "Agent Bridge V3 (Termux / OMP)",
                            subtitle = when (val s = agentState) {
                                is agu.analys.bridge.AgentBridgeServerState.Running ->
                                    "Aktif: ws://127.0.0.1:8765/ws/signals + Documents/agentfeed"
                                is agu.analys.bridge.AgentBridgeServerState.Starting ->
                                    "Menyalakan server port 8765…"
                                is agu.analys.bridge.AgentBridgeServerState.Stopping ->
                                    "Menghentikan server port 8765…"
                                is agu.analys.bridge.AgentBridgeServerState.Failed ->
                                    "Gagal menyala: ${s.reason}"
                                is agu.analys.bridge.AgentBridgeServerState.Stopped ->
                                    if (isAgentEnabled) "Menunggu server port 8765…"
                                    else "Output sinyal & WebSocket port 8765 nonaktif"
                            },
                            checked = isAgentEnabled,
                            onCheckedChange = { checked ->
                                prefs.isAgentOutputEnabled = checked
                                isAgentEnabled = checked
                                if (checked) {
                                    agentServer.start()
                                    Toast.makeText(context, "Menyalakan Agent Bridge V3 port 8765…", Toast.LENGTH_SHORT).show()
                                } else {
                                    agentServer.stop()
                                    Toast.makeText(context, "Agent Bridge V3 dihentikan", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        AndroidPreferenceItem(
                            icon = Icons.Default.Terminal,
                            iconTint = Color(0xFFFFB300),
                            iconBackground = Color(0xFFFFB300).copy(alpha = 0.15f),
                            title = "Pasang ekstensi OMP ke Termux",
                            subtitle = "Satu klik: menulis index.ts ke ~/.omp/agent/extensions/agentfeed",
                            onClick = {
                                ompPrepared = runCatching { OmpBridgeInstaller.prepare(context) }
                                    .getOrElse { t ->
                                        Toast.makeText(
                                            context,
                                            "Gagal membaca ekstensi dari APK: ${t.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        null
                                    }
                            }
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                }
            } else {
                // SUB-SETTING DETAILED CATEGORY SCREEN
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(category.accentColor.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = category.icon,
                                    contentDescription = null,
                                    tint = category.accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = category.title,
                                    color = TvTextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = category.subtitle,
                                    color = TvTextSecondary,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    SettingsCategoryDetailContent(
                        category = category,
                        context = context,
                        viewModel = viewModel,
                        strategyMode = strategyMode,
                        onStrategyModeChange = { mode ->
                            // Catatan: sejak slider SWING hidup di dalam kartunya, semua kartu mode
                            // membuka area slider dan baru aktif lewat "Simpan & Aktifkan".
                            // Parameter ini tetap untuk jalur non-kartu (jika ada).
                            strategyMode = mode
                            saved = false
                        },
                        expandedMode = expandedMode,
                        onExpandedChange = { mode ->
                            // Membuka area selalu mengembalikan draft ke config aktif; menutup area
                            // berarti membatalkan (dulu perilaku tombol "Batal" di dialog).
                            when (mode) {
                                StrategyMode.SCALPING -> {
                                    draftScalpingConfig = activeScalpingConfig
                                    draftScalpingPreset = activeScalpingPreset
                                    draftPaperAuto = viewModel.paperAutoTradeConfig.value
                                }
                                StrategyMode.OFFICE_DAILY -> {
                                    draftIntradayConfig = activeIntradayConfig
                                    draftIntradayPreset = activeIntradayPreset
                                }
                                StrategyMode.SWING -> {
                                    draftSwingConfig = activeSwingConfig
                                    draftSwingPreset = activeSwingPreset
                                }
                                else -> Unit
                            }
                            expandedMode = mode
                        },
                        draftScalpingConfig = draftScalpingConfig,
                        draftScalpingPreset = draftScalpingPreset,
                        draftPaperAuto = draftPaperAuto,
                        draftIntradayConfig = draftIntradayConfig,
                        draftIntradayPreset = draftIntradayPreset,
                        onDraftScalpingChange = { cfg, preset ->
                            draftScalpingConfig = cfg
                            draftScalpingPreset = preset
                        },
                        onDraftScalpingPresetChange = { preset ->
                            draftScalpingPreset = preset.name
                            draftScalpingConfig = when (preset) {
                                SensitivityPreset.DEFAULT -> ScalpingRuntimeConfig.DEFAULT
                                SensitivityPreset.STRICT -> ScalpingRuntimeConfig.STRICT
                                SensitivityPreset.LOOSE -> ScalpingRuntimeConfig.LOOSE
                                SensitivityPreset.CUSTOM -> draftScalpingConfig
                            }
                        },
                        onDraftPaperAutoChange = { draftPaperAuto = it },
                        onDraftIntradayChange = { cfg, preset ->
                            draftIntradayConfig = cfg
                            draftIntradayPreset = preset
                        },
                        onDraftIntradayPresetChange = { preset ->
                            draftIntradayPreset = preset.name
                            draftIntradayConfig = when (preset) {
                                IntradaySensitivityPreset.STANDARD -> IntradayRuntimeConfig.DEFAULT
                                IntradaySensitivityPreset.STRICT -> IntradayRuntimeConfig.STRICT
                                IntradaySensitivityPreset.LOOSE -> IntradayRuntimeConfig.LOOSE
                                IntradaySensitivityPreset.CUSTOM -> draftIntradayConfig
                            }
                        },
                        draftSwingConfig = draftSwingConfig,
                        draftSwingPreset = draftSwingPreset,
                        onDraftSwingChange = { cfg, preset ->
                            draftSwingConfig = cfg
                            draftSwingPreset = preset
                        },
                        onDraftSwingPresetChange = { preset ->
                            draftSwingPreset = preset.name
                            draftSwingConfig = when (preset) {
                                SwingSensitivityPreset.STANDARD -> SwingRuntimeConfig.DEFAULT
                                SwingSensitivityPreset.STRICT -> SwingRuntimeConfig.STRICT
                                SwingSensitivityPreset.LOOSE -> SwingRuntimeConfig.LOOSE
                                SwingSensitivityPreset.CUSTOM -> draftSwingConfig
                            }
                        },
                        onSaveScalping = {
                            saveScalpingEngineSettings()
                            expandedMode = null
                        },
                        onSaveIntraday = {
                            saveIntradayEngineSettings()
                            expandedMode = null
                        },
                        onSaveSwing = {
                            saveSwingEngineSettings()
                            expandedMode = null
                        },
                        selectedSource = selectedSource,
                        onSourceChange = { selectedSource = it; saved = false },
                        buyMakerFee = buyMakerFee,
                        onBuyMakerFeeChange = { buyMakerFee = it; saved = false },
                        buyTakerFee = buyTakerFee,
                        onBuyTakerFeeChange = { buyTakerFee = it; saved = false },
                        sellMakerFee = sellMakerFee,
                        onSellMakerFeeChange = { sellMakerFee = it; saved = false },
                        sellTakerFee = sellTakerFee,
                        onSellTakerFeeChange = { sellTakerFee = it; saved = false },
                        completedLessonsCount = completedLessons.size,
                        watchlistPairs = watchlistPairs,
                        dashboardTicks = dashboardTicks,
                        currentThemeStyle = currentThemeStyle,
                        currentAccentPreset = currentAccentPreset,
                        currentCandleStyle = currentCandleStyle,
                        currentAnimationSpeed = currentAnimationSpeed,
                        currentPriceAnimationMode = currentPriceAnimationMode,
                        isPriceTickPulseEnabled = isPriceTickPulseEnabled,
                        isSmoothChartEnabled = isSmoothChartEnabled,
                        priceFeedThrottleMs = priceFeedThrottleMs,
                        isDarkTheme = isDarkTheme,
                        onThrottleChange = { ms ->
                            priceFeedThrottleMs = ms
                            viewModel.setUiPriceThrottleMs(ms)
                            saved = false
                        },
                        isRealBuyMode = isRealBuyMode,
                        hasPin = hasPin,
                        hasApiCredentials = prefs.hasTokocryptoCredentials(),
                        isPinUnlocked = isPinUnlocked,
                        userPublicIp = userPublicIp ?: "Detecting...",
                        failedPinAttempts = failedPinAttempts,
                        onToggleRealBuyMode = {
                            val hasCreds = prefs.hasTokocryptoCredentials()
                            if (!isRealBuyMode) {
                                if (!hasPin || !hasCreds) {
                                    showSetupRealApiDialog = true
                                } else {
                                    pinDialogAction = PinDialogAction.TOGGLE_REAL_BUY
                                    pinDialogError = null
                                    pendingRealBuyToggle = true
                                    showPinDialog = true
                                }
                            } else {
                                viewModel.setRealBuyMode(false, "")
                                Toast.makeText(context, "Mode beralih ke SIMULASI.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onOpenSetupDialog = { showSetupRealApiDialog = true },
                        onRequirePinUnlock = {
                            pinDialogAction = PinDialogAction.UNLOCK_ONLY
                            pinDialogError = null
                            showPinDialog = true
                        },
                        onWipeCredentials = {
                            viewModel.wipeSecurityCredentials()
                            hasPin = false
                            Toast.makeText(context, "Seluruh Kredensial API & PIN berhasil dihapus.", Toast.LENGTH_SHORT).show()
                        },
                        provider = provider,
                        onProviderChange = { provider = it; saved = false },
                        gemini = gemini,
                        onGeminiChange = { gemini = it; saved = false },
                        isNotifyPriceAlertsEnabled = isNotifyPriceAlertsEnabled,
                        isNotifyCandidateBuyEnabled = isNotifyCandidateBuyEnabled,
                        isNotifyTrailingStopEnabled = isNotifyTrailingStopEnabled,
                        isNotifyEmergencyExitEnabled = isNotifyEmergencyExitEnabled,
                        cacheCleared = cacheCleared,
                        onClearCache = { cacheCleared = true },
                        updateRepo = updateRepo,
                        onUpdateRepoChange = { updateRepo = it; saved = false },
                        updateToken = updateToken,
                        onUpdateTokenChange = { updateToken = it; saved = false },
                        releaseInfo = releaseInfo,
                        checkingUpdate = checkingUpdate,
                        updateStatus = updateStatus,
                        downloadProgress = downloadProgress
                    )

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = { saveAllSettings(true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (saved) Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp), tint = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (saved) "Pengaturan Tersimpan" else "Simpan Perubahan",
                            color = Color.Black,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { expandedMode = null; activeCategory = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TvTextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                    ) {
                        Text("Kembali ke Daftar Pengaturan", color = TvTextSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }

    if (ompPrepared != null) {
        val prepared = ompPrepared!!
        AlertDialog(
            onDismissRequest = { ompPrepared = null },
            title = { Text("Pasang ekstensi OMP ke Termux") },
            text = {
                Column {
                    Text(
                        "Memasang index.ts bridge ke folder konfigurasi oh-my-pi di Termux, lalu agen bisa connect ke server aplikasi.",
                        fontSize = 11.sp,
                        color = TvTextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("CARA 1 — otomatis (satu klik)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvGreen)
                    Text(
                        "Butuh allow-external-apps=true di ~/.termux/termux.properties (sekali saja). Bila belum diset, Termux tidak mengeksekusi apa pun — pakai Cara 2.",
                        fontSize = 10.sp,
                        color = TvTextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("CARA 2 — tempel perintah di Termux (tanpa setelan tambahan)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvGreen)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                prepared.command,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                color = TvTextPrimary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Lalu jalankan: omp — agen akan tersambung ke ws://127.0.0.1:8765/ws/signals selama sakelar Agent Bridge V3 aktif.",
                        fontSize = 10.sp,
                        color = TvTextSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("omp-install", prepared.command))
                    val ok = OmpBridgeInstaller.runInTermux(context, prepared.command)
                    Toast.makeText(
                        context,
                        if (ok) "Perintah dikirim ke Termux & disalin ke clipboard" else "Termux tidak terpasang (perintah disalin ke clipboard)",
                        Toast.LENGTH_LONG
                    ).show()
                }) { Text("Jalankan di Termux", color = TvGreen) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("omp-install", prepared.command))
                        Toast.makeText(context, "Perintah pemasangan tersalin", Toast.LENGTH_SHORT).show()
                    }) { Text("Salin perintah", color = TvBlue) }
                    TextButton(onClick = { ompPrepared = null }) { Text("Tutup", color = TvTextSecondary) }
                }
            }
        )
    }

    // DIALOGS
    if (showSetupRealApiDialog) {
        SetupRealApiDialog(
            targetExchange = selectedSource,
            initialApiKey = prefs.tokocryptoApiKey,
            initialSecretKey = prefs.tokocryptoSecretKey,
            userPublicIp = userPublicIp ?: "Detecting...",
            onCheckPublicIp = { viewModel.checkPublicIp() },
            onSaveAndActivate = { pin, apiKey, secretKey ->
                viewModel.saveRealCredentialsAndPin(pin, apiKey, secretKey)
                viewModel.setRealBuyMode(true, pin)
                hasPin = true
                showSetupRealApiDialog = false
                Toast.makeText(context, "Kredensial API & PIN berhasil disimpan! Mode REAL aktif.", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSetupRealApiDialog = false }
        )
    }

    if (showPinDialog) {
        SecurityPinDialog(
            title = if (pinDialogAction == PinDialogAction.TOGGLE_REAL_BUY) "AKTIVASI MODE REAL ${selectedSource.label.uppercase()}" else "BUKA SESI KEAMANAN",
            subtitle = "Masukkan 6-digit PIN Keamanan Anda.",
            errorMessage = pinDialogError,
            failedAttempts = failedPinAttempts,
            onPinSubmitted = { pin ->
                val isValid = viewModel.verifyPin(pin)
                if (isValid) {
                    showPinDialog = false
                    pinDialogError = null
                    if (pinDialogAction == PinDialogAction.TOGGLE_REAL_BUY && pendingRealBuyToggle) {
                        viewModel.setRealBuyMode(true, pin)
                        pendingRealBuyToggle = false
                        Toast.makeText(context, "Mode REAL ${selectedSource.label.uppercase()} AKTIF!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "PIN Terverifikasi", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    pinDialogError = "PIN Salah (${failedPinAttempts + 1}/5 percobaan)"
                }
            },
            onDismiss = {
                showPinDialog = false
                pendingRealBuyToggle = false
                pinDialogError = null
            }
        )
    }
}
