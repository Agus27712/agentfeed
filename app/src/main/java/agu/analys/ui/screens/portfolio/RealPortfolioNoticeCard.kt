package agu.analys.ui.screens.portfolio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import agu.analys.ui.theme.*

@Composable
fun RealPortfolioNoticeCard(
    isTokocrypto: Boolean = false,
    modifier: Modifier = Modifier
) {
    val exchangeName = "Tokocrypto"
    Card(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = TvBlue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("INFORMASI DEPOSIT & WITHDRAW", color = TvBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Akses API $exchangeName dikhususkan untuk Mode Trade saja. Untuk melakukan Top-Up / Deposit atau Penarikan Dana (Withdraw), silakan gunakan aplikasi atau website resmi $exchangeName.",
                color = TvTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun RealPortfolioStatusBanner(
    status: String,
    modifier: Modifier = Modifier
) {
    val isSuccess = status.contains("berhasil", ignoreCase = true) || status.contains("success", ignoreCase = true)
    val isError = status.contains("error", ignoreCase = true) || status.contains("gagal", ignoreCase = true) || status.contains("invalid", ignoreCase = true)
    val bgColor = if (isError) TvRed.copy(alpha = 0.15f) else if (isSuccess) TvGreen.copy(alpha = 0.15f) else TvSurfaceVariant
    val borderColor = if (isError) TvRed.copy(alpha = 0.5f) else if (isSuccess) TvGreen.copy(alpha = 0.5f) else TvBorder
    val textColor = if (isError) TvRed else if (isSuccess) TvGreen else TvTextSecondary

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = if (isError) TvRed else if (isSuccess) TvGreen else TvBlue,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = status,
                color = textColor,
                fontSize = 10.5.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
