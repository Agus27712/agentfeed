package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import agu.analys.model.CandleBar
import agu.analys.model.MarketTick
import agu.analys.model.TradingPair
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvBlue
import agu.analys.ui.theme.TvCyan
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import kotlin.math.abs

/**
 * Tahap **wajib** sebelum chart penuh ketika pengguna mencari/mengetik koin
 * di dashboard (2026-11-12).
 *
 * Sebelumnya `AddAssetDialog` hanya men-toggle favorit lalu menutup, sementara
 * klik kartu langsung membuka fullscreen landscape. Sekarang hasil pencarian
 * selalu masuk ke mini-chart ini dulu: pengguna melihat bentuk harga,高低 24 jam,
 * dan volume sebelum memutuskan membuka chart penuh atau sekadar mem-favoritkan.
 *
 * Data berasal dari tick real-time + candle 1 jam yang sama dengan kartu
 * dashboard. Tidak ada angka sintetis di sini; bila candle belum tersedia,
 * `MiniSparkline` memakai turunan 24 jam dari tick.
 */
@Composable
fun CoinMiniChartPreview(
    pair: TradingPair,
    tick: MarketTick?,
    candles: List<CandleBar>,
    isFavorite: Boolean,
    onOpenFullChart: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit
) {
    val change = tick?.change24h ?: 0.0
    val naik = change >= 0.0
    val warna = if (naik) TvGreen else TvRed
    val garis = if (naik) TvGreen else TvRed
    val harga = tick?.price ?: 0.0

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DashboardColors.Surface),
            border = BorderStroke(1.dp, DashboardColors.Border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // --- Header: identitas pair ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssetBadge(pair.baseAsset, TvCyan)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = pair.baseAsset,
                                color = TvTextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "/${pair.quoteAsset}",
                                color = TvCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = pair.displayName.ifBlank { pair.symbol },
                            color = TvTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    if (isFavorite) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Favorit",
                            tint = TvAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Tutup", tint = TvTextSecondary)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // --- Harga & perubahan ---
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (harga > 0.0) formatPreviewPrice(harga) else "Memuat…",
                        color = TvTextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (naik) "+%.2f%%".format(change) else "%.2f%%".format(change),
                        color = warna,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Text(
                    text = "Pratinjau mini-chart · candle 1 jam",
                    color = TvTextSecondary,
                    fontSize = 10.sp
                )

                Spacer(Modifier.height(12.dp))

                // --- Mini-chart ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(DashboardColors.Card, RoundedCornerShape(12.dp))
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    MiniSparkline(
                        tick = tick,
                        candles = candles,
                        lineColor = garis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(12.dp))

                // --- Statistik 24 jam ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem("Tinggi 24j", tick?.high24h?.takeIf { it > 0.0 }?.let { formatPreviewPrice(it) } ?: "—")
                    StatItem("Rendah 24j", tick?.low24h?.takeIf { it > 0.0 }?.let { formatPreviewPrice(it) } ?: "—")
                    StatItem("Vol 24j", formatVolume(tick?.volume24h ?: 0.0))
                }

                Spacer(Modifier.height(16.dp))

                // --- Aksi ---
                Button(
                    onClick = onOpenFullChart,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TvCyan),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "Buka Chart Penuh",
                        color = Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, DashboardColors.Border)
                    ) {
                        Text(
                            if (isFavorite) "Hapus Favorit" else "+ Favorit",
                            color = if (isFavorite) TvAmber else TvBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, DashboardColors.Border)
                    ) {
                        Text("Tutup", color = TvTextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(label, color = TvTextSecondary, fontSize = 10.sp)
        Text(
            value,
            color = TvTextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatPreviewPrice(price: Double): String = when {
    price >= 10_000.0 -> "%,.0f".format(price)
    price >= 100.0 -> "%,.2f".format(price)
    price >= 1.0 -> "%,.4f".format(price)
    price >= 0.01 -> "%,.6f".format(price)
    else -> "%,.8f".format(price)
}

private fun formatVolume(v: Double): String {
    val a = abs(v)
    return when {
        a >= 1_000_000_000.0 -> "%.2fM".format(a / 1_000_000_000.0)
        a >= 1_000_000.0 -> "%.2fJt".format(a / 1_000_000.0)
        a >= 1_000.0 -> "%.1fRb".format(a / 1_000.0)
        a > 0.0 -> "%.0f".format(a)
        else -> "—"
    }
}
