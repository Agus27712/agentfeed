package agu.analys.ui.components.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.AiProvider
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.model.MarketTick
import agu.analys.ui.animation.PriceAnimationMode
import agu.analys.ui.theme.*
import agu.analys.util.GitHubReleaseInfo
import agu.analys.viewmodel.*

@Composable
fun SettingsCategoryDetailContent(
    category: SettingsCategory,
    context: Context,
    viewModel: TradingViewModel,
    strategyMode: StrategyMode,
    onStrategyModeChange: (StrategyMode) -> Unit,
    expandedMode: StrategyMode?,
    onExpandedChange: (StrategyMode?) -> Unit,
    draftScalpingConfig: agu.analys.engine.scalping.ScalpingRuntimeConfig,
    draftScalpingPreset: String,
    draftPaperAuto: agu.analys.engine.scalping.PaperAutoTradeConfig,
    draftIntradayConfig: agu.analys.engine.intraday.IntradayConfig,
    draftIntradayPreset: String,
    draftSwingConfig: agu.analys.engine.swing.SwingConfig,
    draftSwingPreset: String,
    onDraftScalpingChange: (agu.analys.engine.scalping.ScalpingRuntimeConfig, String) -> Unit,
    onDraftScalpingPresetChange: (agu.analys.engine.scalping.SensitivityPreset) -> Unit,
    onDraftPaperAutoChange: (agu.analys.engine.scalping.PaperAutoTradeConfig) -> Unit,
    onDraftIntradayChange: (agu.analys.engine.intraday.IntradayConfig, String) -> Unit,
    onDraftIntradayPresetChange: (agu.analys.engine.intraday.IntradaySensitivityPreset) -> Unit,
    onDraftSwingChange: (agu.analys.engine.swing.SwingConfig, String) -> Unit,
    onDraftSwingPresetChange: (agu.analys.engine.swing.SwingSensitivityPreset) -> Unit,
    onSaveScalping: () -> Unit,
    onSaveIntraday: () -> Unit,
    onSaveSwing: () -> Unit,
    selectedSource: MarketDataSource,
    onSourceChange: (MarketDataSource) -> Unit,
    buyMakerFee: String,
    onBuyMakerFeeChange: (String) -> Unit,
    buyTakerFee: String,
    onBuyTakerFeeChange: (String) -> Unit,
    sellMakerFee: String,
    onSellMakerFeeChange: (String) -> Unit,
    sellTakerFee: String,
    onSellTakerFeeChange: (String) -> Unit,
    completedLessonsCount: Int,
    watchlistPairs: Set<String>,
    dashboardTicks: Map<String, MarketTick>,
    currentThemeStyle: ThemeStyle,
    currentAccentPreset: AccentColorPreset,
    currentCandleStyle: CandleColorStyle,
    currentAnimationSpeed: AnimationSpeed,
    currentPriceAnimationMode: PriceAnimationMode,
    isPriceTickPulseEnabled: Boolean,
    isSmoothChartEnabled: Boolean,
    priceFeedThrottleMs: Long,
    isDarkTheme: Boolean,
    onThrottleChange: (Long) -> Unit,
    isRealBuyMode: Boolean,
    hasPin: Boolean,
    hasApiCredentials: Boolean,
    isPinUnlocked: Boolean,
    userPublicIp: String,
    failedPinAttempts: Int,
    onToggleRealBuyMode: () -> Unit,
    onOpenSetupDialog: () -> Unit,
    onRequirePinUnlock: () -> Unit,
    onWipeCredentials: () -> Unit,
    provider: AiProvider,
    onProviderChange: (AiProvider) -> Unit,
    gemini: String,
    onGeminiChange: (String) -> Unit,
    isNotifyPriceAlertsEnabled: Boolean,
    isNotifyCandidateBuyEnabled: Boolean,
    isNotifyTrailingStopEnabled: Boolean,
    isNotifyEmergencyExitEnabled: Boolean,
    cacheCleared: Boolean,
    onClearCache: () -> Unit,
    updateRepo: String,
    onUpdateRepoChange: (String) -> Unit,
    updateToken: String,
    onUpdateTokenChange: (String) -> Unit,
    releaseInfo: GitHubReleaseInfo?,
    checkingUpdate: Boolean,
    updateStatus: String?,
    downloadProgress: Int?
) {
    val scalpingConfig by viewModel.scalpingRuntimeConfig.collectAsStateWithLifecycle()
    val intradayConfig by viewModel.intradayRuntimeConfig.collectAsStateWithLifecycle()
    val swingConfig by viewModel.swingRuntimeConfig.collectAsStateWithLifecycle()

    when (category) {
        SettingsCategory.TRADING -> {
            TradingModeSettings(
                strategyMode = strategyMode,
                scalpingConfig = scalpingConfig,
                intradayConfig = intradayConfig,
                swingConfig = swingConfig,
                draftScalpingConfig = draftScalpingConfig,
                draftScalpingPreset = draftScalpingPreset,
                draftPaperAuto = draftPaperAuto,
                draftIntradayConfig = draftIntradayConfig,
                draftIntradayPreset = draftIntradayPreset,
                draftSwingConfig = draftSwingConfig,
                draftSwingPreset = draftSwingPreset,
                expandedMode = expandedMode,
                onExpandedChange = onExpandedChange,
                onStrategyChange = onStrategyModeChange,
                onDraftScalpingChange = onDraftScalpingChange,
                onDraftScalpingPresetChange = onDraftScalpingPresetChange,
                onDraftPaperAutoChange = onDraftPaperAutoChange,
                onDraftIntradayChange = onDraftIntradayChange,
                onDraftIntradayPresetChange = onDraftIntradayPresetChange,
                onDraftSwingChange = onDraftSwingChange,
                onDraftSwingPresetChange = onDraftSwingPresetChange,
                onSaveScalping = onSaveScalping,
                onSaveIntraday = onSaveIntraday,
                onSaveSwing = onSaveSwing
            )

            Spacer(Modifier.height(14.dp))

            SectionHeader("PILIH SUMBER DATA PASAR (EXCHANGE)")
            Text(
                "Satu-satunya kontrol pemilihan bursa pasar. Ketika Anda beralih bursa dan menekan tombol 'Simpan Perubahan' di bawah, seluruh jalur koneksi bursa aktif akan diputus secara otomatis (hard-stop) dan cache dibersihkan secara total.",
                color = TvTextSecondary,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MarketDataSource.values().forEach { source ->
                    val isSelected = source == selectedSource
                    val isToko = source == MarketDataSource.TOKOCRYPTO
                    val accent = if (isToko) TvCyan else TvBlue

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSourceChange(source) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) accent.copy(alpha = 0.12f) else TvCardBackground
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) accent else TvBorder
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = source.label.uppercase(),
                                        color = if (isSelected) TvTextPrimary else TvTextSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    if (isToko) {
                                        Box(
                                            modifier = Modifier
                                                .background(TvGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("SSOT UTAMA", color = TvGreen, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .background(TvBlue.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("IDR SPOT", color = TvBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .background(if (isSelected) accent else Color.Transparent, CircleShape)
                                        .border(1.5.dp, if (isSelected) accent else TvTextSecondary.copy(alpha = 0.5f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = source.description,
                                color = TvTextSecondary,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            TradingFeeSettings(
                buyMaker = buyMakerFee,
                buyTaker = buyTakerFee,
                sellMaker = sellMakerFee,
                sellTaker = sellTakerFee,
                onBuyMakerChange = onBuyMakerFeeChange,
                onBuyTakerChange = onBuyTakerFeeChange,
                onSellMakerChange = onSellMakerFeeChange,
                onSellTakerChange = onSellTakerFeeChange
            )

            Spacer(Modifier.height(14.dp))

            SectionHeader("MODUL EDUKASI & ANALISIS")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openLearning() },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(TvBlue.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = TvBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "MODE BELAJAR ANALISIS PASAR",
                            color = TvBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "11 Materi: Candlestick, Support & Resistance, Structure HH/HL, Indikator, Risk Management.",
                            color = TvTextPrimary,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Progress: $completedLessonsCount/11 Materi selesai · Ketuk untuk membuka",
                            color = TvGreen,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = TvBlue, modifier = Modifier.size(20.dp))
                }
            }
        }

        SettingsCategory.WATCHLIST -> {
            WatchlistManagerSettings(
                currentWatchlist = watchlistPairs,
                dashboardTicks = dashboardTicks,
                marketDataSource = selectedSource,
                onAddPair = { sym -> viewModel.addToWatchlist(sym) },
                onRemovePair = { sym -> viewModel.removeFromWatchlist(sym) },
                onApplyPreset = { preset -> viewModel.applyWatchlistPreset(preset, selectedSource) },
                onClearAll = { viewModel.setCustomWatchlist(listOf(if (selectedSource == MarketDataSource.TOKOCRYPTO) "BTCUSDT" else "BTCIDR")) }
            )
        }

        SettingsCategory.APPEARANCE -> {
            ThemeAndVisualSettings(
                currentThemeStyle = currentThemeStyle,
                currentAccentPreset = currentAccentPreset,
                currentCandleStyle = currentCandleStyle,
                currentAnimationSpeed = currentAnimationSpeed,
                currentPriceAnimMode = currentPriceAnimationMode,
                isPriceTickPulseEnabled = isPriceTickPulseEnabled,
                isSmoothChartEnabled = isSmoothChartEnabled,
                priceFeedThrottleMs = priceFeedThrottleMs,
                isDarkTheme = isDarkTheme,
                onDarkThemeChange = { isDark -> viewModel.setDarkTheme(isDark) },
                onThemeStyleChange = { style -> viewModel.setThemeStyle(style) },
                onAccentChange = { preset -> viewModel.setAccentColorPreset(preset) },
                onCandleStyleChange = { candle -> viewModel.setCandleColorStyle(candle) },
                onAnimationSpeedChange = { speed -> viewModel.setAnimationSpeed(speed) },
                onPriceAnimModeChange = { mode -> viewModel.setPriceAnimationMode(mode) },
                onPriceTickPulseChange = { pulse -> viewModel.setPriceTickPulseEnabled(pulse) },
                onSmoothChartChange = { smooth -> viewModel.setSmoothChartEnabled(smooth) },
                onThrottleChange = onThrottleChange
            )
        }

        SettingsCategory.SECURITY -> {
            SectionHeader("KEAMANAN & EKSEKUSI ${selectedSource.label.uppercase()}")
            RealBuyModeAndSecurityCard(
                isRealBuyMode = isRealBuyMode,
                hasPin = hasPin,
                hasApiCredentials = hasApiCredentials,
                isPinUnlocked = isPinUnlocked,
                userPublicIp = userPublicIp,
                failedPinAttempts = failedPinAttempts,
                marketDataSource = selectedSource,
                onToggleRealBuyMode = onToggleRealBuyMode,
                onOpenSetupDialog = onOpenSetupDialog,
                onRequirePinUnlock = onRequirePinUnlock,
                onWipeCredentials = onWipeCredentials,
                onCheckPublicIp = { viewModel.checkPublicIp() }
            )
        }

        SettingsCategory.AI_ASSISTANT -> {
            AiAssistantSettings(
                geminiKey = gemini,
                onKeyChange = onGeminiChange
            )
        }

        SettingsCategory.NOTIFICATIONS -> {
            SectionHeader("PREFERENSI NOTIFIKASI APLIKASI")
            Text(
                text = "Aktifkan atau matikan notifikasi spesifik di bawah ini agar Anda hanya menerima informasi peringatan yang Anda inginkan.",
                color = TvTextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
            ) {
                Column {
                    AndroidPreferenceSwitchItem(
                        icon = Icons.Default.NotificationsActive,
                        iconTint = Color(0xFFFF7043),
                        iconBackground = Color(0xFFFF7043).copy(alpha = 0.15f),
                        title = "Trading & Price Alerts",
                        subtitle = "Notifikasi harga target, sinyal strategi, dan trailing stop loss",
                        checked = isNotifyPriceAlertsEnabled,
                        onCheckedChange = { viewModel.setNotifyPriceAlertsEnabled(it) }
                    )
                    HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                    AndroidPreferenceSwitchItem(
                        icon = Icons.Default.NotificationsActive,
                        iconTint = Color(0xFFFF7043),
                        iconBackground = Color(0xFFFF7043).copy(alpha = 0.15f),
                        title = "Notifikasi Pair Ready & Sinyal Buy",
                        subtitle = "Sinyal koin kandidat strategi yang siap entry / buy",
                        checked = isNotifyCandidateBuyEnabled,
                        onCheckedChange = { viewModel.setNotifyCandidateBuyEnabled(it) }
                    )
                    HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                    AndroidPreferenceSwitchItem(
                        icon = Icons.Default.TrendingUp,
                        iconTint = Color(0xFFFF7043),
                        iconBackground = Color(0xFFFF7043).copy(alpha = 0.15f),
                        title = "Notifikasi Trailing Stop & Eksekusi",
                        subtitle = "Sinyal kenaikan trailing profit dan eksekusi jual otomatis saat reversal",
                        checked = isNotifyTrailingStopEnabled,
                        onCheckedChange = { viewModel.setNotifyTrailingStopEnabled(it) }
                    )
                    HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                    AndroidPreferenceSwitchItem(
                        icon = Icons.Default.WarningAmber,
                        iconTint = TvRed,
                        iconBackground = TvRed.copy(alpha = 0.15f),
                        title = "Notifikasi Exit Darurat & Stop Loss",
                        subtitle = "Peringatan darurat saat Stop Loss tersentuh atau terjadi Flash Dump / penurunan drastis",
                        checked = isNotifyEmergencyExitEnabled,
                        onCheckedChange = { viewModel.setNotifyEmergencyExitEnabled(it) }
                    )
                }
            }
        }

        SettingsCategory.SYSTEM -> {
            SectionHeader("PEMELIHARAAN & UPDATE")
            AppMaintenanceCard(
                context = context,
                cacheCleared = cacheCleared,
                onClearCache = onClearCache,
                updateRepo = updateRepo,
                onUpdateRepoChange = onUpdateRepoChange,
                updateToken = updateToken,
                onUpdateTokenChange = onUpdateTokenChange,
                releaseInfo = releaseInfo,
                checkingUpdate = checkingUpdate,
                updateStatus = updateStatus,
                downloadProgress = downloadProgress,
                onCheckUpdate = { viewModel.checkGitHubUpdate(context, updateRepo, updateToken) },
                onDownloadAndInstall = { viewModel.downloadAndInstallUpdate(context, updateRepo, updateToken) }
            )
        }
    }
}
