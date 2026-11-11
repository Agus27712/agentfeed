package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.data.OrderBookDepthCache
import agu.analys.model.AISignalState
import agu.analys.model.CoinBadge
import agu.analys.model.MarketTick
import agu.analys.model.SignalAction
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class FocusSignalType {
    BUY,
    HOLD,
    WATCH,
    SCANNING
}

data class FocusCoinCardData(
    val pair: TradingPair,
    val tick: MarketTick?,
    val worth: WorthCoinInfo?,
    val aiSignal: AISignalState?,
    val badges: List<CoinBadge>,
    val isFavorite: Boolean,
    val maxVolume: Double = 1.0,
    val isTopPicked: Boolean = false
)

@Composable
fun FocusCoinCard(
    data: FocusCoinCardData,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tick = data.tick
    val price = tick?.price ?: 0.0
    val change24h = tick?.change24h ?: 0.0
    val volume = tick?.volume24h ?: 0.0
    val high24h = tick?.high24h ?: 0.0
    val low24h = tick?.low24h ?: 0.0

    // Evaluasi Sinyal & Confidence secara real:
    val (signalType, confidence, _) = remember(data) {
        evaluateSignalReal(data)
    }

    // Format Timestamp "xs ago"
    val timestampText = remember(tick?.timestamp) {
        val ts = tick?.timestamp ?: 0L
        if (ts <= 0L) "10s ago" else formatTimeAgo(ts)
    }

    val isStrongSignal = signalType == FocusSignalType.BUY && confidence >= 60

    val primaryGreen = TvGreen
    val primaryRed = TvRed
    val primaryCyan = TvCyan
    val textSec = TvTextSecondary
    val borderCol = TvBorder
    val cardBg = TvCardBackground

    // Border: Glow Neon Cyan pada kartu BUY utama, border theme pada lainnya
    val cardBorder = if (isStrongSignal || data.isTopPicked) {
        BorderStroke(1.2.dp, primaryCyan)
    } else {
        BorderStroke(1.dp, borderCol)
    }

    val changeColor = when {
        change24h > 0.0 -> primaryGreen
        change24h < 0.0 -> primaryRed
        else -> textSec
    }

    val changeSign = when {
        change24h > 0.0 -> "▲"
        change24h < 0.0 -> "▼"
        else -> ""
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        border = cardBorder,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("focus_card_${data.pair.symbol}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            // ================= Baris 1: Symbol & Nama | Live Price & Change 24h =================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Kiri: Avatar + Symbol & Nama
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    AssetAvatar(baseAsset = data.pair.baseAsset, size = 32.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "${data.pair.baseAsset.uppercase()}/${data.pair.quoteAsset.uppercase()}",
                            color = TvTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = data.pair.displayName.ifBlank { data.pair.baseAsset.uppercase() },
                            color = textSec,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Kanan: Harga Live & Change 24h
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (price > 0.0) PriceFormatter.formatPrice(price, quoteAsset = data.pair.quoteAsset) else "...",
                        color = TvTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$changeSign ${String.format(Locale.US, "%.2f", abs(change24h))}%",
                        color = changeColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ================= Baris 2: High & Low (Kiri) | Volume & Timestamp (Kanan) =================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Kolom Kiri: High & Low 24 Jam
                Column(
                    modifier = Modifier.weight(1.3f, fill = false)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "High: ",
                            color = textSec,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (high24h > 0.0) PriceFormatter.formatPrice(high24h, quoteAsset = data.pair.quoteAsset) else "—",
                            color = primaryGreen,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Low:  ",
                            color = textSec,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (low24h > 0.0) PriceFormatter.formatPrice(low24h, quoteAsset = data.pair.quoteAsset) else "—",
                            color = primaryRed,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                // Kolom Kanan: Volume & Timestamp 🕒
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.weight(0.9f, fill = false)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Vol: ",
                            color = textSec,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = formatCardVolume(volume),
                            color = TvTextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = timestampText,
                            color = textSec,
                            fontSize = 10.5.sp
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = textSec,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }
        }
    }
}

