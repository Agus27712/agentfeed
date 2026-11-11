package agu.analys.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.*

@Composable
fun EmptyWatchlistState(
    selectedTab: MarketRankingTab = MarketRankingTab.WATCHLIST,
    onAddClick: () -> Unit
) {
    val (icon, iconTint, title, desc, showBtn, btnText, btnColor) = when (selectedTab) {
        MarketRankingTab.WATCHLIST -> {
            Tuple7(
                Icons.Default.FormatListBulleted,
                TvBlue,
                "Daftar Pantauan Masih Kosong",
                "Tambahkan koin yang ingin Anda pantau secara kustom melalui tombol Tambah Koin di bawah atau melalui menu Pengaturan Watchlist.",
                true,
                "Tambah Koin Pantauan",
                TvBlue
            )
        }
        MarketRankingTab.FAVORITE -> {
            Tuple7(
                Icons.Default.Star,
                TvAmber,
                "Daftar Favorit Masih Kosong",
                "Tambahkan koin dengan menekan ikon bintang (⭐) pada kartu koin di daftar pasar, atau klik tombol di bawah untuk memilih koin favorit.",
                true,
                "Tambah Koin Favorit",
                TvAmber
            )
        }
        MarketRankingTab.TOP_GAINERS -> {
            Tuple7(
                Icons.Default.TrendingUp,
                TvGreen,
                "Memuat Top Gainers...",
                "Sedang menyinkronkan data pergerakan gainers pasar kripto Tokocrypto realtime.",
                false,
                "",
                TvGreen
            )
        }
        MarketRankingTab.TOP_LOSERS -> {
            Tuple7(
                Icons.Default.TrendingDown,
                TvRed,
                "Memuat Top Losers...",
                "Sedang menyinkronkan data koin dengan koreksi/diskon harga terdalam di pasar Tokocrypto.",
                false,
                "",
                TvRed
            )
        }
        MarketRankingTab.TOP_VOLUME -> {
            Tuple7(
                Icons.Default.LocalFireDepartment,
                Color(0xFFA78BFA),
                "Memuat Top 24H Volume...",
                "Sedang menyinkronkan aset dengan likuiditas dan perputaran volume terbesar.",
                false,
                "",
                Color(0xFFA78BFA)
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Card),
        border = BorderStroke(1.dp, DashboardColors.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                color = TvTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = desc,
                color = TvTextSecondary,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )
            if (showBtn) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onAddClick,
                    colors = ButtonDefaults.buttonColors(containerColor = btnColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        btnText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

private data class Tuple7<A, B, C, D, E, F, G>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F,
    val seventh: G
)
