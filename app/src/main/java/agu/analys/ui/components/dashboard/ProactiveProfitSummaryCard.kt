package agu.analys.ui.components.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import agu.analys.model.BatchExecutionState
import agu.analys.model.BatchResultSummary
import agu.analys.model.CandleBar
import agu.analys.model.CoinHoldingStatus
import agu.analys.model.MarketTick
import agu.analys.model.ReadySellCoinSummary
import agu.analys.model.TradingPair
import agu.analys.model.WorthCoinInfo
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun ProactiveProfitSummaryCard(
    allPairs: List<TradingPair>,
    allTicks: Map<String, MarketTick>,
    worthBySymbol: Map<String, WorthCoinInfo>,
    recentCandles: List<CandleBar>,
    usdtIdrRate: Double,
    isRealTradingMode: Boolean,
    batchExecutionState: BatchExecutionState,
    hasSecurityPin: Boolean,
    holdingStatuses: Map<String, CoinHoldingStatus>,
    tradingFees: agu.analys.config.TradingFeeConfig = agu.analys.config.TradingFeeConfig(),
    onCoinClick: (TradingPair) -> Unit,
    onExecuteBatchSell: (List<ReadySellCoinSummary>, Boolean, String?) -> Unit,
    onResetBatchState: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLight = LocalAppColors.current == LightAppColors
    val colorOrange = TvOrange
    val colorRed = TvRed
    val colorGreen = TvGreen

    // Collect all coins with active Ready Sell status
    val readyCoins = remember(allPairs, allTicks, worthBySymbol, holdingStatuses, usdtIdrRate, tradingFees, isRealTradingMode, colorOrange, colorGreen, colorRed) {
        allPairs.mapNotNull { pair ->
            val holding = holdingStatuses[pair.symbol] ?: return@mapNotNull null
            if (!holding.isHolding || holding.quantity <= 0.00000001) return@mapNotNull null
            if (holding.isReal != isRealTradingMode) return@mapNotNull null

            val tick = allTicks[pair.symbol]
            val currentPrice = tick?.price ?: 0.0
            if (currentPrice <= 0.0) return@mapNotNull null

            val context = agu.analys.model.PositionContext.create(
                symbol = pair.symbol,
                spotPosition = null,
                holdingStatus = holding,
                currentPrice = currentPrice,
                fees = tradingFees
            )

            val badge = ReadySellBadgeEvaluator.computeReadyBadge(
                context = context,
                tick = tick,
                tradingFees = tradingFees,
                colorOrange = colorOrange,
                colorGreen = colorGreen,
                colorRed = colorRed,
                rsi = null
            ) ?: return@mapNotNull null

            if (!badge.isExitDecisionEvent) return@mapNotNull null

            val netProfitPct = context.floatingProfitPct ?: 0.0
            val netSell = context.netValue ?: 0.0
            val costBasis = context.costBasis ?: 0.0

            val rate = if (pair.quoteAsset.equals("USDT", true) || pair.quoteAsset.equals("USD", true)) usdtIdrRate else 1.0
            val cashOutValueIdr = netSell * rate
            val costIdr = costBasis * rate
            val profitIdr = cashOutValueIdr - costIdr

            ReadySellCoinSummary(
                pair = pair,
                quantity = holding.quantity,
                entryPrice = holding.entryPrice,
                currentPrice = currentPrice,
                profitPct = netProfitPct,
                profitIdr = profitIdr,
                cashOutValueIdr = cashOutValueIdr,
                badgeLabel = badge.label,
                badgeColor = badge.color,
                isReal = holding.isReal
            )
        }
    }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    AnimatedVisibility(
        visible = readyCoins.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        val totalCashOutValueIdr = readyCoins.sumOf { it.cashOutValueIdr }
        val totalUnrealizedGainsIdr = readyCoins.sumOf { it.profitIdr }
        val totalCostIdr = totalCashOutValueIdr - totalUnrealizedGainsIdr
        val totalGainPct = if (totalCostIdr > 0.0) (totalUnrealizedGainsIdr / totalCostIdr) * 100.0 else 0.0

        val pulseTransition = rememberInfiniteTransition(label = "profit_summary_pulse")
        val pulseAlpha by pulseTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "summary_pulse_alpha"
        )

        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("proactive_profit_summary_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isLight) Color(0xFFE6F4EA) else Color(0xFF13231B) // Light mint canvas in Light mode, deep emerald in Dark mode
            ),
            border = BorderStroke(
                1.5.dp,
                Brush.horizontalGradient(
                    colors = listOf(
                        TvGreen.copy(alpha = pulseAlpha),
                        TvAmber.copy(alpha = pulseAlpha * 0.8f),
                        TvGreen.copy(alpha = pulseAlpha * 0.5f)
                    )
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (isExpanded) 14.dp else 11.dp)
            ) {
                // Top Row: Title + Quick Info + Badges + Open/Close Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isExpanded = !isExpanded }
                        .testTag("proactive_profit_summary_header"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(TvGreen.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MonetizationOn,
                                contentDescription = null,
                                tint = TvGreen,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Proactive Profit Summary",
                                color = TvTextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.2.sp
                            )
                            val subtitleText = if (isExpanded) {
                                "${readyCoins.size} Koin Siap Amankan Keuntungan"
                            } else {
                                val profitSign = if (totalUnrealizedGainsIdr >= 0) "+" else ""
                                val pctSign = if (totalGainPct >= 0) "+" else ""
                                "${readyCoins.size} Koin • $profitSign${PriceFormatter.formatPrice(totalUnrealizedGainsIdr, showSymbol = true, quoteAsset = "IDR")} ($pctSign${String.format(java.util.Locale.US, "%.1f", totalGainPct)}%)"
                            }
                            Text(
                                text = subtitleText,
                                color = if (!isExpanded && totalUnrealizedGainsIdr > 0.0) TvGreen else TvTextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = if (!isExpanded && totalUnrealizedGainsIdr > 0.0) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Trading Mode indicator badge
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isRealTradingMode) TvOrange.copy(alpha = 0.25f) else TvBlue.copy(alpha = 0.25f),
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (isRealTradingMode) "REAL" else "SIM",
                                color = if (isRealTradingMode) TvOrange else TvBlue,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Box(
                            modifier = Modifier
                                .background(
                                    TvGreen.copy(alpha = 0.2f),
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "🎯 READY SELL",
                                color = TvGreen,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        // Open / Close Toggle Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isLight) Color(0xFFD4EAD9) else Color(0xFF1B382A))
                                .clickable { isExpanded = !isExpanded }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                .testTag("proactive_profit_summary_toggle_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = if (isExpanded) "Tutup" else "Buka",
                                    color = TvGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isExpanded) "Tutup Proactive Profit Summary" else "Buka Proactive Profit Summary",
                                    tint = TvGreen,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }

                // Collapsible detailed section
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(12.dp))

                        // Metric Cards: Total Unrealized Gains + Total Cash-Out Value
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Total Unrealized Profit Box
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (isLight) Color(0xFFFFFFFF) else Color(0xFF0A1811), RoundedCornerShape(12.dp))
                                    .border(0.8.dp, TvGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                    .padding(10.dp)
                                    .testTag("total_unrealized_profit_box")
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.TrendingUp,
                                            contentDescription = null,
                                            tint = TvGreen,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "PROFIT BELUM REALISASI",
                                            color = TvTextSecondary,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = PriceFormatter.formatPrice(totalUnrealizedGainsIdr, showSymbol = true, quoteAsset = "IDR"),
                                        color = TvGreen,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = "${if (totalGainPct >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", totalGainPct)}% Akumulasi",
                                        color = if (totalGainPct >= 0) TvGreen else TvRed,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Total Potential Cash-Out Value Box
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (isLight) Color(0xFFFFFFFF) else Color(0xFF0A1811), RoundedCornerShape(12.dp))
                                    .border(0.8.dp, TvAmber.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                    .padding(10.dp)
                                    .testTag("total_cashout_value_box")
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = TvAmber,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "POTENSI CASH-OUT KAS",
                                            color = TvTextSecondary,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = PriceFormatter.formatPrice(totalCashOutValueIdr, showSymbol = true, quoteAsset = "IDR"),
                                        color = TvAmber,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = "Estimasi Nilai Likuidasi",
                                        color = TvTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Action Bar: "Sell All Ready Assets" Button
                        Button(
                            onClick = { showConfirmDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("sell_all_ready_assets_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRealTradingMode) Color(0xFFC0392B) else TvGreen,
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FlashOn,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Jual Semua Aset Siap (${readyCoins.size})",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.2.sp
                                )
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color.Black.copy(alpha = 0.25f)
                                ) {
                                    Text(
                                        text = if (isRealTradingMode) "TOKOCRYPTO REAL" else "SIMULASI",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Horizontal scroll list of ready-to-sell coin chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            readyCoins.forEach { coin ->
                                ReadySellChip(
                                    coin = coin,
                                    onClick = { onCoinClick(coin.pair) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Confirmation Dialog for Batch Sell
    if (showConfirmDialog) {
        BatchSellConfirmationDialog(
            readyCoins = readyCoins,
            initialRealMode = isRealTradingMode,
            hasSecurityPin = hasSecurityPin,
            onDismiss = { showConfirmDialog = false },
            onConfirm = { modeIsReal, pin ->
                showConfirmDialog = false
                onExecuteBatchSell(readyCoins, modeIsReal, pin)
            }
        )
    }

    // Progress Modal Dialog
    if (batchExecutionState is BatchExecutionState.InProgress) {
        BatchSellProgressDialog(state = batchExecutionState)
    }

    // Completion Results Dialog
    if (batchExecutionState is BatchExecutionState.Completed) {
        BatchSellResultDialog(
            summary = batchExecutionState.summary,
            onDismiss = onResetBatchState
        )
    }

    // Error Alert Dialog
    if (batchExecutionState is BatchExecutionState.Error) {
        AlertDialog(
            onDismissRequest = onResetBatchState,
            title = {
                Text(
                    text = "Gagal Eksekusi Batch Sell",
                    color = TvRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = batchExecutionState.message,
                    color = TvTextPrimary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = onResetBatchState,
                    colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                ) {
                    Text("OK", color = Color.White)
                }
            },
            containerColor = Color(0xFF1B242C),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun ReadySellChip(
    coin: ReadySellCoinSummary,
    onClick: () -> Unit
) {
    val isLight = LocalAppColors.current == LightAppColors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isLight) Color(0xFFD1FAE5) else Color(0xFF1B2C22),
        border = BorderStroke(1.dp, coin.badgeColor.copy(alpha = if (isLight) 0.65f else 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssetAvatar(baseAsset = coin.pair.baseAsset, iconUrl = coin.pair.iconUrl, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = coin.pair.baseAsset,
                        color = TvTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${if (coin.profitPct >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", coin.profitPct)}%",
                        color = if (coin.profitPct >= 0) TvGreen else TvRed,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = coin.badgeLabel,
                    color = coin.badgeColor,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(Modifier.width(5.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TvTextSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

