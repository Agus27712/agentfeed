package agu.analys.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.AISignalState
import agu.analys.model.MarketTick
import agu.analys.model.SignalAction
import agu.analys.ui.theme.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Capsule Orderbook Pressure sesuai mockup
 */
@Composable
fun OrderbookPressureCapsule(
    pressure: Int,
    modifier: Modifier = Modifier
) {
    val cyanColor = TvCyan
    val redColor = TvRed
    val textSec = TvTextSecondary
    val surfaceVar = TvSurfaceVariant

    val (barColor, textColor, textVal) = when {
        pressure > 0 -> Triple(cyanColor, cyanColor, "+$pressure%")
        pressure < 0 -> Triple(redColor, redColor, "$pressure%")
        else -> Triple(cyanColor.copy(alpha = 0.7f), textSec, "0%")
    }

    val absPressure = abs(pressure)
    val fillRatio = (absPressure / 100f).coerceIn(0.12f, 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(surfaceVar)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = fillRatio)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(barColor)
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = textVal,
            color = textColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Segmented Progress Indicator (8 kotak kecil horizontal berdampingan)
 */
@Composable
fun SegmentedConfidenceIndicator(
    confidence: Int,
    activeColor: Color,
    totalSegments: Int = 8,
    modifier: Modifier = Modifier
) {
    val inactiveColor = TvSurfaceVariant
    val activeCount = if (confidence <= 0) 0 else {
        ((confidence / 100f) * totalSegments).roundToInt().coerceIn(1, totalSegments)
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        for (i in 0 until totalSegments) {
            val isActive = i < activeCount
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(5.5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isActive) activeColor else inactiveColor)
            )
        }
    }
}

/**
 * Mini Volume Histogram Bars
 */
@Composable
fun MiniVolumeHistogram(
    symbol: String,
    volume: Double,
    maxVolume: Double,
    color: Color,
    modifier: Modifier = Modifier
) {
    val barHeights = remember(symbol, volume, maxVolume) {
        val seed = abs(symbol.hashCode())
        val ratio = if (maxVolume > 0) (volume / maxVolume).coerceIn(0.15, 1.0) else 0.5
        listOf(
            (4 + ((seed % 5) * ratio * 2)).coerceIn(3.0, 16.0).dp,
            (6 + (((seed / 3) % 7) * ratio * 2)).coerceIn(3.0, 16.0).dp,
            (8 + (((seed / 7) % 6) * ratio * 2)).coerceIn(4.0, 16.0).dp,
            (5 + (((seed / 11) % 8) * ratio * 2)).coerceIn(3.0, 16.0).dp,
            (10 + (((seed / 13) % 5) * ratio * 2)).coerceIn(5.0, 16.0).dp,
            (7 + (((seed / 17) % 7) * ratio * 2)).coerceIn(4.0, 16.0).dp,
            (11 + (((seed / 19) % 5) * ratio * 2)).coerceIn(6.0, 16.0).dp
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier.height(16.dp)
    ) {
        barHeights.forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
                    .background(color)
            )
        }
    }
}

fun formatCardVolume(volume: Double): String {
    val absVol = abs(volume)
    return when {
        absVol >= 1_000_000_000_000.0 -> String.format(Locale.US, "%.2fT", volume / 1_000_000_000_000.0)
        absVol >= 1_000_000_000.0 -> String.format(Locale.US, "%.2fB", volume / 1_000_000_000.0)
        absVol >= 1_000_000.0 -> String.format(Locale.US, "%.2fM", volume / 1_000_000.0)
        absVol >= 1_000.0 -> String.format(Locale.US, "%.2fK", volume / 1_000.0)
        absVol > 0.0 -> String.format(Locale.US, "%.0f", volume)
        else -> "0"
    }
}

fun formatTimeAgo(timestamp: Long): String {
    val diffSec = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        diffSec < 5 -> "just now"
        diffSec < 60 -> "${diffSec}s ago"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        else -> "${diffSec / 3600}h ago"
    }
}

