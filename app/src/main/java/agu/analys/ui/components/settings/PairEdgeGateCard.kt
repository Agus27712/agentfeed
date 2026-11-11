package agu.analys.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.scalping.PairEdgeGate
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvSurfaceVariant
import agu.analys.ui.theme.TvTextMuted
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import java.util.Locale

/**
 * Kartu gerbang kelayakan pair berbasis edge (P4.3).
 *
 * Menjawab pertanyaan yang sering luput: **apakah pair ini secara matematis bisa profit?**
 * Bila median pergerakan harga 7 jam lebih kecil daripada friksi round-trip, tidak ada
 * strategi yang bisa untung di pair itu — berapa pun bagusnya sinyal.
 *
 * @param quote kuota pair (`USDT` / `IDR`)
 * @param frictionPct friksi round-trip (%) yang sedang dipakai
 */
@Composable
fun PairEdgeGateCard(
    quote: String,
    frictionPct: Double
) {
    var expanded by remember { mutableStateOf(false) }

    val eligible = remember(quote, frictionPct) {
        PairEdgeGate.mfeTable(quote).keys
            .map { PairEdgeGate.evaluate(it, quote, frictionPct) }
            .filter { it.eligible == true }
            .sortedByDescending { it.ratio ?: 0.0 }
    }
    val ineligible = remember(quote, frictionPct) { PairEdgeGate.ineligiblePairs(quote, frictionPct) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FilterAlt, contentDescription = null,
                    tint = TvAmber, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "GERBANG EDGE PAIR",
                    color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Pair hanya layak di-scalp bila median pergerakan harga (MFE) dalam horizon " +
                    "trading minimal ${fmt(PairEdgeGate.MIN_MFE_FRICTION_RATIO)}× friksi round-trip. " +
                    "Bila median pergerakan < friksi (rasio < 1×), pair itu mustahil profit — " +
                    "biaya lebih besar daripada pergerakan median. Rasio 1–2× secara teori mungkin " +
                    "tetapi marginnya terlalu tipis untuk diandalkan.",
                color = TvTextSecondary, fontSize = 11.sp, lineHeight = 15.sp
            )

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Friksi round-trip", color = TvTextMuted, fontSize = 10.sp)
                    Text(
                        "${fmt(frictionPct)}% ($quote)",
                        color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Layak / tidak layak", color = TvTextMuted, fontSize = 10.sp)
                    Text(
                        "${eligible.size} / ${ineligible.size}",
                        color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (ineligible.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Semua pair terpantau lolos gerbang edge.",
                    color = TvGreen, fontSize = 11.sp
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${ineligible.size} pair TIDAK layak di bursa ini",
                        color = TvRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null, tint = TvTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        ineligible.forEach { e ->
                            val capture = e.medianMfe7hPct?.let {
                                PairEdgeGate.captureRequiredPct(it, frictionPct)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp, Color.White.copy(alpha = 0.06f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val impossible = (e.ratio ?: 1.0) < 1.0
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        e.pair, color = TvTextPrimary, fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (impossible) {
                                        Spacer(Modifier.size(6.dp))
                                        Text(
                                            "MUSTAHIL",
                                            color = TvRed, fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else {
                                        Spacer(Modifier.size(6.dp))
                                        Text(
                                            "tipis",
                                            color = TvAmber, fontSize = 9.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                                Text(
                                    "MFE ${fmt(e.medianMfe7hPct ?: 0.0)}% · ${e.ratioText} friksi" +
                                        (capture?.let { " · butuh tangkap ${fmt(it)}%" } ?: ""),
                                    color = TvTextMuted, fontSize = 10.sp
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Sumber: median MFE 7 jam, 30 hari data M1 riil " +
                                "(arsip riset, lihat AUDIT-2026-11-08.md).",
                            color = TvTextMuted, fontSize = 9.sp, lineHeight = 12.sp
                        )
                    }
                }
            }
        }
    }
}

private fun fmt(v: Double): String =
    String.format(Locale.US, if (v < 10.0) "%.2f" else "%.0f", v)
