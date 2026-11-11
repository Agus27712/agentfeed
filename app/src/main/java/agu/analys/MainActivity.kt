package agu.analys

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import agu.analys.model.AppScreen
import agu.analys.ui.animation.LocalPriceAnimationMode
import agu.analys.ui.screens.DashboardScreen
import agu.analys.ui.screens.LandscapeChartScreen
import agu.analys.ui.screens.LearningPathScreen
import agu.analys.ui.screens.PortfolioScreen
import agu.analys.ui.screens.SettingsScreen
import agu.analys.ui.screens.SignalLogScreen
import agu.analys.ui.screens.TradeSimulationScreen
import agu.analys.ui.theme.NadiAgentfeedTheme
import agu.analys.ui.theme.TvBackground
import agu.analys.ui.util.edgeSwipeBack
import agu.analys.viewmodel.*

class MainActivity : ComponentActivity() {
    private val tradingViewModel: TradingViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TradingViewModel(application) as T
        }
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                timber.log.Timber.i("Notification permission granted by user on first launch")
            } else {
                timber.log.Timber.w("Notification permission denied by user")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextProvider.init(applicationContext)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Initialize Notification Channels
        agu.analys.util.AlertNotificationHelper.createNotificationChannels(applicationContext)

        // Prompt for notification permission on Android 13+ upon first install/start
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleIntent(intent)

        setContent {
            val isDarkTheme by tradingViewModel.isDarkTheme.collectAsState()
            val themeStyle by tradingViewModel.themeStyle.collectAsState()
            val accentPreset by tradingViewModel.accentColorPreset.collectAsState()
            val candleStyle by tradingViewModel.candleColorStyle.collectAsState()
            val animSpeed by tradingViewModel.animationSpeed.collectAsState()
            val priceAnimMode by tradingViewModel.priceAnimationMode.collectAsState()

            NadiAgentfeedTheme(
                isDarkTheme = isDarkTheme,
                themeStyle = themeStyle,
                accentPreset = accentPreset,
                candleStyle = candleStyle
            ) {
                CompositionLocalProvider(LocalPriceAnimationMode provides priceAnimMode) {
                    val currentScreen by tradingViewModel.currentScreen.collectAsState()
                val rootModifier = Modifier
                    .fillMaxSize()
                    .background(TvBackground)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .edgeSwipeBack(
                        enabled = currentScreen != AppScreen.DASHBOARD && currentScreen != AppScreen.LANDSCAPE_CHART,
                        onBack = { tradingViewModel.goBack() }
                    )

                BackHandler(enabled = currentScreen != AppScreen.DASHBOARD) {
                    tradingViewModel.goBack()
                }

                val duration = animSpeed.durationMs
                val transitionSpecObj = remember(duration) {
                    if (duration <= 0) {
                        fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
                    } else {
                        (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(duration)) + fadeIn(tween(duration)))
                            .togetherWith(slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(duration)) + fadeOut(tween(duration)))
                    }
                }
                
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = { transitionSpecObj },
                    modifier = rootModifier,
                    label = "screen_transition"
                ) { screen ->
                    when (screen) {
                        AppScreen.DASHBOARD -> DashboardScreen(
                            viewModel = tradingViewModel,
                            onOpenChart = { tradingViewModel.openChart(it) },
                            onOpenSettings = { tradingViewModel.openSettings() }
                        )
                        AppScreen.PORTFOLIO -> PortfolioScreen(
                            viewModel = tradingViewModel,
                            onOpenChart = { tradingViewModel.openChart(it) },
                            onNavigateToSimulation = { tradingViewModel.openSimulation(it) },
                            onOpenSettings = { tradingViewModel.openSettings() },
                            onBack = { tradingViewModel.goBack() }
                        )
                        AppScreen.SIMULATION_TRADE -> TradeSimulationScreen(
                            viewModel = tradingViewModel,
                            onOpenChart = { tradingViewModel.openChart(tradingViewModel.selectedPair.value) },
                            onNavigateToDashboard = { tradingViewModel.goBack() },
                            onOpenSettings = { tradingViewModel.openSettings() }
                        )
                        AppScreen.LANDSCAPE_CHART -> LandscapeChartScreen(
                            viewModel = tradingViewModel,
                            onBack = { tradingViewModel.closeLandscapeChart() }
                        )
                        AppScreen.SETTINGS -> SettingsScreen(
                            viewModel = tradingViewModel,
                            onBack = { tradingViewModel.goBack() }
                        )
                        AppScreen.LEARNING -> LearningPathScreen(
                            viewModel = tradingViewModel,
                            onOpenSettings = { tradingViewModel.openSettings() },
                            onBack = { tradingViewModel.goBack() }
                        )
                        AppScreen.SIGNAL_LOGS -> SignalLogScreen(
                            viewModel = tradingViewModel,
                            onBack = { tradingViewModel.goBack() },
                            onOpenChart = { tradingViewModel.openChart(it) }
                        )
                    }
                }
            }
        }
    }
    }

    override fun onResume() {
        super.onResume()
        tradingViewModel.onAppResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val symbol = intent.getStringExtra(agu.analys.util.AlertNotificationHelper.EXTRA_SYMBOL)
            ?: intent.getStringExtra("EXTRA_SYMBOL")
        val exchange = intent.getStringExtra(agu.analys.util.AlertNotificationHelper.EXTRA_EXCHANGE)
            ?: intent.getStringExtra("EXTRA_EXCHANGE")
            ?: "TOKOCRYPTO"
        val quote = intent.getStringExtra(agu.analys.util.AlertNotificationHelper.EXTRA_QUOTE)
            ?: intent.getStringExtra("EXTRA_QUOTE")
            ?: ""
        
        if (action == "agu.analys.ACTION_EXECUTE_TRAILING_SELL") {
            val limitPrice = intent.getDoubleExtra("EXTRA_LIMIT_PRICE", 0.0)
            val qty = intent.getDoubleExtra("EXTRA_QUANTITY", 0.0)
            val isReal = intent.getBooleanExtra("EXTRA_IS_REAL", false)
            if (symbol != null && limitPrice > 0.0 && qty > 0.0) {
                // Konsumsi aksi lebih dulu agar tidak terpicu ulang saat rotasi layar
                intent.action = null
                val pair = agu.analys.model.TradingPair.fromCustomSymbol(
                    raw = symbol,
                    defaultQuote = if (quote.isNotBlank()) quote else "IDR",
                    exchange = exchange
                )
                tradingViewModel.openChart(pair)
                confirmNotificationSell(symbol, exchange, quote, limitPrice, qty, isReal)
            }
        } else if (!symbol.isNullOrEmpty()) {
            val pair = agu.analys.model.TradingPair.fromCustomSymbol(
                raw = symbol,
                defaultQuote = if (quote.isNotBlank()) quote else "IDR",
                exchange = exchange
            )
            tradingViewModel.openChart(pair)
            intent.removeExtra("EXTRA_SYMBOL")
            intent.removeExtra(agu.analys.util.AlertNotificationHelper.EXTRA_SYMBOL)
        }
    }

    /**
     * Jual dari tombol notifikasi. Mode REAL wajib konfirmasi dulu (order asli ke bursa),
     * dan dikirim ke bursa yang tertera di notifikasi — bukan bursa yang sedang aktif.
     */
    private fun confirmNotificationSell(
        symbol: String,
        exchange: String,
        quote: String,
        limitPrice: Double,
        qty: Double,
        isReal: Boolean
    ) {
        if (!isReal) {
            tradingViewModel.executeTrailingSellLimitOrder(
                symbol = symbol, limitPrice = limitPrice, quantity = qty, isReal = false, exchange = exchange
            )
            return
        }
        val key = agu.analys.model.MarketKey.resolve(symbol, exchange, quote.ifBlank { null })
        val priceStr = agu.analys.util.PriceFormatter.formatPrice(limitPrice, showSymbol = true, quoteAsset = key.quote)
        val qtyStr = java.math.BigDecimal(qty).setScale(8, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()
        android.app.AlertDialog.Builder(this)
            .setTitle("Konfirmasi JUAL REAL")
            .setMessage(
                "Jual $qtyStr ${key.base} (${key.formattedPair()}) di ${key.displayExchange}.\n" +
                    "Harga acuan: $priceStr\n\n" +
                    "Order asli akan dikirim ke bursa."
            )
            .setPositiveButton("JUAL SEKARANG") { _, _ ->
                tradingViewModel.executeTrailingSellLimitOrder(
                    symbol = symbol, limitPrice = limitPrice, quantity = qty, isReal = true, exchange = exchange
                )
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
