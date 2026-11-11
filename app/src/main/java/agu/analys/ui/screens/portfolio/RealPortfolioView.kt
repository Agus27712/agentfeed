package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.FeeSchedule
import agu.analys.database.RealOpenOrderEntity
import agu.analys.database.RealTradeEntity
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.ui.theme.*

/**
 * Tampilan khusus Portofolio Real (Tokocrypto):
 * Memisahkan secara total data riil dari data simulasi.
 * Mengelola kartu saldo live, tab ber-Navigasi (Aset, Antrean, Riwayat), antrean order aktif dengan tombol cancel, riwayat transaksi, dan shortcut trade.
 */
@Composable
fun RealPortfolioView(
    isPinUnlocked: Boolean,
    realBalance: Map<String, Double>,
    realFreeBalance: Map<String, Double> = emptyMap(),
    realLockedBalance: Map<String, Double> = emptyMap(),
    realOpenOrders: List<RealOpenOrderEntity> = emptyList(),
    realTrades: List<RealTradeEntity> = emptyList(),
    realAvgBuyPrices: Map<String, Double> = emptyMap(),
    isFetchingRealBalance: Boolean,
    dashboardTicks: Map<String, MarketTick>,
    currentTick: MarketTick?,
    realTradeStatus: String? = null,
    isTokocrypto: Boolean = false,
    onUnlockPin: () -> Unit,
    onRefreshRealBalance: () -> Unit,
    onEditAvgBuyPrice: (coin: String, newAvgPrice: Double, newInvested: Double) -> Unit = { _, _, _ -> },
    onCancelRealOrder: (String, String) -> Unit = { _, _ -> },
    onOpenChart: (TradingPair) -> Unit,
    onSelectPair: (TradingPair) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedRealTab by remember { mutableStateOf(RealPortfolioTab.ASSETS) }

    val savedBalances = remember { agu.analys.util.AppPreferences(context).getSavedRealBalance("TOKOCRYPTO") }

    val realIdr = realBalance["idr"] ?: realBalance["IDR"] ?: savedBalances["idr"] ?: savedBalances["IDR"] ?: 0.0
    val freeIdr = realFreeBalance["idr"] ?: realFreeBalance["IDR"] ?: realIdr
    val lockedIdr = realLockedBalance["idr"] ?: realLockedBalance["IDR"] ?: 0.0

    // Sub-saldo USDT riil: dipakai untuk order di pair berkuotasi USDT.
    // Kunci saldo mengikuti respons Tokocrypto ("usdt"), dengan toleransi ejaan lain.
    val realUsdt = realBalance["usdt"] ?: realBalance["USDT"]
        ?: realBalance.entries.firstOrNull { (k, _) ->
            val key = k.lowercase()
            key == "usdt" || key == "usd" || key == "usdc" || key == "busd"
        }?.value
        ?: savedBalances["usdt"] ?: savedBalances["USDT"]
        ?: savedBalances.entries.firstOrNull { (k, _) ->
            val key = k.lowercase()
            key == "usdt" || key == "usd" || key == "usdc" || key == "busd"
        }?.value ?: 0.0

    val freeUsdt = realFreeBalance["usdt"] ?: realFreeBalance["USDT"]
        ?: realFreeBalance.entries.firstOrNull { (k, _) ->
            val key = k.lowercase()
            key == "usdt" || key == "usd" || key == "usdc" || key == "busd"
        }?.value ?: realUsdt

    val lockedUsdt = realLockedBalance["usdt"] ?: realLockedBalance["USDT"]
        ?: realLockedBalance.entries.firstOrNull { (k, _) ->
            val key = k.lowercase()
            key == "usdt" || key == "usd" || key == "usdc" || key == "busd"
        }?.value ?: 0.0

    // Kurs live (bukan hardcode) untuk ekuivalen Rupiah saldo USDT.
    val usdtIdrRate by agu.analys.util.ExchangeRateManager.usdtIdrRate.collectAsState()
    val realUsdtIdr = if (usdtIdrRate > 0.0) realUsdt * usdtIdrRate else 0.0

    val realCoinItemsList = remember(realBalance, realFreeBalance, realLockedBalance, realAvgBuyPrices, dashboardTicks, currentTick, isTokocrypto, usdtIdrRate) {
        realBalance.entries
            .filter { (key, value) ->
                val k = key.lowercase()
                k != "idr" && k != "usdt" && k != "usd" && k != "usdc" && k != "busd" && k != "bidr" && value > 0.00000001
            }
            .groupBy { it.key.uppercase() }
            .map { (coinUpper, entries) ->
                val coinLower = coinUpper.lowercase()
                val qty = entries.maxOf { it.value }
                val symbolUsdt = "${coinUpper}USDT"
                val symbolIdr = "${coinUpper}IDR"

                val isQuoteUsdt = isTokocrypto || dashboardTicks.containsKey(symbolUsdt) || (currentTick?.symbol?.equals(symbolUsdt, true) == true)
                val primarySymbol = if (isQuoteUsdt) symbolUsdt else symbolIdr

                val price = when {
                    primarySymbol.equals(currentTick?.symbol, ignoreCase = true) -> currentTick?.price ?: 0.0
                    dashboardTicks.containsKey(primarySymbol) -> dashboardTicks[primarySymbol]?.price ?: 0.0
                    dashboardTicks.containsKey("${coinLower}_usdt") -> dashboardTicks["${coinLower}_usdt"]?.price ?: 0.0
                    dashboardTicks.containsKey("${coinLower}_idr") -> dashboardTicks["${coinLower}_idr"]?.price ?: 0.0
                    dashboardTicks.containsKey(coinUpper) -> dashboardTicks[coinUpper]?.price ?: 0.0
                    else -> 0.0
                }

                val avgPrice = realAvgBuyPrices[coinUpper]
                    ?: realAvgBuyPrices[coinLower]
                    ?: realAvgBuyPrices[primarySymbol]
                    ?: 0.0
                val effectivePrice = if (price > 0.0) price else avgPrice

                val quoteAsset = if (isQuoteUsdt) "USDT" else "IDR"

                // Normalisasi nilai estimasi & PnL ke Rupiah (IDR)
                // Kurs belum ada => nilai Rp 0 (tidak menganggap $ sebagai Rp)
                val rate = if (isQuoteUsdt) usdtIdrRate else 1.0
                val estValIdr = qty * effectivePrice * rate
                // Basis biaya tunggal (2026-10-15): **taker + slippage dua sisi**, sama persis
                // dengan FSM paper trade. Sebelumnya baris ini memakai selisih harga **bruto**
                // tanpa fee sama sekali — angka paling optimistis di aplikasi.
                val exchangeName = "TOKOCRYPTO"
                val costBasis = if (avgPrice > 0.0) {
                    FeeSchedule.costBasisNet(
                        quantity = qty,
                        entry = avgPrice,
                        buyFeePct = FeeSchedule.buyRate(exchangeName, quoteAsset) * 100.0
                    )
                } else {
                    0.0
                }
                val netValue = FeeSchedule.netSellValue(
                    quantity = qty,
                    price = effectivePrice,
                    sellFeePct = FeeSchedule.sellRate(exchangeName, quoteAsset) * 100.0
                )
                val pnlIdr = if (avgPrice > 0.0) (netValue - costBasis) * rate else 0.0
                val pnlPct = if (avgPrice > 0.0 && costBasis > 0.0) ((netValue - costBasis) / costBasis) * 100.0 else 0.0

                val freeQty = realFreeBalance[coinLower] ?: realFreeBalance[coinUpper] ?: qty
                val lockedQty = realLockedBalance[coinLower] ?: realLockedBalance[coinUpper] ?: 0.0

                val pair = TradingPair.fromCustomSymbol(primarySymbol, quoteAsset)

                Triple(
                    Pair(coinUpper, Triple(qty, freeQty, lockedQty)),
                    Pair(estValIdr, Triple(price, avgPrice, Pair(pnlIdr, pnlPct))),
                    Pair(quoteAsset, pair)
                )
            }.sortedByDescending { it.second.first }
    }
    
    val estTotalCryptoIdr = remember(realCoinItemsList) { realCoinItemsList.sumOf { it.second.first } }
    val totalRealPortfolioIdr = realIdr + realUsdtIdr + estTotalCryptoIdr

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isPinUnlocked) {
            item {
                RealPortfolioLockedCard(onUnlockPin = onUnlockPin)
            }
        } else {
            // STATUS BANNER (IF ANY)
            if (!realTradeStatus.isNullOrBlank()) {
                item {
                    RealPortfolioStatusBanner(status = realTradeStatus)
                }
            }

            // UNLOCKED REAL PORTFOLIO SUMMARY CARD
            item {
                RealPortfolioSummaryCard(
                    totalRealPortfolioIdr = totalRealPortfolioIdr,
                    realIdr = realIdr,
                    freeIdr = freeIdr,
                    lockedIdr = lockedIdr,
                    estTotalCryptoIdr = estTotalCryptoIdr,
                    isFetchingRealBalance = isFetchingRealBalance,
                    onRefreshRealBalance = onRefreshRealBalance,
                    realUsdt = realUsdt,
                    freeUsdt = freeUsdt,
                    lockedUsdt = lockedUsdt,
                    usdtIdrRate = usdtIdrRate,
                    isTokocrypto = isTokocrypto
                )
            }

            // TAB SEGMENT: Aset | Antrean | Riwayat
            item {
                RealPortfolioTabSelector(
                    selectedTab = selectedRealTab,
                    onTabSelected = { selectedRealTab = it },
                    assetsCount = realCoinItemsList.size,
                    ordersCount = realOpenOrders.size,
                    tradesCount = realTrades.size
                )
            }

            // KONTEN TAB REAL PORTOFOLIO
            when (selectedRealTab) {
                RealPortfolioTab.ASSETS -> {
                    if (realCoinItemsList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TvCardBackground, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                    .padding(20.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                androidx.compose.material3.Text(
                                    "Belum ada aset koin kripto terdeteksi di akun Tokocrypto.",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else {
                        items(realCoinItemsList, key = { "real_coin_${it.first.first}" }) { itemData ->
                            val (coinUpper, qtyTriple) = itemData.first
                            val (qty, freeQty, lockedQty) = qtyTriple
                            val (estValIdr, details) = itemData.second
                            val (price, avgPrice, pnlPair) = details
                            val (pnlIdr, pnlPct) = pnlPair
                            val (quoteAsset, pair) = itemData.third

                            RealPortfolioAssetItem(
                                coinUpper = coinUpper,
                                qty = qty,
                                freeQty = freeQty,
                                lockedQty = lockedQty,
                                estVal = estValIdr,
                                price = price,
                                avgPrice = avgPrice,
                                pnlIdr = pnlIdr,
                                pnlPct = pnlPct,
                                quoteAsset = quoteAsset,
                                isTokocrypto = isTokocrypto,
                                tradingPair = pair,
                                onEditAvgBuyPrice = onEditAvgBuyPrice,
                                onSelectPair = onSelectPair,
                                onOpenChart = onOpenChart
                            )
                        }
                    }
                }

                RealPortfolioTab.OPEN_ORDERS -> {
                    realPortfolioOpenOrdersSection(
                        realOpenOrders = realOpenOrders,
                        onCancelRealOrder = onCancelRealOrder
                    )
                }

                RealPortfolioTab.HISTORY -> {
                    realPortfolioHistorySection(
                        realTrades = realTrades
                    )
                }
            }

            // TOP-UP & WITHDRAW NOTICE CARD FOR REAL MODE
            item {
                RealPortfolioNoticeCard(isTokocrypto = isTokocrypto)
            }
        }
    }
}