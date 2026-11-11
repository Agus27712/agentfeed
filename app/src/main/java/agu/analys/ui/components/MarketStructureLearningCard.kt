package agu.analys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.util.PriceFormatter

import agu.analys.ui.theme.*

@Composable
fun MarketStructureLearningCard(
    snapshot: MarketStructureSnapshot,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().background(TvCardBackground, RoundedCornerShape(14.dp)).padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("STRATEGI AKADEMI CRYPTO", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvGreen)
            Text("Grade: ${snapshot.akademiCryptoScalpingGrade}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvAmber)
        }
        Spacer(Modifier.height(3.dp))
        Text("Price Action & Market Structure", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
        Spacer(Modifier.height(10.dp))

        if (!snapshot.dataEnough) {
            Text(snapshot.trendExplanation, fontSize = 11.sp, color = TvTextSecondary)
            return@Column
        }

        Text(snapshot.trend, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (snapshot.hasHigherHighsHigherLows) TvGreen else if (snapshot.hasLowerHighsLowerLows) TvRed else TvAmber)
        Spacer(Modifier.height(4.dp))
        Text(snapshot.trendExplanation, fontSize = 11.sp, color = TvTextSecondary, lineHeight = 16.sp)
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LevelBox("Support (Demand)", snapshot.support, snapshot.supportDistancePct, snapshot.supportTouchesCount, quoteAsset, Modifier.weight(1f))
            LevelBox("Resistance (Supply)", snapshot.resistance, snapshot.resistanceDistancePct, snapshot.resistanceTouchesCount, quoteAsset, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))

        // Breakout & Retest + Liquidity Sweep Status
        Column(
            modifier = Modifier.fillMaxWidth().background(TvSurfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp)
        ) {
            Text("Breakout & Retest Status:", fontSize = 9.sp, color = TvTextSecondary)
            Text(
                snapshot.breakoutAndRetestStatus,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (snapshot.isBreakoutAndRetestValid) TvGreen else TvTextPrimary
            )
            if (snapshot.liquiditySweepDetected) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "🔥 Liquidity Sweep Terdeteksi: Jebakan ekor (stop hunt) di support. Potensi pembalikan naik tinggi!",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TvCyan
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("EMA 13 / 21 Trend", fontSize = 9.sp, color = TvTextSecondary)
                Text(
                    if (snapshot.isEmaBounceValid) "EMA 13 Bounce Valid (${PriceFormatter.formatPrice(snapshot.ema13Value, quoteAsset = quoteAsset)})"
                    else "EMA 13: ${PriceFormatter.formatPrice(snapshot.ema13Value, quoteAsset = quoteAsset)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TvTextPrimary
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text("Aturan Risk Management", fontSize = 9.sp, color = TvTextSecondary)
                Text("R:R Min 1:2 | Lot 3-5%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvAmber)
            }
        }

        Spacer(Modifier.height(9.dp))
        Text(snapshot.structureExplanation, fontSize = 9.sp, color = TvTextSecondary, lineHeight = 14.sp)
        Spacer(Modifier.height(5.dp))
        Text(
            "Tips Akademi Crypto: Tunggu Breakout & Retest, konfirmasi volume tinggi, dan gunakan posisi 3-5% dari modal agar psikologi trading tetap stabil.",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = TvTextPrimary,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun LevelBox(
    label: String,
    value: Double?,
    distancePct: Double?,
    touchesCount: Int,
    quoteAsset: String,
    modifier: Modifier = Modifier
) {
    Column(modifier.background(TvSurfaceVariant, RoundedCornerShape(10.dp)).padding(9.dp)) {
        Text(label, fontSize = 9.sp, color = TvTextSecondary)
        Text(value?.let { PriceFormatter.formatPrice(it, quoteAsset = quoteAsset) } ?: "Belum ada", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
        Text(
            distancePct?.let { "${String.format(java.util.Locale.US, "%.2f", it)}% (${touchesCount}x disentuh)" } ?: "Tidak tersedia",
            fontSize = 8.sp,
            color = TvTextSecondary
        )
    }
}
