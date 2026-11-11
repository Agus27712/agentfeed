package agu.analys.ui.components.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.CandleBar
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.trading.SpotPosition
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.util.Locale
import kotlin.math.abs

data class ActiveHoldingItemData(
    val pair: TradingPair,
    val holding: CoinHoldingStatus,
    val position: SpotPosition?,
    val tick: MarketTick?,
    val candles1h: List<CandleBar> = emptyList()
)

/**
 * Section HOLDING AKTIF sesuai gambar mockup:
 * - Header "● HOLDING AKTIF"
 * - Cards: Avatar + Symbol & Nama | Live Price & Pill Change% | Mini Sparkline | Trailing Stop % & ● PnL%
 */
@Composable
fun ActiveHoldingSection(
    items: List<ActiveHoldingItemData>,
    onCoinClick: (TradingPair) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    var isExpanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp)
    ) {
        // Section Header Row: "● HOLDING AKTIF"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(TvCyan)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "HOLDING AKTIF",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }

            IconButton(
                onClick = { isExpanded = !isExpanded },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Sembunyikan Holding" else "Tampilkan Holding",
                    tint = TvTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Body: Content ketika Expanded
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(items, key = { "active_holding_${it.pair.symbol}" }) { item ->
                    ActiveHoldingCard(
                        item = item,
                        onClick = { onCoinClick(item.pair) }
                    )
                }
            }
        }
    }
}

/**
 * Kartu Tunggal Posisi Holding Real:
 * - Avatar bulat di kiri, Symbol bold (cth: XRP) + Nama koin (cth: Ripple)
 * - Harga Live besar (cth: Rp 13.245) + Pill persentase (cth: ▲ 2.35%)
 * - Mini Sparkline chart (hijau / merah)
 * - Trailing Stop 3% / 2% | ● PnL%
 */
@Composable
fun ActiveHoldingCard(
    item: ActiveHoldingItemData,
    onClick: () -> Unit
) {
    val currentPrice = item.tick?.price ?: 0.0
    val entryPrice = when {
        item.holding.entryPrice > 0.0 && item.position != null && item.position.entryPrice > 0.0 -> {
            if (item.pair.quoteAsset.equals("IDR", ignoreCase = true) && item.holding.entryPrice < 1.0 && item.position.entryPrice >= 1.0) item.position.entryPrice
            else item.holding.entryPrice
        }
        item.holding.entryPrice > 0.0 -> item.holding.entryPrice
        item.position != null && item.position.entryPrice > 0.0 -> item.position.entryPrice
        else -> 0.0
    }
    val change24h = item.tick?.change24h ?: 0.0
    // Basis biaya tunggal (2026-10-15): **taker + slippage dua sisi**, sama persis dengan FSM
    // paper trade. Sebelumnya badge ini memakai selisih harga **bruto**, sehingga selalu lebih
    // optimistis daripada angka yang diakui mesin exit untuk posisi yang sama.
    val exchangeName = item.position?.exchange?.takeIf { it.isNotBlank() } ?: "TOKOCRYPTO"
    val pnlPct = if (entryPrice > 0.0 && currentPrice > 0.0) {
        val net = agu.analys.config.FeeSchedule.netProfitPctFor(
            entry = entryPrice,
            price = currentPrice,
            exchange = exchangeName,
            quoteAsset = item.pair.quoteAsset
        )
        if (net.isFinite() && abs(net) < 100000.0) net else change24h
    } else change24h

    val isProfit = pnlPct >= 0.0
    val accentColor = if (isProfit) TvGreen else TvRed
    val pnlSign = if (isProfit) "▲" else "▼"

    val pos = item.position
    val trailingPercent = when {
        pos != null && pos.isTrailingEnabled -> {
            if (pos.activeTrailingPercent > 0.0) pos.activeTrailingPercent else pos.trailingPercent
        }
        item.holding.isTrailingEnabled -> 3.0
        else -> 3.0
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = TvCardBackground,
        border = BorderStroke(1.dp, TvBorder),
        modifier = Modifier
            .width(215.dp)
            .wrapContentHeight()
            .clickable(onClick = onClick)
            .testTag("holding_card_${item.pair.symbol}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Baris 1: Avatar + Symbol & Nama
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AssetAvatar(baseAsset = item.pair.baseAsset, size = 28.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = item.pair.baseAsset.uppercase(),
                        color = TvTextPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${item.pair.baseAsset.uppercase()} / ${item.pair.quoteAsset.uppercase()}",
                        color = TvTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Baris 2: Harga Live besar & Pill PnL%
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (currentPrice > 0.0) PriceFormatter.formatPrice(currentPrice, quoteAsset = item.pair.quoteAsset) else "...",
                    color = TvTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                // Pill Change / PnL (cth: ▲ 2.35% hijau atau ▼ -0.81% merah)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentColor.copy(alpha = 0.16f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$pnlSign ${String.format(Locale.US, "%.2f", abs(pnlPct))}%",
                        color = accentColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Baris 3: Mini Sparkline 1 Jam (SSOT dari chart detail)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
            ) {
                MiniSparkline(
                    tick = item.tick,
                    candles = item.candles1h,
                    lineColor = accentColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Baris 4: Trailing Stop & Dot Floating PnL%
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Trailing Stop ${String.format(Locale.US, "%.0f", trailingPercent)}%",
                    color = TvTextSecondary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${String.format(Locale.US, "%.2f", abs(pnlPct))}%",
                        color = TvTextSecondary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
