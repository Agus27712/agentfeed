package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.MarketTick
import agu.analys.ui.animation.SmoothPriceText
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun VolumeLeaderChip(
    rank: Int,
    tick: MarketTick,
    isWatched: Boolean,
    onOpen: () -> Unit,
    onToggleWatch: () -> Unit
) {
    val base = tick.symbol.removeSuffix("IDR").ifBlank { tick.symbol }
    val rangePct = if (tick.low24h > 0) ((tick.high24h - tick.low24h) / tick.low24h) * 100.0 else 0.0
    val highlight = rank <= 3

    Card(
        modifier = Modifier
            .width(168.dp)
            .clickable { onOpen() }
            .testTag("volume_leader_${tick.symbol}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = BorderStroke(1.dp, if (highlight) TvBlue.copy(alpha = 0.4f) else TvBorder)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(TvBlue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("#" + rank, color = TvBlue, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(base, color = TvTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text(tick.symbol, color = TvTextSecondary, fontSize = 9.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            SmoothPriceText(
                price = tick.price,
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Vol " + PriceFormatter.formatPrice(tick.volume24h),
                color = TvBlue,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (rangePct > 0) {
                Text(
                    "Range " + String.format("%.1f", rangePct) + "%",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f).height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Chart", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvSurface)
                }
                OutlinedButton(
                    onClick = onToggleWatch,
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, if (isWatched) TvAmber.copy(alpha = 0.5f) else TvBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isWatched) TvAmber else TvTextSecondary
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        if (isWatched) Icons.Default.Star else Icons.Default.Add,
                        null,
                        Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
