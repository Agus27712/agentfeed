package agu.analys.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.scalping.PaperAutoBuyAllocation
import agu.analys.engine.scalping.PaperAutoTradeConfig
import agu.analys.model.ScalpingStage
import agu.analys.ui.theme.TvBorder
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvOrange
import agu.analys.ui.theme.TvSurface
import agu.analys.ui.theme.TvSurfaceVariant
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.ExchangeRateManager
import agu.analys.util.PriceFormatter

/**
 * Pengaturan auto-buy & auto-exit **paper trade** (P2).
 *
 * Seluruh sakelar di sini hanya memengaruhi posisi **simulasi**. Tidak ada kontrol di sini
 * yang mengaktifkan eksekusi uang riil dari sinyal scalp — sesuai `AUDIT-2026-11-08.md` §P2 dan
 * larangan di `AGENTS.md`.
 */
@Composable
fun PaperAutoTradeSettings(
    config: PaperAutoTradeConfig,
    onChange: (PaperAutoTradeConfig) -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    // Kurs dipakai hanya untuk menampilkan ekuivalen. Konversi yang sebenarnya terjadi saat
    // sinyal masuk, memakai pair yang bersangkutan.
    val usdtIdrRate by ExchangeRateManager.usdtIdrRate.collectAsState()

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = TvGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "PAPER TRADE OTOMATIS",
                    color = TvTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Penjelasan",
                    tint = TvTextSecondary,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { showInfo = !showInfo }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Membuka dan menutup posisi SIMULASI secara otomatis mengikuti sinyal scalping. Tidak pernah mengirim order uang riil.",
                color = TvTextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            if (showInfo) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TvSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Auto-buy memakai alokasi di bawah; auto-exit memakai mode exit yang dipilih di bagian atas. " +
                                "Replay historis dengan fee all-in belum menunjukkan edge stabil, jadi fitur ini untuk MENGUJI, " +
                                "bukan untuk dianggap menghasilkan profit.",
                            color = TvOrange,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            AutoTradeToggle(
                label = "Auto-buy posisi simulasi",
                description = "Buka posisi simulasi saat sinyal scalp terkonfirmasi",
                checked = config.autoBuyEnabled,
                onCheckedChange = { onChange(config.copy(autoBuyEnabled = it)) }
            )

            AutoTradeToggle(
                label = "Auto-exit sesuai mode",
                description = "Tutup posisi simulasi mengikuti mode exit terpilih",
                checked = config.autoExitEnabled,
                onCheckedChange = { onChange(config.copy(autoExitEnabled = it)) }
            )

            if (config.autoBuyEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "ALOKASI POSISI",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PaperAutoBuyAllocation.entries.forEach { alloc ->
                        val selected = config.allocation == alloc
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) TvGreen.copy(alpha = 0.2f) else TvSurface,
                            border = if (selected) {
                                androidx.compose.foundation.BorderStroke(1.dp, TvGreen)
                            } else {
                                androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onChange(config.copy(allocation = alloc)) }
                        ) {
                            Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)) {
                                Text(
                                    alloc.label,
                                    color = if (selected) TvGreen else TvTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    alloc.description,
                                    color = TvTextSecondary,
                                    fontSize = 9.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "STAGE MINIMUM SINYAL",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(ScalpingStage.ENTRY, ScalpingStage.STRONG_ENTRY).forEach { stage ->
                        val selected = config.minStage == stage
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) TvGreen.copy(alpha = 0.2f) else TvSurface,
                            border = if (selected) {
                                androidx.compose.foundation.BorderStroke(1.dp, TvGreen)
                            } else {
                                androidx.compose.foundation.BorderStroke(1.dp, TvBorder)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onChange(config.copy(minStage = stage)) }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    stage.displayName,
                                    color = if (selected) TvGreen else TvTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (config.allocation == PaperAutoBuyAllocation.FIXED_QUOTE) {
                    StepperRow(
                        label = "Nominal per posisi (Rp)",
                        valueText = PriceFormatter.formatMoney(config.fixedIdrAmount, "IDR"),
                        onMinus = {
                            onChange(config.copy(fixedIdrAmount = (config.fixedIdrAmount - 10_000.0).coerceAtLeast(10_000.0)))
                        },
                        onPlus = {
                            onChange(config.copy(fixedIdrAmount = (config.fixedIdrAmount + 10_000.0).coerceAtMost(100_000_000.0)))
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (usdtIdrRate > 0.0) {
                            "Dikonversi per pair saat sinyal: ≈ " +
                                PriceFormatter.formatPrice(
                                    config.fixedIdrAmount / usdtIdrRate,
                                    quoteAsset = "USDT"
                                ) +
                                " untuk pair USDT (kurs " +
                                PriceFormatter.formatPrice(usdtIdrRate, quoteAsset = "IDR") + ")."
                        } else {
                            "Kurs USDT/IDR belum tersedia — auto-buy pair USDT dilewati sampai kurs masuk."
                        },
                        color = if (usdtIdrRate > 0.0) TvTextSecondary else TvOrange,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                } else {
                    StepperRow(
                        label = "Risiko per trade",
                        valueText = String.format(java.util.Locale.US, "%.1f%%", config.riskPctPerTrade),
                        onMinus = {
                            onChange(config.copy(riskPctPerTrade = (config.riskPctPerTrade - 0.1).coerceAtLeast(0.1)))
                        },
                        onPlus = {
                            onChange(config.copy(riskPctPerTrade = (config.riskPctPerTrade + 0.1).coerceAtMost(10.0)))
                        }
                    )
                }

                Spacer(Modifier.height(10.dp))

                StepperRow(
                    label = "Maks. posisi bersamaan (0 = tanpa batas)",
                    valueText = if (config.maxConcurrentPositions <= 0) {
                        "Tanpa batas"
                    } else {
                        config.maxConcurrentPositions.toString()
                    },
                    onMinus = {
                        onChange(
                            config.copy(
                                maxConcurrentPositions =
                                    (config.maxConcurrentPositions - 1).coerceAtLeast(0)
                            )
                        )
                    },
                    onPlus = {
                        onChange(
                            config.copy(
                                maxConcurrentPositions =
                                    (config.maxConcurrentPositions + 1).coerceAtMost(20)
                            )
                        )
                    }
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    "Auto-buy berhenti membuka posisi baru bila jumlah posisi paper aktif sudah " +
                        "mencapai batas ini (dihitung lintas bursa, termasuk posisi beli manual). " +
                        "Beli manual tetap bebas.",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    "JEDA ENTRI-ULANG",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                StepperRow(
                    label = "Setelah sell, tunggu sebelum beli ulang",
                    valueText = "${config.reentryCooldownMinutes} menit",
                    onMinus = {
                        onChange(
                            config.copy(
                                reentryCooldownMinutes =
                                    (config.reentryCooldownMinutes - 1).coerceIn(1, 30)
                            )
                        )
                    },
                    onPlus = {
                        onChange(
                            config.copy(
                                reentryCooldownMinutes =
                                    (config.reentryCooldownMinutes + 1).coerceIn(1, 30)
                            )
                        )
                    }
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    "Begitu posisi paper dijual, auto-buy menunggu jeda ini sebelum boleh membuka " +
                        "posisi ulang pada pair yang sama (1–30 menit). Fungsinya mencegah " +
                        "beli-ulang seketika setelah sell saat harga masih naik kuat — biaya buy & sell " +
                        "dibayar beruntun tanpa evaluasi baru (terukur: entri ulang hanya 60 detik " +
                        "setelah sell pada WINUSDT 25/10/2026). Beli manual tidak dijeda; " +
                        "auto-exit tidak terpengaruh.",
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun AutoTradeToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TvTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(description, color = TvTextSecondary, fontSize = 10.sp, lineHeight = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TvGreen,
                checkedTrackColor = TvGreen.copy(alpha = 0.35f)
            )
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TvTextSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = TvSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
            modifier = Modifier.clickable { onMinus() }
        ) {
            Text(
                "−",
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Text(
            valueText,
            color = TvTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = TvSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
            modifier = Modifier.clickable { onPlus() }
        ) {
            Text(
                "+",
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
