package agu.analys.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import agu.analys.config.MarketDataSource
import agu.analys.model.MarketConnectionState
import agu.analys.model.Timeframe
import agu.analys.ui.components.MarketEmptyOrErrorState
import agu.analys.ui.components.chart.LightweightChartView
import agu.analys.ui.theme.*
import agu.analys.viewmodel.*

/** Timeframe yang ditampilkan di layar penuh (M5 ikut, tidak ada di chip detail). */
private val FULLSCREEN_TIMEFRAMES = listOf(
    Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1
)

@Composable
fun LandscapeChartScreen(
    viewModel: TradingViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val pair by viewModel.selectedPair.collectAsState()
    val timeframe by viewModel.selectedTimeframe.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val signal by viewModel.aiSignalState.collectAsState()
    val candles by viewModel.recentCandles.collectAsState()
    val tick by viewModel.currentTick.collectAsState()

    // State indikator layar penuh (Tokocrypto). Default sama dengan chart detail.
    var showVolume by remember { mutableStateOf(true) }
    var showEma by remember { mutableStateOf(true) }
    var showBb by remember { mutableStateOf(false) }
    var showStochRsi by remember { mutableStateOf(false) }
    // Naik saat pengguna menekan "Fit". Mengubah resetKey → chart boleh memasang
    // ulang visible range. Tanpa ini tombol Fit tidak punya efek apa pun.
    var viewEpoch by remember { mutableStateOf(0) }

    // Kunci identitas dataset. Selama nilai ini sama, tick harga live TIDAK boleh
    // me-reset scroll/zoom. Ganti pair atau timeframe → fit ulang memang diinginkan.
    val chartResetKey = "${pair.symbol}|${timeframe.label}|$viewEpoch"

    // Force landscape + hide System UI (status bar + nav bar)
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                val controller = WindowInsetsControllerCompat(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
                @Suppress("DEPRECATION")
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
    }

    BackHandler { onBack() }

    val isConnected = connectionState is MarketConnectionState.Connected

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when (val state = connectionState) {
            is MarketConnectionState.ConnectionLost -> MarketEmptyOrErrorState(
                false, true, state.title, state.reason,
                { viewModel.retryConnection() }, Modifier.fillMaxSize()
            )
            is MarketConnectionState.Loading -> MarketEmptyOrErrorState(
                true, false, onRetry = { viewModel.retryConnection() },
                modifier = Modifier.fillMaxSize()
            )
            is MarketConnectionState.Connected -> {
                // Candle server Tokocrypto, indikator & level sama dengan chart detail.
                LightweightChartView(
                    candles = candles,
                    currentPrice = tick?.price ?: 0.0,
                    showVolume = showVolume,
                    showEma = showEma,
                    showBb = showBb,
                    showStochRsi = showStochRsi,
                    entryPrice = signal.entryPrice,
                    targetPrice1 = signal.targetPrice1,
                    targetPrice2 = signal.targetPrice2,
                    stopLoss = signal.stopLoss,
                    resetKey = chartResetKey,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Identitas pair + harga berjalan. Tanpa ini user tidak tahu koin apa
        // yang sedang dianalisa setelah berpindah dari dashboard.
        if (isConnected) {
            FullscreenPairInfo(
                pairLabel = "${pair.baseAsset}/${pair.quoteAsset}",
                displayName = pair.displayName,
                price = tick?.price ?: 0.0,
                change24h = tick?.change24h ?: 0.0,
                timeframeLabel = timeframe.label,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 12.dp)
            )
        }

        // Toolbar timeframe & indikator saat chart tampil.
        if (isConnected) {
            LandscapeChartToolbar(
                selectedTimeframe = timeframe,
                onSelectTimeframe = { viewModel.selectTimeframe(it) },
                showVolume = showVolume,
                onToggleVolume = { showVolume = !showVolume },
                showEma = showEma,
                onToggleEma = { showEma = !showEma },
                showBb = showBb,
                onToggleBb = { showBb = !showBb },
                showStochRsi = showStochRsi,
                onToggleStochRsi = { showStochRsi = !showStochRsi },
                onFit = { viewEpoch++ },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp, start = 64.dp, end = 12.dp)
            )
        }

        // Floating Back Button Overlay
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.65f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Kembali ke Dashboard",
                tint = Color.White
            )
        }
    }
}

/** Label pair + harga di pojok kiri bawah, agar konteks analisa tidak hilang. */
@Composable
private fun FullscreenPairInfo(
    pairLabel: String,
    displayName: String,
    price: Double,
    change24h: Double,
    timeframeLabel: String,
    modifier: Modifier = Modifier
) {
    val naik = change24h >= 0.0
    val warna = if (naik) TvGreen else TvRed
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pairLabel,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = timeframeLabel,
                color = TvCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (displayName.isNotBlank() && displayName != pairLabel) {
            Text(text = displayName, color = TvTextSecondary, fontSize = 10.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (price > 0.0) formatHargaRingkas(price) else "—",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (naik) "+%.2f%%".format(change24h) else "%.2f%%".format(change24h),
                color = warna,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Format harga ringkas: koin murah butuh banyak desimal, IDR cukup tanpa desimal. */
private fun formatHargaRingkas(price: Double): String = when {
    price >= 10_000.0 -> "%,.0f".format(price)
    price >= 100.0 -> "%,.2f".format(price)
    price >= 1.0 -> "%,.4f".format(price)
    price >= 0.01 -> "%,.6f".format(price)
    else -> "%,.8f".format(price)
}

@Composable
private fun LandscapeChartToolbar(
    selectedTimeframe: Timeframe,
    onSelectTimeframe: (Timeframe) -> Unit,
    showVolume: Boolean,
    onToggleVolume: () -> Unit,
    showEma: Boolean,
    onToggleEma: () -> Unit,
    showBb: Boolean,
    onToggleBb: () -> Unit,
    showStochRsi: Boolean,
    onToggleStochRsi: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = Color.Transparent,
        labelColor = TvTextSecondary,
        selectedContainerColor = TvBlue.copy(alpha = 0.25f),
        selectedLabelColor = TvBlue
    )

    @Composable
    fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
            colors = chipColors,
            border = FilterChipDefaults.filterChipBorder(
                borderColor = TvBorder,
                enabled = true,
                selected = selected
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.height(28.dp)
        )
    }

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FULLSCREEN_TIMEFRAMES.forEach { tf ->
            ToolChip(tf.label, selectedTimeframe == tf) { onSelectTimeframe(tf) }
        }
        ToolChip("Vol", showVolume, onToggleVolume)
        ToolChip("EMA", showEma, onToggleEma)
        ToolChip("BB", showBb, onToggleBb)
        ToolChip("StochRSI", showStochRsi, onToggleStochRsi)
        // PULIHKAN TAMPILAN: satu-satunya cara sengaja mengembalikan zoom/scroll
        // ke 60 candle terakhir. Tick harga tidak pernah melakukan ini.
        ToolChip("Fit", false, onFit)
    }
}
