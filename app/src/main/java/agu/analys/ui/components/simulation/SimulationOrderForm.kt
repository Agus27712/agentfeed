package agu.analys.ui.components.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.TradingPair
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.trading.SimulationWallet
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun SimulationOrderForm(
    pair: TradingPair,
    currentPrice: Double,
    wallet: SimulationWallet,
    selectedSide: SimulationOrderSide,
    selectedType: SimulationOrderType,
    onSideChange: (SimulationOrderSide) -> Unit,
    onTypeChange: (SimulationOrderType) -> Unit,
    inputPrice: String,
    inputStopPrice: String,
    inputQuantity: String,
    inputTotalIdr: String,
    onPriceChange: (String) -> Unit,
    onStopPriceChange: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onTotalIdrChange: (String) -> Unit,
    onSubmitOrder: () -> Unit,
    onOpenTopUp: () -> Unit,
    isRealMode: Boolean = false,
    realIdrBalance: Double = 0.0,
    /**
     * Saldo USDT riil di exchange. Untuk pair berkuotasi USDT, saldo inilah yang
     * dipakai (bukan saldo Rupiah) supaya `$` dan `Rp` tidak tercampur.
     */
    realUsdtBalance: Double = 0.0,
    modifier: Modifier = Modifier
) {
    var showTypeMenu by remember { mutableStateOf(false) }
    val isBuy = selectedSide == SimulationOrderSide.BUY
    val themeColor = if (isBuy) TvGreen else TvRed
    val quote = pair.quoteAsset

    // Saldo yang relevan dipilih berdasarkan kuotasi pair:
    //  - pair USDT → saldo USDT ($)   (real: realUsdtBalance | simulasi: wallet.getAvailableUsdt())
    //  - pair IDR  → saldo Rupiah (Rp) (real: realIdrBalance  | simulasi: wallet.getAvailableIdr())
    val isUsdtQuote = agu.analys.util.PriceFormatter.isUsdtQuote(quote)
    val availableQuoteBalance = if (isUsdtQuote) {
        if (isRealMode && realUsdtBalance > 0.0) realUsdtBalance else wallet.getAvailableUsdt()
    } else {
        if (isRealMode && realIdrBalance > 0.0) realIdrBalance else wallet.getAvailableIdr()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = 6.dp)
    ) {
        // Tab Beli / Jual Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSideChange(SimulationOrderSide.BUY) },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Beli",
                        color = if (isBuy) TvGreen else TvTextSecondary,
                        fontSize = 15.sp,
                        fontWeight = if (isBuy) FontWeight.Bold else FontWeight.Medium
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(if (isBuy) TvGreen else Color.Transparent)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSideChange(SimulationOrderSide.SELL) },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Jual",
                        color = if (!isBuy) TvRed else TvTextSecondary,
                        fontSize = 15.sp,
                        fontWeight = if (!isBuy) FontWeight.Bold else FontWeight.Medium
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(if (!isBuy) TvRed else Color.Transparent)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Dropdown Tipe Order
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(TvSurfaceVariant)
                    .border(1.dp, TvBorder, RoundedCornerShape(6.dp))
                    .clickable { showTypeMenu = true }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedType.displayName,
                    color = TvTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Dropdown Order Type",
                    tint = TvTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = showTypeMenu,
                onDismissRequest = { showTypeMenu = false },
                modifier = Modifier.background(TvCardBackground)
            ) {
                SimulationOrderType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = type.displayName,
                                color = if (selectedType == type) TvGreen else TvTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (selectedType == type) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onTypeChange(type)
                            showTypeMenu = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // STOP LIMIT: Field Stop Price
        if (selectedType == SimulationOrderType.STOP_LIMIT) {
            StepperInputField(
                label = "Stop ($quote)",
                value = inputStopPrice,
                onValueChange = onStopPriceChange,
                onStepMinus = {
                    val cur = parseSimulationDecimal(inputStopPrice) ?: currentPrice
                    val step = calculatePriceStep(cur)
                    onStopPriceChange(PriceFormatter.formatRawDecimal((cur - step).coerceAtLeast(0.0)))
                },
                onStepPlus = {
                    val cur = parseSimulationDecimal(inputStopPrice) ?: currentPrice
                    val step = calculatePriceStep(cur)
                    onStopPriceChange(PriceFormatter.formatRawDecimal(cur + step))
                }
            )
            Spacer(Modifier.height(8.dp))
        }

        // LIMIT / STOP LIMIT: Field Harga
        if (selectedType != SimulationOrderType.MARKET) {
            StepperInputField(
                label = if (selectedType == SimulationOrderType.STOP_LIMIT) "Limit ($quote)" else "Harga ($quote)",
                value = inputPrice,
                onValueChange = onPriceChange,
                onStepMinus = {
                    val cur = parseSimulationDecimal(inputPrice) ?: currentPrice
                    val step = calculatePriceStep(cur)
                    onPriceChange(PriceFormatter.formatRawDecimal((cur - step).coerceAtLeast(0.0)))
                },
                onStepPlus = {
                    val cur = parseSimulationDecimal(inputPrice) ?: currentPrice
                    val step = calculatePriceStep(cur)
                    onPriceChange(PriceFormatter.formatRawDecimal(cur + step))
                }
            )
            Spacer(Modifier.height(8.dp))
        }

        // Field Jumlah Koin
        StepperInputField(
            label = "Jumlah (${pair.baseAsset})",
            value = inputQuantity,
            onValueChange = onQuantityChange,
            onStepMinus = {
                val cur = parseSimulationDecimal(inputQuantity) ?: 0.0
                val step = calculateCoinStep(cur, currentPrice)
                onQuantityChange(formatCoinDecimals((cur - step).coerceAtLeast(0.0)))
            },
            onStepPlus = {
                val cur = parseSimulationDecimal(inputQuantity) ?: 0.0
                val step = calculateCoinStep(cur, currentPrice)
                onQuantityChange(formatCoinDecimals(cur + step))
            }
        )

        Spacer(Modifier.height(8.dp))

        // Quick Percentage Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(25, 50, 75, 100).forEach { pct ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(TvSurfaceVariant)
                        .border(1.dp, TvBorder, RoundedCornerShape(4.dp))
                        .clickable {
                            if (isBuy) {
                                // Persentase dihitung dari saldo kuotasi pair (USDT atau IDR).
                                val targetQuote = availableQuoteBalance * (pct / 100.0)
                                val price = if (selectedType == SimulationOrderType.MARKET) currentPrice else (parseSimulationDecimal(inputPrice) ?: currentPrice)
                                if (price > 0.0) {
                                    val coin = targetQuote / price
                                    onQuantityChange(formatCoinDecimals(coin))
                                    onTotalIdrChange(PriceFormatter.formatRawDecimal(targetQuote))
                                }
                            } else {
                                val availCoin = wallet.getAvailableCoin(pair.baseAsset)
                                val targetCoin = availCoin * (pct / 100.0)
                                val price = if (selectedType == SimulationOrderType.MARKET) currentPrice else (parseSimulationDecimal(inputPrice) ?: currentPrice)
                                onQuantityChange(formatCoinDecimals(targetCoin))
                                onTotalIdrChange(PriceFormatter.formatRawDecimal(targetCoin * price))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$pct%",
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Field Total
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TvSurfaceVariant)
                .border(1.dp, TvBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Total ($quote)",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
                BasicTextField(
                    value = inputTotalIdr,
                    onValueChange = { onTotalIdrChange(normalizeSimulationDecimalInput(it)) },
                    textStyle = TextStyle(
                        color = TvTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    cursorBrush = SolidColor(TvGreen),
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Info Saldo & Top-up
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isBuy) "Saldo ($quote)" else "Saldo (${pair.baseAsset})",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
                if (isRealMode && isBuy) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(TvGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "REAL",
                            color = TvGreen,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                } else if (!isRealMode && isBuy) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(TvSurfaceVariant)
                            .clickable { onOpenTopUp() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Top Up",
                            tint = TvGreen,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            Text(
                text = if (isBuy) {
                    val avail = if (isRealMode && realIdrBalance > 0.0) realIdrBalance else wallet.getAvailableIdr()
                    PriceFormatter.formatPrice(avail, quoteAsset = quote)
                    // Prefix mengikuti kuotasi pair: `$` untuk USDT, `Rp` untuk IDR.
                    PriceFormatter.formatPrice(availableQuoteBalance, quoteAsset = quote)
                } else {
                    "${formatCoinDecimals(wallet.getAvailableCoin(pair.baseAsset))} ${pair.baseAsset}"
                },
                color = if (isRealMode && isBuy) TvGreen else TvTextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onSubmitOrder,
            colors = ButtonDefaults.buttonColors(
                containerColor = themeColor,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
        ) {
            Text(
                text = if (isBuy) "Beli ${pair.baseAsset}" else "Jual ${pair.baseAsset}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}