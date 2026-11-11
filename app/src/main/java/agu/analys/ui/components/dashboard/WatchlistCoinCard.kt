package agu.analys.ui.components.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.CandleBar
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.ui.animation.AnimatedMetricText
import agu.analys.ui.animation.AnimatedPercentageBadge
import agu.analys.ui.animation.SmoothPriceText
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

/**
 * Compact Watchlist Card:
 * - Rank + Avatar + Symbol
 * - Harga + Trend 24h %
 * - MiniSparkline + Vol 24h
 * - Star favorite
 * Tanpa tombol Buka Chart / Lihat Rekomendasi (klik card = buka detail)
 */
@Composable
fun WatchlistCoinCard(
    pair: TradingPair,
    tick: MarketTick?,
    worth: WorthCoinInfo?,
    rank: Int?,
    isAuto: Boolean,
    isScalping: Boolean,
    isFavorite: Boolean = true,
    badges: List<agu.analys.model.CoinBadge> = emptyList(),
    // 0.0 = rate belum tersedia dari exchange (bukan nilai hardcode)
    usdtIdrRate: Double = 0.0,
    recentCandles: List<CandleBar> = emptyList(),
    holdingStatus: CoinHoldingStatus? = null,
    tradingFees: agu.analys.config.TradingFeeConfig = agu.analys.config.TradingFeeConfig(),
    onToggleFavorite: () -> Unit = {},
    onClick: () -> Unit
) {
    val change = tick?.change24h ?: Double.NaN
    val volume = tick?.volume24h ?: 0.0
    val isUsdt = pair.quoteAsset.equals("USDT", true) || pair.quoteAsset.equals("USD", true)

    val changeColor = when {
        change > 0 -> TvGreen
        change < 0 -> TvRed
        else -> TvTextSecondary
    }

    val volumeScore = if (isUsdt) {
        when {
            volume >= 100_000_000.0 -> 9
            volume >= 20_000_000.0 -> 8
            volume >= 5_000_000.0 -> 7
            volume >= 1_000_000.0 -> 6
            volume >= 200_000.0 -> 5
            volume > 0 -> 4
            else -> 2
        }
    } else {
        when {
            volume >= 100_000_000_000 -> 9
            volume >= 50_000_000_000 -> 8
            volume >= 10_000_000_000 -> 7
            volume >= 1_000_000_000 -> 6
            volume >= 200_000_000 -> 5
            volume > 0 -> 4
            else -> 2
        }
    }

    val activity = when {
        volumeScore >= 8 || (change.isFinite() && change >= 3.0) -> ActivityLevel.HIGH
        volumeScore >= 6 || (change.isFinite() && change >= 0.0) -> ActivityLevel.MEDIUM
        else -> ActivityLevel.LOW
    }

    val rankText = rank?.let { String.format("%02d", it) } ?: "··"
    val sparkColor = if (change >= 0) TvGreen else TvRed

    val colorOrange = TvOrange
    val colorGreen = TvGreen
    val colorRed = TvRed

    val positionContext = remember(holdingStatus, tick, tradingFees) {
        agu.analys.model.PositionContext.create(
            symbol = pair.symbol,
            spotPosition = null,
            holdingStatus = holdingStatus,
            currentPrice = tick?.price ?: 0.0,
            fees = tradingFees
        )
    }
    val isHolding = positionContext.hasPosition && (positionContext.quantity ?: 0.0) > 0.00000001
    val badgeInfo: ReadySellBadge? = remember(positionContext, tick, tradingFees, colorOrange, colorGreen, colorRed) {
        ReadySellBadgeEvaluator.computeReadyBadge(
            context = positionContext,
            tick = tick,
            tradingFees = tradingFees,
            colorOrange = colorOrange,
            colorGreen = colorGreen,
            colorRed = colorRed,
            rsi = null
        )
    }

    val isBuyReady = badges.any { it.type == agu.analys.model.BadgeType.READY }
    val isWorthIt = worth?.isWorthIt == true && worth.potentialProfitPct >= 2.0

    // User constraint P1: "tidak semua koin yang belum dibeli memiliki pulse"
    // HANYA koin yang berstatus READY to buy yang berdenyut (pulse).
    val shouldPulse = if (isHolding) {
        badgeInfo?.isExitDecisionEvent == true
    } else {
        isBuyReady
    }

    val pulseTransition = rememberInfiniteTransition(label = "pulse_card_${pair.symbol}")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha_${pair.symbol}"
    )

    val cardBorder = if (isHolding) {
        if (badgeInfo != null && badgeInfo.isExitDecisionEvent) {
            BorderStroke(1.4.dp, badgeInfo.color.copy(alpha = pulseAlpha))
        } else {
            BorderStroke(1.dp, Color(0xFF2962FF).copy(alpha = 0.45f))
        }
    } else if (isBuyReady) {
        // Sinyal eksekusi BUY aktif: berdenyut (pulse)
        BorderStroke(1.4.dp, TvGreen.copy(alpha = pulseAlpha))
    } else if (isWorthIt) {
        // Layak dianalisa tapi belum ada sinyal konfirmasi READY: aksen border statis tenang tanpa pulse
        BorderStroke(1.dp, TvGreen.copy(alpha = 0.35f))
    } else {
        BorderStroke(1.dp, DashboardColors.Border)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = cardBorder
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .background(TvSurfaceVariant, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = rankText,
                            color = TvAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    AssetAvatar(baseAsset = pair.baseAsset, iconUrl = pair.iconUrl, size = 28.dp)
                    Spacer(Modifier.width(5.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = pair.baseAsset,
                                color = TvTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "/${pair.quoteAsset}",
                                color = TvTextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (badges.isNotEmpty()) {
                                CoinBadgeRow(badges = badges.take(1))
                            } else {
                                CompactActivityChip(activity)
                            }

                            if (badgeInfo != null) {
                                val badgeBgAlpha = if (badgeInfo.isExitDecisionEvent) (0.16f + 0.14f * pulseAlpha) else 0.16f
                                val badgeBorderAlpha = if (badgeInfo.isExitDecisionEvent) pulseAlpha else 0.5f
                                Box(
                                    modifier = Modifier
                                        .background(
                                            badgeInfo.color.copy(alpha = badgeBgAlpha),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            0.8.dp,
                                            badgeInfo.color.copy(alpha = badgeBorderAlpha),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                ) {
                                    Text(
                                        text = badgeInfo.label,
                                        color = badgeInfo.color,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (tick != null && tick.price > 0) {
                            SmoothPriceText(
                                price = tick.price,
                                color = TvTextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Black,
                                quoteAsset = pair.quoteAsset
                            )
                            if (isUsdt && usdtIdrRate > 0) {
                                val idrEst = tick.price * usdtIdrRate
                                Text(
                                    text = "≈ ${PriceFormatter.formatPrice(idrEst, quoteAsset = "IDR")}",
                                    color = TvTextSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        } else {
                            Text("—", color = TvTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        if (change.isFinite()) {
                            AnimatedPercentageBadge(
                                percentage = change,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) TvAmber else TvTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiniSparkline(
                    tick = tick,
                    modifier = Modifier
                        .width(84.dp)
                        .height(34.dp),
                    lineColor = sparkColor
                )

                Spacer(Modifier.width(8.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = when {
                            !change.isFinite() -> "Trend —"
                            change > 0 -> "Trend Naik"
                            change < 0 -> "Trend Turun"
                            else -> "Trend Sideways"
                        },
                        color = changeColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Vol ${PriceFormatter.formatVolume(volume, quoteAsset = pair.quoteAsset)}",
                        color = TvTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (tick != null && tick.high24h > 0 && tick.low24h > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "H ${PriceFormatter.formatPrice(tick.high24h, quoteAsset = pair.quoteAsset)}",
                            color = TvGreen.copy(alpha = 0.85f),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "L ${PriceFormatter.formatPrice(tick.low24h, quoteAsset = pair.quoteAsset)}",
                            color = TvRed.copy(alpha = 0.85f),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private enum class ActivityLevel { HIGH, MEDIUM, LOW, UNKNOWN }

@Composable
private fun CompactActivityChip(activity: ActivityLevel) {
    val (label, background, foreground) = when (activity) {
        ActivityLevel.HIGH -> Triple("Tinggi", TvGreen.copy(alpha = 0.15f), TvGreen)
        ActivityLevel.MEDIUM -> Triple("Sedang", TvAmber.copy(alpha = 0.15f), TvAmber)
        ActivityLevel.LOW -> Triple("Rendah", TvSurfaceVariant, TvTextSecondary)
        ActivityLevel.UNKNOWN -> Triple("—", TvSurfaceVariant, TvTextSecondary)
    }
    Box(
        Modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}