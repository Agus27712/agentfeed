package agu.analys.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*

enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accentColor: Color
) {
    TRADING(
        title = "Strategi & Trading",
        subtitle = "Mode sinyal, sensitivitas scalping, sumber pasar & fee transaksi",
        icon = Icons.Default.TrendingUp,
        accentColor = Color(0xFF3B82F6)
    ),
    WATCHLIST(
        title = "Pair Watchlist & Koin",
        subtitle = "Kustomisasi daftar pantau, cari koin, preset Top 10, Scalping & AI",
        icon = Icons.Default.FormatListBulleted,
        accentColor = Color(0xFF00BCD4)
    ),
    APPEARANCE(
        title = "Tampilan, Tema & Animasi",
        subtitle = "Palet tema (AMOLED/Dark/Light), warna aksen, candle & kecepatan animasi",
        icon = Icons.Default.Palette,
        accentColor = Color(0xFFFF9800)
    ),
    SECURITY(
        title = "Keamanan & Kredensial API",
        subtitle = "Mode Beli Real, PIN keamanan, API Key/Secret & IP Whitelist",
        icon = Icons.Default.Shield,
        accentColor = Color(0xFFEF4444)
    ),
    AI_ASSISTANT(
        title = "AI Assistant & Engine",
        subtitle = "Konfigurasi engine AI Google Gemini",
        icon = Icons.Default.SmartToy,
        accentColor = Color(0xFF9C27B0)
    ),
    NOTIFICATIONS(
        title = "Kategori Notifikasi Sistem",
        subtitle = "Konfigurasi suara, getaran, lencana, & prioritas saluran sistem",
        icon = Icons.Default.Notifications,
        accentColor = Color(0xFFFF7043)
    ),
    SYSTEM(
        title = "Sistem, Logcat & Pemeliharaan",
        subtitle = "Logcat diagnostik, pembersihan cache & update rilis GitHub",
        icon = Icons.Default.Settings,
        accentColor = Color(0xFF10B981)
    )
}

enum class PinDialogAction {
    TOGGLE_REAL_BUY,
    UNLOCK_ONLY
}

@Composable
fun AndroidSettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = TvTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun AndroidPreferenceItem(
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconBackground, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                color = TvTextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TvTextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun AndroidPreferenceSwitchItem(
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconBackground, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                color = TvTextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = TvGreen,
                uncheckedThumbColor = TvTextSecondary,
                uncheckedTrackColor = TvSurface
            )
        )
    }
}
