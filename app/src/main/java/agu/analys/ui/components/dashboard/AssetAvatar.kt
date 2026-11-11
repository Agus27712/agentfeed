package agu.analys.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage

/**
 * Ikon aset: coba CDN cryptoicons, fallback huruf base asset.
 * Tidak memakai data palsu — hanya visual identitas pair.
 */
@Composable
fun AssetAvatar(
    baseAsset: String,
    iconUrl: String = "",
    size: Dp = 36.dp
) {
    val base = baseAsset.uppercase().ifBlank { "?" }
    val cdn = iconUrl.ifBlank {
        "https://cdn.jsdelivr.net/gh/spothq/cryptocurrency-icons@master/128/color/${base.lowercase()}.png"
    }
    val bg = avatarColor(base)

    SubcomposeAsyncImage(
        model = cdn,
        contentDescription = base,
        modifier = Modifier.size(size).clip(CircleShape),
        contentScale = ContentScale.Crop,
        loading = { LetterFallback(base, bg, size) },
        error = { LetterFallback(base, bg, size) }
    )
}

@Composable
private fun LetterFallback(base: String, bg: Color, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = base.take(3),
            color = Color.White,
            fontSize = if (base.length <= 2) 12.sp else 9.sp,
            fontWeight = FontWeight.Black
        )
    }
}

private fun avatarColor(base: String): Color {
    val palette = listOf(
        Color(0xFFF7931A), // BTC orange
        Color(0xFF627EEA), // ETH
        Color(0xFF14F195), // SOL
        Color(0xFFF3BA2F), // BNB
        Color(0xFF23292F), // XRP
        Color(0xFFC2A633), // DOGE
        Color(0xFF00D395), // generic green
        Color(0xFF087FF5)
    )
    val idx = base.fold(0) { acc, c -> acc + c.code } % palette.size
    return palette[idx]
}
