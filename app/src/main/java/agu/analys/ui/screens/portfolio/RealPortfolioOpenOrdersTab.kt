package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.database.RealOpenOrderEntity
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

fun LazyListScope.realPortfolioOpenOrdersSection(
    realOpenOrders: List<RealOpenOrderEntity>,
    onCancelRealOrder: (String, String) -> Unit
) {
    if (realOpenOrders.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvCardBackground, RoundedCornerShape(10.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Tidak ada antrean order aktif.",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    } else {
        items(realOpenOrders, key = { "real_order_${it.orderId}_${it.symbol}" }) { order ->
            RealOpenOrderItemCard(
                order = order,
                onCancel = { onCancelRealOrder(order.symbol, order.orderId) }
            )
        }
    }
}

@Composable
fun RealOpenOrderItemCard(
    order: RealOpenOrderEntity,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val orderSymbolUpper = order.symbol.uppercase()
    val orderSideColor = if (order.side.equals("BUY", true)) TvGreen else TvRed
    val quoteAsset = when {
        orderSymbolUpper.endsWith("USDT") || orderSymbolUpper.contains("_USDT") -> "USDT"
        orderSymbolUpper.endsWith("BIDR") || orderSymbolUpper.contains("_BIDR") -> "BIDR"
        else -> "IDR"
    }
    val baseAsset = orderSymbolUpper
        .replace("USDT", "")
        .replace("BIDR", "")
        .replace("IDR", "")
        .replace("_", "")
        .replace("/", "")
        .ifEmpty { orderSymbolUpper }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = orderSideColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = order.side,
                            color = orderSideColor,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$baseAsset / $quoteAsset",
                        color = TvTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Harga: ${PriceFormatter.formatPrice(order.price, quoteAsset = quoteAsset)}",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
                Text(
                    text = "Jumlah: ${PriceFormatter.formatRawDecimal(order.quantity)} $baseAsset | Executed: ${PriceFormatter.formatRawDecimal(order.executedQty)}",
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )
            }

            // Tombol Cancel Order Real
            Button(
                onClick = onCancel,
                modifier = Modifier.height(34.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TvRed),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Batalkan Order",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Cancel",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
