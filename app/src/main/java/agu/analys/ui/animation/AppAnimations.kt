package agu.analys.ui.animation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Pilihan Mode Animasi Harga & Metrik:
 * Sesuai tren UI/UX modern Android Jetpack Compose (Flip per digit, Pulse glow, Vertical slide, Counter, Statis).
 */
enum class PriceAnimationMode(
    val displayName: String,
    val description: String
) {
    DIGIT_FLIP(
        displayName = "Flip Per Angka (Rolling Digit)",
        description = "Setiap digit bergerak/berputar 3D vertikal naik/turun saat harga berubah"
    ),
    PULSE_GLOW(
        displayName = "Denyut & Kilau (Pulse & Glow)",
        description = "Skala harga membesar halus dengan kilau warna saat terjadi perubahan harga"
    ),
    SLIDE_VERTICAL(
        displayName = "Slide Vertikal Utuh (Vertical Slide)",
        description = "Angka meluncur vertikal halus ke atas atau ke bawah secara menyeluruh"
    ),
    SMOOTH_INTERPOLATE(
        displayName = "Interpolasi Halus (Smooth Counter)",
        description = "Nilai angka berhitung gradual dan transisi halus menuju harga target"
    ),
    STATIC(
        displayName = "Statis / Tanpa Animasi",
        description = "Pembaruan angka langsung instan tanpa efek gerak (hemat baterai)"
    )
}

val LocalPriceAnimationMode = compositionLocalOf { PriceAnimationMode.DIGIT_FLIP }

object AppAnimations {
    const val FAST_MS = 140
    const val NORMAL_MS = 220
    const val SLOW_MS = 360
    const val PRICE_MS = 160
    const val METRIC_MS = 160
}

@Composable
fun FadeSlideIn(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(AppAnimations.NORMAL_MS)) +
                slideInVertically(tween(AppAnimations.NORMAL_MS)) { it / 8 },
        exit = fadeOut(tween(AppAnimations.FAST_MS)) +
                slideOutVertically(tween(AppAnimations.FAST_MS)) { it / 10 }
    ) { content() }
}

@Composable
fun rememberLivePulseAlpha(min: Float = 0.55f, max: Float = 1f): Float {
    val transition = rememberInfiniteTransition(label = "live_pulse")
    val alpha by transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_pulse_alpha"
    )
    return alpha
}

fun Modifier.livePulse(alpha: Float): Modifier =
    this.graphicsLayer { this.alpha = alpha }

/**
 * Smooth numerical price interpolation
 */
