package agu.analys.ui.components.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SimulationOpenOrdersList(
    openOrders: List<SimulationOrder>,
    tradeHistory: List<SimulationTradeHistoryItem>,
    currentSymbol: String,
    onCancelOrder: (String) -> Unit,
    onCancelAllOrders: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showAllCoins by remember { mutableStateOf(false) }

    val filteredOpenOrders = remember(openOrders, showAllCoins, currentSymbol) {
        if (showAllCoins) openOrders else openOrders.filter { it.symbol.equals(currentSymbol, true) }
    }

    val filteredHistory = remember(tradeHistory, showAllCoins, currentSymbol) {
        if (showAllCoins) tradeHistory else tradeHistory.filter { it.symbol.equals(currentSymbol, true) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.clickable { selectedTab = 0 },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Open Orders",
                        color = if (selectedTab == 0) TvTextPrimary else TvTextSecondary,
                        fontSize = 15.sp,
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = TvTextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    if (openOrders.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(TvGreen)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${openOrders.size}",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.clickable { selectedTab = 1 },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Riwayat",
                        tint = if (selectedTab == 1) TvTextPrimary else TvTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Riwayat",
                        color = if (selectedTab == 1) TvTextPrimary else TvTextSecondary,
                        fontSize = 15.sp,
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }

            if (selectedTab == 0 && filteredOpenOrders.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, TvBorder, RoundedCornerShape(4.dp))
                        .clickable { onCancelAllOrders(if (showAllCoins) null else currentSymbol) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Batalkan Semua",
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = showAllCoins,
                onCheckedChange = { showAllCoins = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = TvGreen,
                    uncheckedColor = TvTextSecondary,
                    checkmarkColor = Color.Black
                ),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Tampilkan semua pasangan koin",
                color = TvTextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.clickable { showAllCoins = !showAllCoins }
            )
        }

        Spacer(Modifier.height(10.dp))

        if (selectedTab == 0) {
            if (filteredOpenOrders.isEmpty()) {
                SimulationEmptyStateView(
                    icon = Icons.Default.ReceiptLong,
                    message = "Tidak ada order terbuka saat ini"
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredOpenOrders.forEach { order ->
                        OpenOrderItemCard(
                            order = order,
                            onCancel = { onCancelOrder(order.id) }
                        )
                    }
                }
            }
        } else {
            if (filteredHistory.isEmpty()) {
                SimulationEmptyStateView(
                    icon = Icons.Default.History,
                    message = "Belum ada riwayat transaksi simulasi"
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredHistory.forEach { history ->
                        TradeHistoryItemCard(history = history)
                    }
                }
            }
        }
    }
}
