package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.TradingPair
import agu.analys.trading.SimulationOrder
import agu.analys.trading.SimulationTradeHistoryItem
import agu.analys.trading.SimulationWallet
import agu.analys.ui.components.simulation.OpenOrderItemCard
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import java.util.Locale

/**
 * Tampilan khusus Portofolio Simulasi:
 * Menampilkan ringkasan portofolio virtual, kas Rupiah & USDT simulasi,
 * daftar koin virtual yang dimiliki, antrean open orders, serta riwayat eksekusi.
 */
@Composable
fun SimulationPortfolioView(
    wallet: SimulationWallet,
    history: List<SimulationTradeHistoryItem>,
    openOrders: List<SimulationOrder>,
    holdings: List<HoldingItem>,
    totalPortfolioValueIdr: Double,
    totalUnrealizedPnlIdr: Double,
    totalUnrealizedPnlPct: Double,
    selectedTab: PortfolioTab,
    onSelectTab: (PortfolioTab) -> Unit,
    onOpenTopUp: () -> Unit,
    onOpenChart: (TradingPair) -> Unit,
    onNavigateToSimulation: (TradingPair) -> Unit,
    onCancelOrder: (String) -> Unit,
    onCancelAllOrders: (String?) -> Unit,
    realIdrBalance: Double = 0.0,
    isRealSimSyncEnabled: Boolean = true,
    /** Kurs USDT→IDR live. `0.0` = belum tersedia dari exchange (bukan nilai hardcode). */
    usdtIdrRate: Double = 0.0,
    /** Buka dialog konversi Rupiah ⇄ USDT di halaman Portofolio. */
    onOpenConvert: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val totalKasCombinedIdr = if (isRealSimSyncEnabled) realIdrBalance + wallet.idrBalance else wallet.idrBalance
    val usdtBalanceIdr = if (usdtIdrRate > 0.0) wallet.usdtBalance * usdtIdrRate else 0.0

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // CARD 1: TOTAL PORTOFOLIO & PNL OVERVIEW
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ESTIMASI TOTAL NILAI",
                            color = TvTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Row(
                            modifier = Modifier
                                .background(TvSurfaceVariant, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PieChart,
                                contentDescription = null,
                                tint = TvBlue,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${holdings.size} Koin",
                                color = TvBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Total Nilai Portofolio
                    Text(
                        text = PriceFormatter.formatPrice(totalPortfolioValueIdr, quoteAsset = "IDR"),
                        color = TvTextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )

                    Spacer(Modifier.height(6.dp))

                    // Total Floating PnL
                    val pnlColor = if (totalUnrealizedPnlIdr >= 0) TvGreen else TvRed
                    val pnlPrefix = if (totalUnrealizedPnlIdr >= 0) "+" else ""
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Floating PnL: ",
                            color = TvTextSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "$pnlPrefix${PriceFormatter.formatPrice(totalUnrealizedPnlIdr, quoteAsset = "IDR")} ($pnlPrefix${String.format(Locale.US, "%.2f", totalUnrealizedPnlPct)}%)",
                            color = pnlColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // CARD 2: SALDO KAS RUPIAH (MULTI-TIER: TOTAL GABUNGAN + REAL 1:1 + SIMULASI ONLY)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = if (isRealSimSyncEnabled) TvGreen else TvBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isRealSimSyncEnabled) "SALDO KAS RUPIAH (IDR)" else "SALDO KAS SIMULASI (IDR)",
                                color = if (isRealSimSyncEnabled) TvBlue else TvTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = onOpenTopUp,
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isRealSimSyncEnabled) TvGreen else TvBlue)
                        ) {
                            Text("+ Top Up Sim", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    if (isRealSimSyncEnabled) {
                        // Highlight Total Saldo Kas Gabungan (Real + Sim)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Total Saldo Kas (Real + Sim)",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = PriceFormatter.formatPrice(totalKasCombinedIdr, quoteAsset = "IDR"),
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = TvGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // 2-Kolom Split: Saldo Real (1:1 Tokocrypto) vs Saldo Simulasi Only
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Kolom 1: Saldo Real 1:1
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(TvBackground, RoundedCornerShape(8.dp))
                                    .border(1.dp, TvGreen.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Saldo Real", color = TvTextSecondary, fontSize = 10.sp)
                                    Box(
                                        modifier = Modifier
                                            .background(TvGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text("1:1 TOKOCRYPTO", color = TvGreen, fontSize = 8.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = PriceFormatter.formatPrice(realIdrBalance, quoteAsset = "IDR"),
                                    color = TvGreen,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Sinkron 1:1 live",
                                    color = TvTextSecondary,
                                    fontSize = 9.sp
                                )
                            }

                            // Kolom 2: Saldo Simulasi Only
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(TvBackground, RoundedCornerShape(8.dp))
                                    .border(1.dp, TvBlue.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Saldo Simulasi", color = TvTextSecondary, fontSize = 10.sp)
                                    Box(
                                        modifier = Modifier
                                            .background(TvBlue.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text("SIM ONLY", color = TvBlue, fontSize = 8.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = PriceFormatter.formatPrice(wallet.getAvailableIdr(), quoteAsset = "IDR"),
                                    color = TvBlue,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Tersedia trading",
                                    color = TvTextSecondary,
                                    fontSize = 9.sp
                                )
                            }
                        }

                        if (wallet.lockedIdr > 0.0) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Terkunci di Antrean Open Order:", color = TvTextSecondary, fontSize = 10.sp)
                                Text(
                                    PriceFormatter.formatPrice(wallet.lockedIdr, quoteAsset = "IDR"),
                                    color = TvAmber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        // Catatan Shadow Mirror
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TvSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = TvGreen,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Shadow Mirror Aktif: Order real otomatis dicerminkan 1:1 ke simulasi agar mudah dipantau saat di luar rumah tanpa kendala IP Whitelist.",
                                color = TvTextSecondary,
                                fontSize = 9.sp,
                                lineHeight = 12.sp
                            )
                        }
                    } else {
                        // MODE ISOLASI SIMULASI MURNI
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Sisa Kas Simulasi (Siap Pakai)",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = PriceFormatter.formatPrice(wallet.getAvailableIdr(), quoteAsset = "IDR"),
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = TvBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Kolom 1: Terkunci di Antrean
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(TvBackground, RoundedCornerShape(8.dp))
                                    .border(1.dp, TvAmber.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Text("Terkunci Antrean", color = TvTextSecondary, fontSize = 10.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = PriceFormatter.formatPrice(wallet.lockedIdr, quoteAsset = "IDR"),
                                    color = TvAmber,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text("Open limit order", color = TvTextSecondary, fontSize = 9.sp)
                            }

                            // Kolom 2: Total Modal Simulasi
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(TvBackground, RoundedCornerShape(8.dp))
                                    .border(1.dp, TvBlue.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Text("Total Kas Simulasi", color = TvTextSecondary, fontSize = 10.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = PriceFormatter.formatPrice(wallet.idrBalance, quoteAsset = "IDR"),
                                    color = TvBlue,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text("Termasuk antrean", color = TvTextSecondary, fontSize = 9.sp)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TvSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = TvBlue,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Mode Isolasi Aktif: Berjalan hanya dengan simulasi virtual murni. Tidak ada saldo atau aset real Tokocrypto yang tersinkronisasi.",
                                color = TvTextSecondary,
                                fontSize = 9.sp,
                                lineHeight = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // CARD 2B: SALDO USDT & KONVERSI (Rp ⇄ $)
        // Saldo dollar dipisah total dari saldo Rupiah supaya harga $ dan Rp tidak tercampur.
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(TvGreen.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$",
                                    color = TvGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "SALDO USDT ($)",
                                color = TvGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // Tombol fitur konversi — masuk ke halaman Portofolio.
                        Button(
                            onClick = onOpenConvert,
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapVert,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Konversi", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Saldo USDT tersedia",
                                color = TvTextSecondary,
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = PriceFormatter.formatPrice(wallet.getAvailableUsdt(), quoteAsset = "USDT"),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                            if (usdtIdrRate > 0.0 && wallet.usdtBalance > 0.0) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "≈ ${PriceFormatter.formatPrice(usdtBalanceIdr, quoteAsset = "IDR", decimals = 0)}",
                                    color = TvTextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Kurs",
                                color = TvTextSecondary,
                                fontSize = 9.sp
                            )
                            Text(
                                text = if (usdtIdrRate > 0.0) {
                                    PriceFormatter.formatPrice(usdtIdrRate, quoteAsset = "IDR", decimals = 0)
                                } else {
                                    "—"
                                },
                                color = if (usdtIdrRate > 0.0) TvGreen else TvAmber,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (wallet.lockedUsdt > 0.0) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Terkunci di antrean order USDT:", color = TvTextSecondary, fontSize = 10.sp)
                            Text(
                                PriceFormatter.formatPrice(wallet.lockedUsdt, quoteAsset = "USDT"),
                                color = TvAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TvSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = TvGreen,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (usdtIdrRate > 0.0) {
                                "Saldo $ dipakai otomatis untuk order di pair berkuotasi USDT. Konversi memakai kurs live ${PriceFormatter.formatPrice(usdtIdrRate, quoteAsset = "IDR", decimals = 0)}/USDT, bukan angka tetap."
                            } else {
                                "Kurs USDT/IDR belum tersedia dari exchange. Konversi dan order pair USDT ditahan sampai kurs berhasil diambil."
                            },
                            color = if (usdtIdrRate > 0.0) TvTextSecondary else TvAmber,
                            fontSize = 9.sp,
                            lineHeight = 12.sp
                        )
                    }
                }
            }
        }

        // TAB SEGMENT: Koin Dimiliki | Riwayat Transaksi | Posisi Spot
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PortfolioTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) TvCardBackground else Color.Transparent)
                            .clickable { onSelectTab(tab) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.title,
                            color = if (isSelected) TvBlue else TvTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // KONTEN TAB
        when (selectedTab) {
            PortfolioTab.HOLDINGS -> {
                if (holdings.isEmpty()) {
                    item {
                        EmptyHoldingsCard(
                            onNavigateToSimulation = {
                                val fallbackPair = TradingPair.popularPairsForSource().first()
                                onNavigateToSimulation(fallbackPair)
                            }
                        )
                    }
                } else {
                    items(holdings, key = { "sim_holding_${it.baseAsset}_${it.quoteAsset}" }) { item ->
                        HoldingCoinCard(
                            item = item,
                            onTrade = { onNavigateToSimulation(item.tradingPair) },
                            onViewChart = { onOpenChart(item.tradingPair) }
                        )
                    }
                }
            }

            PortfolioTab.OPEN_ORDERS -> {
                if (openOrders.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TvCardBackground, RoundedCornerShape(10.dp))
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Tidak ada antrean order terbuka saat ini.",
                                color = TvTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Total ${openOrders.size} Order Aktif",
                                color = TvTextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(TvSurfaceVariant)
                                    .clickable { onCancelAllOrders(null) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Batalkan Semua",
                                    color = TvRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    items(openOrders, key = { "sim_order_${it.id}" }) { order ->
                        OpenOrderItemCard(
                            order = order,
                            onCancel = { onCancelOrder(order.id) }
                        )
                    }
                }
            }

            PortfolioTab.HISTORY -> {
                if (history.isEmpty()) {
                    item {
                        EmptyHistoryCard()
                    }
                } else {
                    items(history, key = { "sim_history_${it.id}" }) { trade ->
                        TradeHistoryItemCard(trade = trade)
                    }
                }
            }
        }
    }
}