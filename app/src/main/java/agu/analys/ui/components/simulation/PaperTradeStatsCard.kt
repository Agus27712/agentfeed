package agu.analys.ui.components.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.scalping.PaperExitReason
import agu.analys.engine.scalping.PaperTradeRecord
import agu.analys.engine.scalping.PaperTradeStats
import agu.analys.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kartu ringkasan performa paper trade (P2).
 *
 * Menampilkan akumulasi **R net setelah fee all-in**, win rate, profit factor, dan rincian
 * alasan exit. Dua hal yang sengaja dibuat eksplisit karena dicatat sebagai kelemahan replay
 * di `AGENT_BRIDGE.md`:
 * - trade `TIMEOUT` ditampilkan terpisah, tidak dibuang diam-diam;
 * - breakeven (R ≈ 0) tidak dihitung sebagai kekalahan.
 */
@Composable
fun PaperTradeStatsCard(
    stats: PaperTradeStats,
    records: List<PaperTradeRecord>,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showInfo by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.QueryStats,
                    contentDescription = null,
                    tint = TvAmber,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "STATISTIK PAPER TRADE",
                    color = TvTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (stats.hasData) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Kosongkan jurnal paper trade",
                        tint = if (confirmReset) TvRed else TvTextSecondary,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { confirmReset = !confirmReset }
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Penjelasan statistik",
                    tint = TvTextSecondary,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { showInfo = !showInfo }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Akumulasi R net setelah buy fee + sell fee + 2x slippage. Bisa dibandingkan " +
                    "langsung dengan AUDIT-2026-11-08.md.",
                color = TvTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )

            if (confirmReset) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TvRed.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Kosongkan ${records.size} baris jurnal paper trade? Saldo simulasi tidak ikut direset.",
                            color = TvRed,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = TvRed,
                                modifier = Modifier.clickable {
                                    onReset()
                                    confirmReset = false
                                }
                            ) {
                                Text(
                                    "Ya, kosongkan",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = TvSurface,
                                modifier = Modifier.clickable { confirmReset = false }
                            ) {
                                Text(
                                    "Batal",
                                    color = TvTextPrimary,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (showInfo) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TvSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Win rate dihitung dari trade yang benar-benar keluar; breakeven tidak " +
                                "dihitung sebagai kalah. Profit factor = total R menang / total |R kalah|; " +
                                "kosong bila belum ada trade rugi. Timeout/time-stop dicatat terpisah dan " +
                                "tidak dimasukkan ke rata-rata R.",
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (!stats.hasData) {
                Text(
                    "Belum ada trade paper yang selesai. Statistik terisi otomatis setiap kali " +
                        "posisi simulasi ditutup oleh FSM exit di atas.",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
                return@Column
            }

            // ── Metrik utama ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TvSurface)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BigMetric(
                    "TOTAL R",
                    String.format(Locale.US, "%+.2fR", stats.totalR),
                    if (stats.totalR >= 0.0) TvGreen else TvRed,
                    Modifier.weight(1f)
                )
                BigMetric(
                    "RATA-RATA R",
                    String.format(Locale.US, "%+.2fR", stats.avgR),
                    if (stats.avgR >= 0.0) TvGreen else TvRed,
                    Modifier.weight(1f)
                )
                BigMetric(
                    "WIN RATE",
                    String.format(Locale.US, "%.1f%%", stats.winRate),
                    TvTextPrimary,
                    Modifier.weight(1f)
                )
                BigMetric(
                    "PROFIT FACTOR",
                    stats.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                    if ((stats.profitFactor ?: 0.0) >= 1.0) TvGreen else TvAmber,
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Hitungan hasil ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CountPill("Menang", stats.wins, TvGreen, Modifier.weight(1f))
                CountPill("Kalah", stats.losses, TvRed, Modifier.weight(1f))
                CountPill("Breakeven", stats.breakevens, TvAmber, Modifier.weight(1f))
                CountPill("Timeout", stats.timeouts, TvTextSecondary, Modifier.weight(1f))
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "${stats.resolvedTrades} trade selesai dari ${stats.totalTrades} evaluasi" +
                    " · rata-rata menang " + String.format(Locale.US, "%+.2fR", stats.avgWinR) +
                    " · rata-rata kalah " + String.format(Locale.US, "%+.2fR", stats.avgLossR),
                color = TvTextMuted,
                fontSize = 9.sp,
                lineHeight = 12.sp
            )

            // ── Rincian alasan exit ───────────────────────────────────────────
            if (stats.byExitReason.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "RINCIAN ALASAN EXIT",
                    color = TvTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))

                val maxCount = stats.byExitReason.values.maxOrNull() ?: 1
                stats.byExitReason.entries
                    .sortedByDescending { it.value }
                    .forEach { (reason, count) ->
                        ExitReasonRow(
                            reason = reason,
                            count = count,
                            fraction = if (maxCount > 0) count.toFloat() / maxCount else 0f
                        )
                    }
            }

            // ── Jurnal terakhir ───────────────────────────────────────────────
            if (records.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "JURNAL TERAKHIR",
                    color = TvTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                records.takeLast(4).reversed().forEach { record ->
                    JournalRow(record)
                }
            }

            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = TvOrange.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Replay historis dengan fee all-in belum menunjukkan edge stabil pada semua mode. " +
                        "Angka di atas hanya alat ukur — jangan jadikan dasar untuk mengaktifkan " +
                        "eksekusi uang riil dari sinyal scalp.",
                    color = TvOrange,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun BigMetric(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, color = TvTextMuted, fontSize = 8.sp)
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun CountPill(label: String, count: Int, color: Color, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, color = TvTextMuted, fontSize = 8.sp)
        Text(
            "$count",
            color = if (count > 0) color else TvTextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ExitReasonRow(reason: PaperExitReason, count: Int, fraction: Float) {
    val color = when (reason) {
        PaperExitReason.TP2, PaperExitReason.EARLY_PROFIT -> TvGreen
        PaperExitReason.TRAIL_SL -> TvCyan
        PaperExitReason.BE_SL -> TvAmber
        PaperExitReason.SL -> TvRed
        PaperExitReason.TIMEOUT -> TvTextSecondary
        PaperExitReason.BAD_RISK -> TvOrange
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            reason.label,
            color = TvTextSecondary,
            fontSize = 9.sp,
            modifier = Modifier.width(96.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(TvSurface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color.copy(alpha = 0.8f))
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "$count",
            color = TvTextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp)
        )
    }
}

private val journalTimeFormat = SimpleDateFormat("HH:mm", Locale.US)

@Composable
private fun JournalRow(record: PaperTradeRecord) {
    val rColor = when {
        record.r > 0.0 -> TvGreen
        record.r < 0.0 -> TvRed
        else -> TvAmber
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            record.symbol,
            color = TvTextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            record.mode.label,
            color = TvTextMuted,
            fontSize = 9.sp,
            modifier = Modifier.width(76.dp)
        )
        Text(
            record.reason.label,
            color = TvTextSecondary,
            fontSize = 9.sp,
            modifier = Modifier.width(88.dp)
        )
        Text(
            String.format(Locale.US, "%+.2fR", record.r),
            color = rColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(52.dp)
        )
        Text(
            if (record.closedAt > 0L) journalTimeFormat.format(Date(record.closedAt)) else "—",
            color = TvTextMuted,
            fontSize = 9.sp
        )
    }
}