@Composable
fun rememberSmoothPrice(target: Double, durationMs: Int = AppAnimations.PRICE_MS): Double {
    val progress = remember { Animatable(1f) }
    var startPrice by remember { mutableDoubleStateOf(target) }
    var previousTarget by remember { mutableDoubleStateOf(target) }

    val progressValue = progress.value.coerceIn(0f, 1f)
    val displayed = startPrice + (target - startPrice) * progressValue.toDouble()

    LaunchedEffect(target) {
        if (!target.isFinite() || target <= 0.0) return@LaunchedEffect

        if (target != previousTarget) {
            val currentDisplayed = startPrice + (previousTarget - startPrice) * progress.value.toDouble()
            startPrice = if (currentDisplayed.isFinite() && currentDisplayed > 0.0) currentDisplayed else previousTarget
            previousTarget = target
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMs.coerceIn(180, 400),
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    return displayed
}

@Composable
fun SmoothPriceText(
    price: Double,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier,
    showSymbol: Boolean = true,
    quoteAsset: String = "IDR",
    animationMode: PriceAnimationMode = LocalPriceAnimationMode.current,
    maxLines: Int = 1
) {
    FlipCardPriceText(
        price = price,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        modifier = modifier,
        showSymbol = showSymbol,
        quoteAsset = quoteAsset,
        animationMode = animationMode,
        maxLines = maxLines
    )
}

/**
 * Modern Jetpack Compose Character-Level Rolling Digit / 3D Flip
 */
@Composable
private fun FlipDigitChar(
    char: Char,
    isUp: Boolean,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight
) {
    if (char.isDigit()) {
        AnimatedContent(
            targetState = char,
            transitionSpec = {
                if (isUp) {
                    (slideInVertically(tween(180, easing = FastOutSlowInEasing)) { height -> height } +
                            fadeIn(tween(140)))
                        .togetherWith(
                            slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { height -> -height } +
                                    fadeOut(tween(140))
                        )
                } else {
                    (slideInVertically(tween(180, easing = FastOutSlowInEasing)) { height -> -height } +
                            fadeIn(tween(140)))
                        .togetherWith(
                            slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { height -> height } +
                                    fadeOut(tween(140))
                        )
                }
            },
            label = "flip_digit_$char"
        ) { targetChar ->
            Text(
                text = targetChar.toString(),
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                maxLines = 1
            )
        }
    } else {
        Text(
            text = char.toString(),
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1
        )
    }
}

/**
 * Universal Modern Price Display Composable
 * Menerapkan animasi pilihan pengguna (FLIP_PER_DIGIT, PULSE_GLOW, SLIDE_VERTICAL, SMOOTH_INTERPOLATE, STATIC)
 */
@Composable
fun FlipCardPriceText(
    price: Double,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier,
    showSymbol: Boolean = true,
    quoteAsset: String = "IDR",
    animationMode: PriceAnimationMode = LocalPriceAnimationMode.current,
    maxLines: Int = 1
) {
    if (!price.isFinite() || price <= 0.0) {
        val placeholder = if (quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)) "$ —" else "Rp —"
        Text(
            text = placeholder,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            modifier = modifier,
            maxLines = maxLines
        )
        return
    }

    var previousPrice by remember { mutableDoubleStateOf(price) }
    var isUp by remember { mutableStateOf(true) }

    val scaleAnim = remember { Animatable(1.0f) }
    val glowAnim = remember { Animatable(0f) }

    LaunchedEffect(price) {
        if (price != previousPrice) {
            isUp = price >= previousPrice
            previousPrice = price
            if (animationMode == PriceAnimationMode.PULSE_GLOW) {
                launch {
                    scaleAnim.snapTo(1.06f)
                    scaleAnim.animateTo(1.0f, tween(220, easing = FastOutSlowInEasing))
                }
                launch {
                    glowAnim.snapTo(0.6f)
                    glowAnim.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    val formatted = remember(price, showSymbol, quoteAsset) {
        PriceFormatter.formatPrice(price, showSymbol, quoteAsset)
    }

    when (animationMode) {
        PriceAnimationMode.DIGIT_FLIP -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Renders character by character with individual animated rolling wheels for digits
                formatted.forEachIndexed { index, ch ->
                    key(index) {
                        FlipDigitChar(
                            char = ch,
                            isUp = isUp,
                            color = color,
                            fontSize = fontSize,
                            fontWeight = fontWeight
                        )
                    }
                }
            }
        }

        PriceAnimationMode.PULSE_GLOW -> {
            val glowColor = if (isUp) TvGreen else TvRed
            Box(
                modifier = modifier
                    .scale(scaleAnim.value)
                    .graphicsLayer {
                        shadowElevation = glowAnim.value * 8f
                    }
            ) {
                Text(
                    text = formatted,
                    color = if (glowAnim.value > 0.05f) {
                        glowColor
                    } else {
                        color
                    },
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    maxLines = maxLines
                )
            }
        }

        PriceAnimationMode.SLIDE_VERTICAL -> {
            AnimatedContent(
                targetState = formatted,
                modifier = modifier,
                transitionSpec = {
                    if (isUp) {
                        (slideInVertically(tween(AppAnimations.PRICE_MS)) { it } + fadeIn(tween(AppAnimations.PRICE_MS)))
                            .togetherWith(slideOutVertically(tween(AppAnimations.PRICE_MS)) { -it } + fadeOut(tween(AppAnimations.PRICE_MS)))
                    } else {
                        (slideInVertically(tween(AppAnimations.PRICE_MS)) { -it } + fadeIn(tween(AppAnimations.PRICE_MS)))
                            .togetherWith(slideOutVertically(tween(AppAnimations.PRICE_MS)) { it } + fadeOut(tween(AppAnimations.PRICE_MS)))
                    }
                },
                label = "slide_vertical_price"
            ) { text ->
                Text(
                    text = text,
                    color = color,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    maxLines = maxLines
                )
            }
        }

        PriceAnimationMode.SMOOTH_INTERPOLATE -> {
            val smooth = rememberSmoothPrice(price)
            Text(
                text = PriceFormatter.formatPrice(smooth, showSymbol = showSymbol, quoteAsset = quoteAsset),
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                modifier = modifier,
                maxLines = maxLines
            )
        }

        PriceAnimationMode.STATIC -> {
            Text(
                text = formatted,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                modifier = modifier,
                maxLines = maxLines
            )
        }
    }
}

/**
 * Modern Animated Percentage Badge dengan Arrow Indicator dan Dynamic Motion
 */
@Composable
fun AnimatedPercentageBadge(
    percentage: Double,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    animationMode: PriceAnimationMode = LocalPriceAnimationMode.current
) {
    val isPositive = percentage >= 0
    val color = if (isPositive) TvGreen else TvRed
    val formatted = PriceFormatter.formatPercentage(percentage)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isPositive) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size((fontSize.value * 0.9f).dp)
            )
            Spacer(Modifier.width(2.dp))

            when (animationMode) {
                PriceAnimationMode.DIGIT_FLIP -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        formatted.forEachIndexed { idx, ch ->
                            key("pct-$idx") {
                                FlipDigitChar(
                                    char = ch,
                                    isUp = isPositive,
                                    color = color,
                                    fontSize = fontSize,
                                    fontWeight = fontWeight
                                )
                            }
                        }
                    }
                }
                PriceAnimationMode.SLIDE_VERTICAL -> {
                    AnimatedContent(
                        targetState = formatted,
                        transitionSpec = {
                            (slideInVertically(tween(AppAnimations.FAST_MS)) { if (isPositive) it else -it } + fadeIn(tween(AppAnimations.FAST_MS)))
                                .togetherWith(slideOutVertically(tween(AppAnimations.FAST_MS)) { if (isPositive) -it else it } + fadeOut(tween(AppAnimations.FAST_MS)))
                        },
                        label = "animated_percentage"
                    ) { text ->
                        Text(
                            text = text,
                            color = color,
                            fontSize = fontSize,
                            fontWeight = fontWeight,
                            maxLines = 1
                        )
                    }
                }
                else -> {
                    Text(
                        text = formatted,
                        color = color,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun AnimatedMetricText(
    value: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(AppAnimations.METRIC_MS)) togetherWith
                    fadeOut(tween(AppAnimations.FAST_MS))
        },
        label = "live_metric"
    ) { animatedValue ->
        Text(
            text = animatedValue,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = maxLines
        )
    }
}
