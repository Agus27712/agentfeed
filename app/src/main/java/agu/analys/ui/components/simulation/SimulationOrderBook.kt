package agu.analys.ui.components.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.OrderBookItem
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun SimulationOrderBook(
    bids: List<OrderBookItem>,
    asks: List<OrderBookItem>,
    currentPrice: Double,
    isPriceUp: Boolean,
    quoteAsset: String = "IDR",
    onSelectPrice: (Double) -> Unit,
    onViewMore: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val displayAsks = asks.take(5).reversed()
    val displayBids = bids.take(5)

    val maxAskVol = (displayAsks.maxOfOrNull { it.amount } ?: 1.0).coerceAtLeast(0.001)
    val maxBidVol = (displayBids.maxOfOrNull { it.amount } ?: 1.0).coerceAtLeast(0.001)
    val maxVol = maxOf(maxAskVol, maxBidVol)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Harga ($quoteAsset)",
                color = TvTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Nilai ($quoteAsset)",
                color = TvTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (displayAsks.isEmpty()) {
                repeat(5) { EmptyOrderBookRow(color = TvRed) }
            } else {
                displayAsks.forEach { item ->
                    OrderBookRow(
                        price = item.price,
                        amount = item.amount * item.price,
                        fillRatio = (item.amount / maxVol).toFloat().coerceIn(0.05f, 1f),
                        color = TvRed,
                        quoteAsset = quoteAsset,
                        onClick = { onSelectPrice(item.price) }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(TvSurfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = if (currentPrice > 0.0) PriceFormatter.formatPrice(currentPrice, quoteAsset = quoteAsset) else "-",
                color = if (isPriceUp) TvGreen else TvRed,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (displayBids.isEmpty()) {
                repeat(5) { EmptyOrderBookRow(color = TvGreen) }
            } else {
                displayBids.forEach { item ->
                    OrderBookRow(
                        price = item.price,
                        amount = item.amount * item.price,
                        fillRatio = (item.amount / maxVol).toFloat().coerceIn(0.05f, 1f),
                        color = TvGreen,
                        quoteAsset = quoteAsset,
                        onClick = { onSelectPrice(item.price) }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.clickable { onViewMore() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Lebih banyak", color = TvTextSecondary, fontSize = 11.sp)
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Lebih banyak",
                    tint = TvTextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.GridView,
                contentDescription = "Depth Mode",
                tint = TvTextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun OrderBookRow(
    price: Double,
    amount: Double,
    fillRatio: Float,
    color: Color,
    quoteAsset: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(3.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(fillRatio)
                .background(color.copy(alpha = 0.16f))
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = PriceFormatter.formatPrice(price, showSymbol = false, quoteAsset = quoteAsset),
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = formatAmountShort(amount, quoteAsset),
                color = TvTextPrimary,
                fontSize = 11.sp,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun EmptyOrderBookRow(color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "—", color = color.copy(alpha = 0.4f), fontSize = 11.sp)
        Text(text = "—", color = Color.Gray.copy(alpha = 0.4f), fontSize = 11.sp)
    }
}

private fun formatAmountShort(amount: Double, quoteAsset: String): String {
    val isUsdt = quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)
    return when {
        amount >= 1_000_000_000 -> String.format("%.2fB", amount / 1_000_000_000)
        amount >= 1_000_000 -> if (isUsdt) String.format("%.2fM", amount / 1_000_000) else String.format("%.2fJt", amount / 1_000_000)
        amount >= 1_000 -> if (isUsdt) String.format("%.1fK", amount / 1_000) else String.format("%.1fRb", amount / 1_000)
        isUsdt -> String.format("%.2f", amount)
        else -> String.format("%.0f", amount)
    }
}