fun formatTechnicalReason(
    aiSignal: AISignalState?,
    pressure: Int,
    change24h: Double,
    signalType: FocusSignalType,
    rawReasons: String
): String {
    if (aiSignal != null && aiSignal.reasoning.isNotEmpty()) {
        val clean = aiSignal.reasoning.filter { !it.contains("⚠️") && !it.contains("Tertahan") }
        if (clean.size >= 2) {
            return clean.take(3).joinToString(" • ")
        }
    }

    val mtfPart = when (signalType) {
        FocusSignalType.BUY -> "MTF aligned"
        FocusSignalType.WATCH -> if (change24h < 0) "MTF mixed" else "MTF retesting"
        FocusSignalType.SCANNING -> "MTF neutral"
        FocusSignalType.HOLD -> "MTF consolidation"
    }

    val obPart = when {
        pressure >= 40 -> "OB buy pressure"
        pressure >= 10 -> "OB buy support"
        pressure in -10..9 -> "OB balanced"
        pressure in -35..-11 -> "OB sell resistance"
        else -> "OB sell wall"
    }

    val rsiPart = when {
        signalType == FocusSignalType.BUY && change24h > 0 -> "RSI reclaim"
        signalType == FocusSignalType.BUY -> "RSI bullish"
        signalType == FocusSignalType.WATCH && change24h < 0 -> "RSI cooling"
        signalType == FocusSignalType.WATCH -> "RSI consolidation"
        else -> if (abs(change24h) < 1.5) "RSI midrange" else "RSI momentum"
    }

    return "$mtfPart • $obPart • $rsiPart"
}

fun deriveEstimatedPressure(
    tick: MarketTick?,
    signalType: FocusSignalType,
    confidence: Int
): Int {
    if (tick == null) return if (signalType == FocusSignalType.BUY) 62 else if (signalType == FocusSignalType.WATCH) -38 else 8

    val change = tick.change24h
    return when {
        signalType == FocusSignalType.BUY -> ((confidence * 0.8) + (change * 4)).roundToInt().coerceIn(25, 92)
        signalType == FocusSignalType.WATCH && change < 0 -> ((change * 15) - 10).roundToInt().coerceIn(-85, -15)
        signalType == FocusSignalType.WATCH -> ((change * 8) + 12).roundToInt().coerceIn(-40, 40)
        else -> (change * 5).roundToInt().coerceIn(-25, 25)
    }
}

data class FocusSignalBadgeStyle(
    val signalColor: Color,
    val badgeBgColor: Color,
    val badgeBorderColor: Color,
    val badgeLabel: String
)

fun evaluateSignalReal(data: FocusCoinCardData): Triple<FocusSignalType, Int, String> {
    val aiSignal = data.aiSignal
    val worth = data.worth
    val tick = data.tick
    val badges = data.badges

    if (aiSignal != null) {
        val type = when (aiSignal.action) {
            SignalAction.BUY -> FocusSignalType.BUY
            SignalAction.SELL -> FocusSignalType.WATCH
            SignalAction.HOLD -> {
                if (aiSignal.confidence == 0) FocusSignalType.HOLD
                else if (aiSignal.confidence >= 50) FocusSignalType.WATCH
                else FocusSignalType.HOLD
            }
        }
        val reasons = if (aiSignal.reasoning.isNotEmpty()) {
            aiSignal.reasoning.take(2).joinToString(" • ")
        } else {
            when {
                aiSignal.confidence == 0 -> "Menunggu konfirmasi setup (0/4)"
                aiSignal.action == SignalAction.HOLD -> "Hold • Menunggu trigger"
                else -> "Setup engine aktif"
            }
        }
        return Triple(type, aiSignal.confidence, reasons)
    }

    val score = worth?.worthScore ?: run {
        val chg = tick?.change24h ?: 0.0
        val vol = tick?.volume24h ?: 0.0
        val volPart = if (vol >= 10_000_000_000.0) 30 else if (vol >= 1_000_000_000.0) 20 else 10
        val momPart = if (chg >= 5.0) 40 else if (chg > 0.0) 25 else 10
        (volPart + momPart).coerceIn(10, 95)
    }

    val change = tick?.change24h ?: 0.0

    // Jujur: tanpa sinyal engine (pair tidak sedang dipilih), jangan tampilkan WATCH/BUY
    // seolah-olah sinyal engine. SCANNING = heuristik momentum, bukan sinyal.
    val signalType = FocusSignalType.SCANNING

    val tagList = mutableListOf<String>()
    badges.firstOrNull()?.let { tagList.add(it.description) }
    if (change >= 3.0) tagList.add("Momentum naik")
    if (score >= 60) tagList.add("Volatil aktif")
    if (tagList.isEmpty()) {
        tagList.add(if (change >= 0.0) "Trend positif" else "Tekanan pasar")
    }

    val reasons = "Estimasi • " + tagList.take(3).joinToString(" • ")
    return Triple(signalType, score, reasons)
}
