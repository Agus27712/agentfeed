package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

@Composable
fun RealPortfolioSummaryCard(
    totalRealPortfolioIdr: Double,
    realIdr: Double,
    freeIdr: Double,
    lockedIdr: Double,
    estTotalCryptoIdr: Double,
    isFetchingRealBalance: Boolean,
    onRefreshRealBalance: () -> Unit,
    /**
     * Saldo USDT riil di exchange — ditampilkan terpisah dengan prefix `$`
     * supaya tidak tercampur dengan saldo Rupiah.
     */
    realUsdt: Double = 0.0,
    freeUsdt: Double = realUsdt,
    lockedUsdt: Double = 0.0,
    /** Kurs USDT→IDR live; `0.0` = belum tersedia (ekuivalen Rp disembunyikan). */
    usdtIdrRate: Double = 0.0,
    isTokocrypto: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvGreen.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerifiedUser, null, tint = TvGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "SALDO REAL TOKOCRYPTO",
                        color = TvGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                if (isFetchingRealBalance) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TvGreen, strokeWidth = 2.dp)
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(TvSurfaceVariant)
                            .border(0.8.dp, TvBorder, RoundedCornerShape(6.dp))
                            .clickable(onClick = onRefreshRealBalance),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = TvTextSecondary, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = PriceFormatter.formatPrice(totalRealPortfolioIdr),
                color = TvTextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Estimasi Total Aset (Cash IDR + USDT + Koin Kripto)",
                color = TvTextSecondary,
                fontSize = 10.sp
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = TvBorder)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SALDO CASH IDR", color = TvTextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    Text(PriceFormatter.formatPrice(realIdr), color = TvGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text("Tersedia: ", color = TvTextSecondary, fontSize = 9.sp)
                        Text(PriceFormatter.formatPrice(freeIdr), color = TvGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text("Terkunci: ", color = TvTextSecondary, fontSize = 9.sp)
                        Text(PriceFormatter.formatPrice(lockedIdr), color = TvRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("ESTIMASI KOIN", color = TvTextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    Text(PriceFormatter.formatPrice(estTotalCryptoIdr), color = TvBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // ── Sub-saldo USDT (prefix `$`) — dipisah total dari Rupiah ──
            if (isTokocrypto || realUsdt > 0.00000001 || lockedUsdt > 0.0) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                        .border(1.dp, TvGreen.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "SALDO USDT ($)",
                            color = TvTextSecondary,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            PriceFormatter.formatPrice(realUsdt, quoteAsset = "USDT"),
                            color = TvGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row {
                            Text("Tersedia: ", color = TvTextSecondary, fontSize = 9.sp)
                            Text(
                                PriceFormatter.formatPrice(freeUsdt, quoteAsset = "USDT"),
                                color = TvGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Terkunci: ", color = TvTextSecondary, fontSize = 9.sp)
                            Text(
                                PriceFormatter.formatPrice(lockedUsdt, quoteAsset = "USDT"),
                                color = TvRed,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("≈ RUPIAH", color = TvTextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        if (usdtIdrRate > 0.0) {
                            Text(
                                PriceFormatter.formatPrice(realUsdt * usdtIdrRate, quoteAsset = "IDR"),
                                color = TvTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "kurs ${PriceFormatter.formatPrice(usdtIdrRate, quoteAsset = "IDR", decimals = 0)}/USDT",
                                color = TvTextSecondary,
                                fontSize = 8.5.sp
                            )
                        } else {
                            Text("kurs —", color = TvAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}