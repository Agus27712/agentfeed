package agu.analys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.OrderBookItem
import agu.analys.model.TradeStreamItem
import agu.analys.ui.theme.TvCardBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter

@Composable
fun OrderBookAndTradesPanel(
    bids: List<OrderBookItem>,
    asks: List<OrderBookItem>,
    tradeStream: List<TradeStreamItem>,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground)
    ) {
        Column(
            modifier = Modifier
                .padding(18.dp)
                .fillMaxWidth()
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = TvTextPrimary,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = TvGreen,
                            height = 3.dp
                        )
                    }
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("KEDALAMAN ORDER BOOK", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("STREAM TRANSAKSI", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (selectedTab == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("HARGA ($quoteAsset)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
                    Text("JUMLAH COIN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
                    Text("TOTAL QTY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text("Harga = $quoteAsset · Jumlah/Total = kuantitas coin", fontSize = 9.sp, color = TvTextSecondary)
                Spacer(modifier = Modifier.height(8.dp))

                asks.take(4).asReversed().forEach { ask ->
                    OrderBookRow(item = ask, color = TvRed, quoteAsset = quoteAsset)
                }

                Spacer(modifier = Modifier.height(6.dp))

                bids.take(4).forEach { bid ->
                    OrderBookRow(item = bid, color = TvGreen, quoteAsset = quoteAsset)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("WAKTU", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
                    Text("HARGA ($quoteAsset)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
                    Text("JUMLAH COIN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text("Harga transaksi berasal dari pasar live", fontSize = 9.sp, color = TvTextSecondary)
                Spacer(modifier = Modifier.height(8.dp))

                tradeStream.take(8).forEach { trade ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(trade.timeFormatted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TvTextSecondary)
                        Text(
                            text = PriceFormatter.formatPrice(trade.price, quoteAsset = quoteAsset),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (trade.isBuy) TvGreen else TvRed
                        )
                        Text(
                            text = PriceFormatter.formatIndicatorVal(trade.amount, 3),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TvTextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderBookRow(item: OrderBookItem, color: Color, quoteAsset: String = "IDR") {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        val maxTotal = 15.0
        val fillWidthPercent = (item.total / maxTotal).coerceIn(0.05, 1.0)

        Box(
            modifier = Modifier
                .fillMaxWidth(fillWidthPercent.toFloat())
                .height(20.dp)
                .align(Alignment.CenterEnd)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.12f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = PriceFormatter.formatPrice(item.price, quoteAsset = quoteAsset),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color
            )
            Text(
                text = PriceFormatter.formatIndicatorVal(item.amount, 3),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TvTextPrimary
            )
            Text(
                text = PriceFormatter.formatIndicatorVal(item.total, 3),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TvTextSecondary
            )
        }
    }
}