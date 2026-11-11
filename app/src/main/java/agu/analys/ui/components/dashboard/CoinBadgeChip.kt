package agu.analys.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.BadgeType
import agu.analys.model.CoinBadge
import agu.analys.ui.theme.*

/**
 * Reusable Chip Component untuk menampilkan Badge Koin dengan style konsisten Material 3.
 */
@Composable
fun CoinBadgeChip(
    badge: CoinBadge,
    modifier: Modifier = Modifier
) {
    val (bgColor, borderColor, textColor) = getBadgeColors(badge.type)

    Box(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .border(0.6.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.5.dp, vertical = 1.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = badge.label,
            color = textColor,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

/**
 * Row untuk merender multiple badges secara rapi.
 */
@Composable
fun CoinBadgeRow(
    badges: List<CoinBadge>,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(4.dp)
) {
    if (badges.isEmpty()) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement
    ) {
        badges.forEach { badge ->
            CoinBadgeChip(badge = badge)
        }
    }
}

@Composable
fun getBadgeColors(type: BadgeType): Triple<Color, Color, Color> {
    return when (type) {
        BadgeType.INTRADAY -> Triple(
            Color(0xFF4F46E5).copy(alpha = 0.18f),
            Color(0xFF818CF8).copy(alpha = 0.6f),
            Color(0xFFA5B4FC)
        )
        BadgeType.SECONDWAVE -> Triple(
            Color(0xFF0891B2).copy(alpha = 0.18f),
            Color(0xFF06B6D4).copy(alpha = 0.6f),
            Color(0xFF67E8F9)
        )
        BadgeType.SWING -> Triple(
            Color(0xFF2563EB).copy(alpha = 0.18f),
            Color(0xFF3B82F6).copy(alpha = 0.6f),
            Color(0xFF93C5FD)
        )
        BadgeType.SCALPING -> Triple(
            Color(0xFF059669).copy(alpha = 0.18f),
            Color(0xFF10B981).copy(alpha = 0.6f),
            Color(0xFF6EE7B7)
        )
        BadgeType.TRENCHING -> Triple(
            Color(0xFFD97706).copy(alpha = 0.18f),
            Color(0xFFF59E0B).copy(alpha = 0.6f),
            Color(0xFFFCD34D)
        )
        BadgeType.READY -> Triple(
            Color(0xFF16A34A).copy(alpha = 0.22f),
            Color(0xFF22C55E).copy(alpha = 0.8f),
            Color(0xFF86EFAC)
        )
        BadgeType.HOT -> Triple(
            Color(0xFFEA580C).copy(alpha = 0.18f),
            Color(0xFFF97316).copy(alpha = 0.6f),
            Color(0xFFFDBA74)
        )
        BadgeType.PUMP -> Triple(
            Color(0xFF15803D).copy(alpha = 0.18f),
            Color(0xFF22C55E).copy(alpha = 0.6f),
            Color(0xFF86EFAC)
        )
        BadgeType.DUMP -> Triple(
            Color(0xFFDC2626).copy(alpha = 0.18f),
            Color(0xFFEF4444).copy(alpha = 0.6f),
            Color(0xFFFCA5A5)
        )
        BadgeType.VOL24 -> Triple(
            Color(0xFF7C3AED).copy(alpha = 0.18f),
            Color(0xFF8B5CF6).copy(alpha = 0.6f),
            Color(0xFFC4B5FD)
        )
    }
}
