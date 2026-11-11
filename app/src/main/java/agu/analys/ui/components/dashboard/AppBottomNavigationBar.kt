package agu.analys.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*

enum class NavTab {
    WATCHLIST, PORTOFOLIO, SIMULASI, SETTINGS
}

@Composable
fun AppBottomNavigationBar(
    currentTab: NavTab,
    onSelectTab: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        androidx.compose.material3.HorizontalDivider(
            color = TvBorder,
            thickness = 1.dp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TvSurface)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                label = "Watchlist",
                isSelected = currentTab == NavTab.WATCHLIST,
                onClick = { onSelectTab(NavTab.WATCHLIST) }
            )
            NavItem(
                icon = Icons.Default.AccountBalanceWallet,
                label = "Portofolio",
                isSelected = currentTab == NavTab.PORTOFOLIO,
                onClick = { onSelectTab(NavTab.PORTOFOLIO) }
            )
            NavItem(
                icon = Icons.AutoMirrored.Filled.CompareArrows,
                label = "Simulasi",
                isSelected = currentTab == NavTab.SIMULASI,
                onClick = { onSelectTab(NavTab.SIMULASI) }
            )
            NavItem(
                icon = Icons.Default.Settings,
                label = "Pengaturan",
                isSelected = currentTab == NavTab.SETTINGS,
                onClick = { onSelectTab(NavTab.SETTINGS) }
            )
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) TvGreen else TvTextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = if (isSelected) TvGreen else TvTextSecondary,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
