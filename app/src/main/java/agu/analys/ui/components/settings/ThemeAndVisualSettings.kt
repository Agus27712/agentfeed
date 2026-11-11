package agu.analys.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.animation.AnimatedPercentageBadge
import agu.analys.ui.animation.FlipCardPriceText
import agu.analys.ui.animation.PriceAnimationMode
import agu.analys.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun ThemeAndVisualSettings(
    currentThemeStyle: ThemeStyle,
    currentAccentPreset: AccentColorPreset,
    currentCandleStyle: CandleColorStyle,
    currentAnimationSpeed: AnimationSpeed,
    currentPriceAnimMode: PriceAnimationMode,
    isPriceTickPulseEnabled: Boolean,
    isSmoothChartEnabled: Boolean,
    priceFeedThrottleMs: Long,
    isDarkTheme: Boolean = true,
    onDarkThemeChange: (Boolean) -> Unit = {},
    onThemeStyleChange: (ThemeStyle) -> Unit,
    onAccentChange: (AccentColorPreset) -> Unit,
    onCandleStyleChange: (CandleColorStyle) -> Unit,
    onAnimationSpeedChange: (AnimationSpeed) -> Unit,
    onPriceAnimModeChange: (PriceAnimationMode) -> Unit,
    onPriceTickPulseChange: (Boolean) -> Unit,
    onSmoothChartChange: (Boolean) -> Unit,
    onThrottleChange: (Long) -> Unit
) {
    // Interactive Test Price for Live Animation Preview
    var testPrice by remember { mutableDoubleStateOf(1450250000.0) }
    var testPct by remember { mutableDoubleStateOf(3.85) }

    LaunchedEffect(Unit) {
        val samples = listOf(
            Pair(1450250000.0, 3.85),
            Pair(1452800000.0, 4.02),
            Pair(1451100000.0, 3.91),
            Pair(1456900000.0, 4.31),
            Pair(1449000000.0, 3.76)
        )
        var idx = 0
        while (true) {
            delay(2400)
            idx = (idx + 1) % samples.size
            testPrice = samples[idx].first
            testPct = samples[idx].second
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 0. QUICK MODE SWITCH (LIGHT / DARK)
        SectionHeader("MODE TAMPILAN UTAMA (LIGHT / DARK)")
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Light Mode Option
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onDarkThemeChange(false)
                            onThemeStyleChange(ThemeStyle.LIGHT_CLEAN)
                        },
                    color = if (!isDarkTheme || currentThemeStyle == ThemeStyle.LIGHT_CLEAN) TvBlue.copy(alpha = 0.15f) else TvSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (!isDarkTheme || currentThemeStyle == ThemeStyle.LIGHT_CLEAN) TvBlue else TvBorder
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.LightMode,
                            contentDescription = "Mode Terang",
                            tint = if (!isDarkTheme || currentThemeStyle == ThemeStyle.LIGHT_CLEAN) TvBlue else TvTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "Mode Terang",
                                color = if (!isDarkTheme || currentThemeStyle == ThemeStyle.LIGHT_CLEAN) TvBlue else TvTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Light Clean", color = TvTextSecondary, fontSize = 10.sp)
                        }
                    }
                }

                // Dark Mode Option
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onDarkThemeChange(true)
                            if (currentThemeStyle == ThemeStyle.LIGHT_CLEAN) {
                                onThemeStyleChange(ThemeStyle.DARK_NAVY)
                            }
                        },
                    color = if (isDarkTheme && currentThemeStyle != ThemeStyle.LIGHT_CLEAN) TvBlue.copy(alpha = 0.15f) else TvSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (isDarkTheme && currentThemeStyle != ThemeStyle.LIGHT_CLEAN) TvBlue else TvBorder
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.DarkMode,
                            contentDescription = "Mode Gelap",
                            tint = if (isDarkTheme && currentThemeStyle != ThemeStyle.LIGHT_CLEAN) TvBlue else TvTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "Mode Gelap",
                                color = if (isDarkTheme && currentThemeStyle != ThemeStyle.LIGHT_CLEAN) TvBlue else TvTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Navy/Black/Matrix", color = TvTextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 1. PALET TEMA UTAMA (THEME STYLE)
        SectionHeader("PALET TEMA UTAMA (THEME STYLE)")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Pilih suasana latar belakang aplikasi:",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                ThemeStyle.values().forEach { style ->
                    val isSelected = style == currentThemeStyle
                    val sampleColors = createCustomAppColors(themeStyle = style, accent = currentAccentPreset, candleStyle = currentCandleStyle)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) TvBlue.copy(alpha = 0.12f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) TvBlue else TvBorder.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onThemeStyleChange(style) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(sampleColors.background, sampleColors.surfaceVariant, sampleColors.blue)
                                    )
                                )
                                .border(1.5.dp, if (isSelected) TvBlue else Color.Gray.copy(alpha = 0.5f), CircleShape)
                        )

                        Spacer(Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = style.displayName,
                                    color = if (isSelected) TvBlue else TvTextPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                if (isSelected) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(TvBlue.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text("AKTIF", color = TvBlue, fontSize = 8.5.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                            Text(
                                text = style.description,
                                color = TvTextSecondary,
                                fontSize = 10.sp
                            )
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = { onThemeStyleChange(style) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = TvBlue,
                                unselectedColor = TvTextSecondary
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 2. WARNA AKSEN (ACCENT COLOR PALETTE)
        SectionHeader("WARNA AKSEN APLIKASI (ACCENT COLOR)")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Warna aksen tombol, tab aktif, dan highlight data:",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AccentColorPreset.values().forEach { preset ->
                        val isSelected = preset == currentAccentPreset
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) preset.primary.copy(alpha = 0.18f) else TvSurface)
                                .border(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) preset.primary else TvBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onAccentChange(preset) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .background(preset.primary, CircleShape)
                                        .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    text = preset.displayName.substringBefore(" "),
                                    color = if (isSelected) preset.primary else TvTextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 3. WARNA CANDLESTICK / CHART GRAFIK
        SectionHeader("WARNA GRAFIK & CANDLESTICK (BULLISH / BEARISH)")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Pilih skema warna indikator candle naik & turun:",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                CandleColorStyle.values().forEach { candleStyle ->
                    val isSelected = candleStyle == currentCandleStyle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) TvBlue.copy(alpha = 0.12f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) TvBlue else TvBorder.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onCandleStyleChange(candleStyle) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Bullish Pill
                        Box(
                            modifier = Modifier
                                .background(candleStyle.bullish, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("UP ▲", color = Color.Black, fontSize = 9.5.sp, fontWeight = FontWeight.Black)
                        }

                        Spacer(Modifier.width(6.dp))

                        // Bearish Pill
                        Box(
                            modifier = Modifier
                                .background(candleStyle.bearish, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("DOWN ▼", color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Black)
                        }

                        Spacer(Modifier.width(10.dp))

                        Text(
                            text = candleStyle.displayName,
                            color = if (isSelected) TvBlue else TvTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )

                        RadioButton(
                            selected = isSelected,
                            onClick = { onCandleStyleChange(candleStyle) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = TvBlue,
                                unselectedColor = TvTextSecondary
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 4. PILIHAN ANIMASI HARGA & PERSENTASE (HEADER & KARTU)
        SectionHeader("ANIMASI HARGA & PERSENTASE (HEADER & KARTU)")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Live preview banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(TvSurface)
                        .border(1.dp, TvBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("PREVIEW ANIMASI LIVE", color = TvTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            FlipCardPriceText(
                                price = testPrice,
                                color = TvTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                animationMode = currentPriceAnimMode
                            )
                        }
                        AnimatedPercentageBadge(
                            percentage = testPct,
                            animationMode = currentPriceAnimMode
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Pilih gaya animasi angka saat terjadi pembaruan harga realtime:",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))

                PriceAnimationMode.values().forEach { mode ->
                    val isSelected = mode == currentPriceAnimMode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) TvBlue.copy(alpha = 0.12f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) TvBlue else TvBorder.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onPriceAnimModeChange(mode) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(if (isSelected) TvBlue.copy(alpha = 0.2f) else TvSurface, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (mode) {
                                    PriceAnimationMode.DIGIT_FLIP -> Icons.Default.Flip
                                    PriceAnimationMode.PULSE_GLOW -> Icons.Default.FlashOn
                                    PriceAnimationMode.SLIDE_VERTICAL -> Icons.Default.SwapVert
                                    PriceAnimationMode.SMOOTH_INTERPOLATE -> Icons.Default.TrendingUp
                                    PriceAnimationMode.STATIC -> Icons.Default.Stop
                                },
                                contentDescription = null,
                                tint = if (isSelected) TvBlue else TvTextSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.displayName,
                                color = if (isSelected) TvBlue else TvTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = mode.description,
                                color = TvTextSecondary,
                                fontSize = 10.sp
                            )
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = { onPriceAnimModeChange(mode) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = TvBlue,
                                unselectedColor = TvTextSecondary
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 5. KECEPATAN GERAK LAYAR & FITUR MOTION LAINNYA
        SectionHeader("KECEPATAN GERAK & FITUR MOTION (UI SPEED)")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Sesuaikan kecepatan transisi layar antar menu:",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AnimationSpeed.values().forEach { speed ->
                        val isSelected = speed == currentAnimationSpeed
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) TvBlue.copy(alpha = 0.15f) else TvSurface)
                                .border(
                                    1.dp,
                                    if (isSelected) TvBlue else TvBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onAnimationSpeedChange(speed) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = when (speed) {
                                        AnimationSpeed.SMOOTH -> Icons.Default.MotionPhotosAuto
                                        AnimationSpeed.FAST -> Icons.Default.Bolt
                                        AnimationSpeed.REDUCED -> Icons.Default.PowerSettingsNew
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) TvBlue else TvTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = when (speed) {
                                        AnimationSpeed.SMOOTH -> "Halus"
                                        AnimationSpeed.FAST -> "Cepat"
                                        AnimationSpeed.REDUCED -> "Minimal"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) TvBlue else TvTextPrimary
                                )
                                Text(
                                    text = "${speed.durationMs} ms",
                                    fontSize = 9.sp,
                                    color = if (isSelected) TvBlue.copy(alpha = 0.8f) else TvTextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                Spacer(Modifier.height(8.dp))

                // Toggle Price Tick Glow
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPriceTickPulseChange(!isPriceTickPulseEnabled) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Denyut Kerdip Harga Live (Tick Pulse)",
                            color = TvTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Efek kilau hijau/merah lembut saat ada tick harga masuk",
                            color = TvTextSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = isPriceTickPulseEnabled,
                        onCheckedChange = onPriceTickPulseChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = TvGreen,
                            uncheckedThumbColor = TvTextSecondary,
                            uncheckedTrackColor = TvSurface
                        )
                    )
                }

                // Toggle Smooth Chart Interpolation
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSmoothChartChange(!isSmoothChartEnabled) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Interpolasi Garis Chart Halus",
                            color = TvTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Pergerakan kursor dan garis grafik tanpa patah-patah",
                            color = TvTextSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = isSmoothChartEnabled,
                        onCheckedChange = onSmoothChartChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = TvGreen,
                            uncheckedThumbColor = TvTextSecondary,
                            uncheckedTrackColor = TvSurface
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 6. THROTTLING REFRESH UI (VOLATILITY SHIELD)
        SectionHeader("PERFORMA & THROTTLING UI (VOLATILITY SHIELD)")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, null, tint = TvGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Laju Refresh UI & State",
                        color = TvTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Mencegah bottleneck Main Thread dan stuttering animasi Compose saat terjadi lonjakan order/trade ekstrem.",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
                Spacer(Modifier.height(10.dp))

                val throttleOptions = listOf(
                    Triple(100L, "100 ms", "Ultra Cepat"),
                    Triple(200L, "200 ms", "Standar"),
                    Triple(500L, "500 ms", "Hemat Daya"),
                    Triple(0L, "0 ms", "Raw Filter")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    throttleOptions.forEach { (ms, label, desc) ->
                        val isSelected = priceFeedThrottleMs == ms
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) TvGreen.copy(alpha = 0.15f) else TvSurface)
                                .border(
                                    1.dp,
                                    if (isSelected) TvGreen else TvBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { onThrottleChange(ms) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) TvGreen else TvTextPrimary
                                )
                                Text(
                                    text = desc,
                                    fontSize = 8.sp,
                                    color = if (isSelected) TvGreen.copy(alpha = 0.8f) else TvTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
