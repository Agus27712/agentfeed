package agu.analys.ui.components.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.MarketDataSource
import agu.analys.ui.theme.*

/**
 * Modern Header Dashboard:
 * - Kiri: Exchange Selector (Tokocrypto SSOT) dengan dropdown switcher
 * - Kanan: Indikator status Live/Offline + Tombol Refresh + Tombol Diagnostik Log (Terminal)
 */
@Composable
fun DashboardModernHeader(
    marketDataSource: MarketDataSource = MarketDataSource.TOKOCRYPTO,
    isConnected: Boolean,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onOpenLogcat: () -> Unit,
    onOpenSignalLogs: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val statusColor = if (isConnected) TvGreen else TvRed
    val statusText = if (isConnected) "Live" else "Offline"

    val infiniteTransition = rememberInfiniteTransition(label = "header_refresh_spin")
    val spinningRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "header_spin_angle"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TvBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Sisi Kiri: Indikator Exchange Aktif (Pengaturan exchange dipusatkan di Settings)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background((if (marketDataSource == MarketDataSource.TOKOCRYPTO) TvCyan else TvBlue).copy(alpha = 0.15f))
                    .border(1.dp, (if (marketDataSource == MarketDataSource.TOKOCRYPTO) TvCyan else TvBlue).copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ElectricBolt,
                    contentDescription = null,
                    tint = if (marketDataSource == MarketDataSource.TOKOCRYPTO) TvCyan else TvBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = marketDataSource.label,
                    color = TvTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = if (marketDataSource == MarketDataSource.TOKOCRYPTO) "SSOT · Pair IDR/USDT" else "Pasar IDR Spot",
                    color = TvTextSecondary,
                    fontSize = 9.5.sp
                )
            }
        }

        // Sisi Kanan: Status Live + Tombol Refresh + Tombol Logcat Diagnostik
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Status Indicator (Live / Offline)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusColor.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Tombol Refresh di sebelah tombol live
            IconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("btn_header_refresh")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Data Pasar",
                    tint = if (isRefreshing) TvCyan else TvTextPrimary,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer(rotationZ = if (isRefreshing) spinningRotation else 0f)
                )
            }

            // Tombol Signal Log & Evaluasi Reliabilitas (Room DB)
            IconButton(
                onClick = onOpenSignalLogs,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("btn_header_signal_logs")
            ) {
                Icon(
                    imageVector = Icons.Default.Assessment,
                    contentDescription = "Buka Log & Evaluasi Sinyal Room DB",
                    tint = TvCyan,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Tombol Logcat / Diagnostik (Accessible touch target 40dp+)
            IconButton(
                onClick = onOpenLogcat,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("btn_header_logcat")
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Buka Diagnostik Log",
                    tint = TvTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
