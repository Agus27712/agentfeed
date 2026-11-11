package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.TradingPair
import agu.analys.ui.components.dashboard.AssetAvatar
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun RealPortfolioAssetItem(
    coinUpper: String,
    qty: Double,
    freeQty: Double,
    lockedQty: Double,
    estVal: Double,
    price: Double,
    avgPrice: Double,
    pnlIdr: Double,
    pnlPct: Double,
    quoteAsset: String = "IDR",
    isTokocrypto: Boolean = false,
    tradingPair: TradingPair? = null,
    onEditAvgBuyPrice: (coin: String, newAvgPrice: Double, newInvested: Double) -> Unit,
    onSelectPair: (TradingPair) -> Unit,
    onOpenChart: (TradingPair) -> Unit,
    modifier: Modifier = Modifier
) {
    var showEditDialog by remember { mutableStateOf(false) }
    val effectivePair = tradingPair ?: TradingPair.fromCustomSymbol(
        if (isTokocrypto || quoteAsset == "USDT") "${coinUpper}USDT" else "${coinUpper}IDR",
        quoteAsset
    )
    val pnlColor = if (pnlIdr > 0) TvGreen else if (pnlIdr < 0) TvRed else TvTextSecondary
    val pnlPrefix = if (pnlIdr > 0) "+" else ""

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssetAvatar(baseAsset = coinUpper, size = 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(coinUpper, color = TvTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Total: $qty $coinUpper", color = TvTextSecondary, fontSize = 11.sp)
                        Row {
                            Text("Tersedia: $freeQty", color = TvGreen, fontSize = 9.5.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Terkunci: $lockedQty", color = TvRed, fontSize = 9.5.sp)
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        PriceFormatter.formatPrice(estVal, quoteAsset = "IDR"),
                        color = TvTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (avgPrice > 0.0) {
                        Text(
                            "$pnlPrefix${PriceFormatter.formatPrice(pnlIdr, quoteAsset = "IDR")} ($pnlPrefix${String.format("%.2f", pnlPct)}%)",
                            color = pnlColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            if (price > 0) "@ ${PriceFormatter.formatPrice(price, quoteAsset = quoteAsset)}" else "Ticker Menunggu",
                            color = TvBlue,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Rata-rata Harga Beli", color = TvTextSecondary, fontSize = 9.sp)
                    Text(
                        if (avgPrice > 0.0) PriceFormatter.formatPrice(avgPrice, quoteAsset = quoteAsset) else "Belum Diset",
                        color = if (avgPrice > 0.0) TvTextPrimary else TvAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Harga Live", color = TvTextSecondary, fontSize = 9.sp)
                        Text(
                            if (price > 0) PriceFormatter.formatPrice(price, quoteAsset = quoteAsset) else "-",
                            color = TvBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Harga Beli",
                            tint = TvGreen,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Action Buttons for Real Coin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onSelectPair(effectivePair)
                        onOpenChart(effectivePair)
                    },
                    modifier = Modifier.weight(1f).height(32.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TvBlue),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.ShowChart, null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Chart", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        onSelectPair(effectivePair)
                        onOpenChart(effectivePair)
                    },
                    modifier = Modifier.weight(1f).height(32.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TvRed),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Jual / Trade", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }

    if (showEditDialog) {
        RealAvgPriceEditDialog(
            show = showEditDialog,
            onDismiss = { showEditDialog = false },
            coinUpper = coinUpper,
            availableQty = qty,
            initialAvgBuy = avgPrice,
            onSave = { c, newAvg, newInv ->
                onEditAvgBuyPrice(c, newAvg, newInv)
                showEditDialog = false
            }
        )
    }
}
