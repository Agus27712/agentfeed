package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*

enum class RealPortfolioTab(val title: String) {
    ASSETS("Aset"),
    OPEN_ORDERS("Antrean"),
    HISTORY("Riwayat")
}

@Composable
fun RealPortfolioTabSelector(
    selectedTab: RealPortfolioTab,
    onTabSelected: (RealPortfolioTab) -> Unit,
    assetsCount: Int,
    ordersCount: Int,
    tradesCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RealPortfolioTab.entries.forEach { tab ->
            val isSelected = selectedTab == tab
            val countText = when (tab) {
                RealPortfolioTab.ASSETS -> if (assetsCount > 0) " ($assetsCount)" else ""
                RealPortfolioTab.OPEN_ORDERS -> if (ordersCount > 0) " ($ordersCount)" else ""
                RealPortfolioTab.HISTORY -> if (tradesCount > 0) " ($tradesCount)" else ""
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) TvCardBackground else Color.Transparent)
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${tab.title}$countText",
                    color = if (isSelected) TvBlue else TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
