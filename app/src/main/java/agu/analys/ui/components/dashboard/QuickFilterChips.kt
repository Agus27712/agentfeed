package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*

enum class DashboardQuickFilter {
    ALL,
    STRONG_SIGNAL,
    HOLDING,
    WATCHLIST
}

/**
 * Quick Filter Chips sesuai gambar mockup:
 * [Semua] [Signal Kuat ★] [🔒 Holding] [☆ Watchlist] [>]
 */
@Composable
fun QuickFilterChips(
    selectedFilter: DashboardQuickFilter,
    onSelectFilter: (DashboardQuickFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. "Semua"
        FilterChipItem(
            label = "Semua",
            icon = null,
            isSelected = selectedFilter == DashboardQuickFilter.ALL,
            onClick = { onSelectFilter(DashboardQuickFilter.ALL) },
            testTag = "filter_all"
        )

        // 2. "Signal Kuat ★" (Solid Cyan saat aktif sesuai gambar)
        FilterChipItem(
            label = "Signal Kuat",
            icon = Icons.Default.Star,
            isSelected = selectedFilter == DashboardQuickFilter.STRONG_SIGNAL,
            onClick = { onSelectFilter(DashboardQuickFilter.STRONG_SIGNAL) },
            activeBgColor = TvCyan,
            activeTextColor = TvBackground,
            testTag = "filter_strong_signal"
        )

        // 3. "🔒 Holding"
        FilterChipItem(
            label = "Holding",
            icon = Icons.Default.Lock,
            isSelected = selectedFilter == DashboardQuickFilter.HOLDING,
            onClick = { onSelectFilter(DashboardQuickFilter.HOLDING) },
            activeBgColor = TvGreen,
            activeTextColor = TvBackground,
            testTag = "filter_holding"
        )

        // 4. "☆ Watchlist"
        FilterChipItem(
            label = "Watchlist",
            icon = Icons.Default.StarBorder,
            isSelected = selectedFilter == DashboardQuickFilter.WATCHLIST,
            onClick = { onSelectFilter(DashboardQuickFilter.WATCHLIST) },
            activeBgColor = TvOrange,
            activeTextColor = TvBackground,
            testTag = "filter_watchlist"
        )

        // 5. Chevron Right ">"
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = TvCardBackground,
            border = BorderStroke(1.dp, TvBorder),
            modifier = Modifier.size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Lainnya",
                    tint = TvTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun FilterChipItem(
    label: String,
    icon: ImageVector?,
    isSelected: Boolean,
    onClick: () -> Unit,
    activeBgColor: Color = TvCyan,
    activeTextColor: Color = TvBackground,
    testTag: String = ""
) {
    val bgColor = if (isSelected) activeBgColor else TvCardBackground
    val contentColor = if (isSelected) activeTextColor else TvTextSecondary
    val border = if (isSelected) null else BorderStroke(1.dp, TvBorder)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = bgColor,
        border = border,
        modifier = Modifier.testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (icon != null && isSelected && label != "Signal Kuat") {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
            } else if (icon != null && !isSelected) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
            }

            Text(
                text = label,
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )

            // Khusus "Signal Kuat ★", bintang di sebelah kanan teks persis gambar
            if (label == "Signal Kuat" && icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
