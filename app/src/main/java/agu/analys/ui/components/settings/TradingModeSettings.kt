package agu.analys.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.StrategyMode
import agu.analys.engine.intraday.IntradayConfig
import agu.analys.engine.intraday.IntradaySensitivityPreset
import agu.analys.engine.scalping.PaperAutoTradeConfig
import agu.analys.engine.scalping.ScalpingRuntimeConfig
import agu.analys.engine.scalping.SensitivityPreset
import agu.analys.ui.theme.*

/**
 * Pemilih mode strategi PLUS slider sensitifitas milik mode itu sendiri.
 *
 * Sejak 2026-11-01 slider hidup DI DALAM kartu mode yang bersangkutan: dulu kartu pemilih mode
 * menampilkan angka aktif, lalu di bawahnya ada kartu "PARAMETER ENGINE … AKTIF" kedua yang
 * mengulang angka yang sama, dan slider-nya sendiri ada di dialog layar penuh terpisah. Kerangka
 * kedua dan kedua dialog itu dihapus — jangan dibangun ulang (`AUDIT-2026-11-08.md` §89).
 *
 * Aturan yang dijaga:
 * - Bullet kartu menampilkan config **aktif** (yang benar-benar dipakai engine).
 * - Area di dalam kartu menampilkan **draft**; engine hanya menerima draft setelah
 *   "Simpan & Aktifkan" ditekan. Menutup area = membatalkan draft.
 * - Mode SWING kini punya slider sendiri (`SwingSensitivitySettings`, 18 knob) dengan pola yang
 *   sama: draft sampai "Simpan & Aktifkan", config-nya `SwingConfig`/HOLDER
 *   `SwingRuntimeConfig.current`.
 */
@Composable
fun TradingModeSettings(
    strategyMode: StrategyMode,
    scalpingConfig: ScalpingRuntimeConfig,
    intradayConfig: IntradayConfig,
    swingConfig: agu.analys.engine.swing.SwingConfig,
    draftScalpingConfig: ScalpingRuntimeConfig,
    draftScalpingPreset: String,
    draftPaperAuto: PaperAutoTradeConfig,
    draftIntradayConfig: IntradayConfig,
    draftIntradayPreset: String,
    draftSwingConfig: agu.analys.engine.swing.SwingConfig,
    draftSwingPreset: String,
    expandedMode: StrategyMode?,
    onExpandedChange: (StrategyMode?) -> Unit,
    onStrategyChange: (StrategyMode) -> Unit,
    onDraftScalpingChange: (ScalpingRuntimeConfig, String) -> Unit,
    onDraftScalpingPresetChange: (SensitivityPreset) -> Unit,
    onDraftPaperAutoChange: (PaperAutoTradeConfig) -> Unit,
    onDraftIntradayChange: (IntradayConfig, String) -> Unit,
    onDraftIntradayPresetChange: (IntradaySensitivityPreset) -> Unit,
    onDraftSwingChange: (agu.analys.engine.swing.SwingConfig, String) -> Unit,
    onDraftSwingPresetChange: (agu.analys.engine.swing.SwingSensitivityPreset) -> Unit,
    onSaveScalping: () -> Unit,
    onSaveIntraday: () -> Unit,
    onSaveSwing: () -> Unit
) {
    Column {
        SectionHeader("MODE ANALISIS TRADING")
        Text(
            "Sesuaikan strategi perhitungan engine sinyal and timeframe aktif.",
            color = TvTextSecondary,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))

        ModeOptionCard(
            title = "SCALPING",
            tag = "AGGRESSIVE SCALPING",
            tagBg = TvGreen.copy(alpha = 0.15f),
            tagFg = TvGreen,
            isSelected = strategyMode == StrategyMode.SCALPING,
            expanded = expandedMode == StrategyMode.SCALPING,
            expandLabel = "Atur sensitifitas scalping",
            desc = "Mencari peluang BUY jangka pendek (1M – 15M) secara agresif dengan eksekusi cepat dan filter MTF.",
            bullets = listOf(
                "Bias: 1H · Setup: 15M · Trigger: 1M",
                "Skor LONG: ${scalpingConfig.minScoreLong} · STRONG: ${scalpingConfig.minScoreStrong}",
                "Skor Retest: ${scalpingConfig.minScoreBreakoutRetest} · Net R:R: ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.minNetRr)}",
                "RVOL breakout: ${String.format(java.util.Locale.US, "%.1f", scalpingConfig.rvolBreakoutMin)}x · CHOP: ${String.format(java.util.Locale.US, "%.0f", scalpingConfig.chopHardRanging)}",
                "Risiko: Target R:R ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.targetNetRr)} · Min Risk ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.minRiskPct)}% · Max TP2 ${String.format(java.util.Locale.US, "%.1f", scalpingConfig.maxTp2R)}R",
                "Orderbook: tekanan beli ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.buyPressureBreakout)}x · ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.buyPressureRetest)}x · Slippage ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.slippagePct)}%",
                "Struktur: breakout ${String.format(java.util.Locale.US, "%.3f", scalpingConfig.breakoutAboveFactor)} · retest ${String.format(java.util.Locale.US, "%.3f", scalpingConfig.retestAboveFactor)} · pullback support ${String.format(java.util.Locale.US, "%.1f", scalpingConfig.pullbackSupportMaxDistPct)}%"
            ),
            onClick = {
                onExpandedChange(
                    if (expandedMode == StrategyMode.SCALPING) null else StrategyMode.SCALPING
                )
            }
        ) {
            Text(
                "Nilai di bawah masih draft. Engine Scalping menerima nilai ini setelah tombol Simpan & Aktifkan ditekan.",
                color = TvTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(10.dp))
            ScalpingSensitivitySettings(
                config = draftScalpingConfig,
                activePreset = draftScalpingPreset,
                onConfigChange = onDraftScalpingChange,
                onPresetChange = onDraftScalpingPresetChange
            )
            Spacer(Modifier.height(14.dp))
            PaperAutoTradeSettings(
                config = draftPaperAuto,
                onChange = onDraftPaperAutoChange
            )
            Spacer(Modifier.height(12.dp))
            SaveAndActivateButton(onClick = onSaveScalping)
        }

        Spacer(Modifier.height(10.dp))

        ModeOptionCard(
            title = "SWING",
            tag = "ANALISIS TREND",
            tagBg = TvBlue.copy(alpha = 0.15f),
            tagFg = TvBlue,
            isSelected = strategyMode == StrategyMode.SWING,
            expanded = expandedMode == StrategyMode.SWING,
            expandLabel = "Atur sensitifitas swing",
            desc = "Menganalisis trend jangka menengah (1H – 1D) untuk posisi swing yang lebih tenang.",
            bullets = listOf(
                "Timeframe: 1H & 1D · Analisis struktur trend (HH/HL/LH/LL)",
                "Net R:R min ${fmtNum(swingConfig.minNetRr, 2)} · Skor beli min ${fmtNum(swingConfig.minEntryScore, 0)} · Konfluensi min ${swingConfig.minConfluenceCheckpoints}/6",
                "Filter pucuk: jarak ${fmtNum(swingConfig.maxDistToHighPct, 1)}% · Ekstensi ${fmtNum(swingConfig.maxAtrExtension, 1)}× ATR · RSI jenuh ${fmtNum(swingConfig.overboughtRsi, 0)}",
                "Stop Loss: buffer ${fmtNum(swingConfig.slBufferAtrRejection, 2)}× ATR, jarak ${fmtNum(swingConfig.slMinDistancePct, 1)}–${fmtNum(swingConfig.slMaxDistancePct, 1)}%",
                "Target: ${fmtNum(swingConfig.tp1AtrMult, 1)}× / ${fmtNum(swingConfig.tp2AtrMult, 1)}× ATR · Slippage ${fmtNum(swingConfig.slippagePct, 2)}%"
            ),
            onClick = {
                onExpandedChange(
                    if (expandedMode == StrategyMode.SWING) null else StrategyMode.SWING
                )
            }
        ) {
            Text(
                "Nilai di bawah masih draft. Engine Swing menerima nilai ini setelah tombol Simpan & Aktifkan ditekan.",
                color = TvTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(10.dp))
            SwingSensitivitySettings(
                config = draftSwingConfig,
                activePreset = draftSwingPreset,
                onConfigChange = onDraftSwingChange,
                onPresetChange = onDraftSwingPresetChange
            )
            Spacer(Modifier.height(12.dp))
            SaveAndActivateButton(onClick = onSaveSwing)
        }

        Spacer(Modifier.height(10.dp))

        ModeOptionCard(
            title = "INTRADAY",
            tag = "OPEN PAGI · CLOSE MALAM",
            tagBg = Color(0xFFA5B4FC).copy(alpha = 0.18f),
            tagFg = Color(0xFFA5B4FC),
            isSelected = strategyMode == StrategyMode.OFFICE_DAILY,
            expanded = expandedMode == StrategyMode.OFFICE_DAILY,
            expandLabel = "Atur sensitifitas intraday",
            desc = "Trading harian disiplin sesi (Open Pagi 06:00-11:29 WIB, Close Malam 19:30-23:29 WIB). Batas akhir fase milik fase berikutnya: 11:30 Siang, 15:30 Sore, 19:30 Close Malam, 23:30 Istirahat. Menggunakan histori harga panjang & filter anti-flash dump.",
            bullets = listOf(
                "Siklus: Open Pagi (06:00–11:29) & Close Malam (19:30–23:29 WIB)",
                "Anti Flash Dump: Analisis histori H4 (200 candle) & D1 (100 candle)",
                "Deteksi Trap: Memblokir jebakan fake pump wick & drop mendadak",
                "Kunci Profit: Exit sebelum tengah malam, lindungi kas dari overnight dump",
                "Stop Loss ${fmtNum(intradayConfig.minSlPct, 1)}–${fmtNum(intradayConfig.maxSlPct, 1)}% · lantai biaya ${fmtNum(intradayConfig.minSlFrictionMultiple, 1)}x",
                "TP1 cap ${fmtNum(intradayConfig.maxTp1Pct, 1)}% · Net R:R TP1 min ${fmtNum(intradayConfig.minNetRrTp1, 2)}x"
            ),
            onClick = {
                onExpandedChange(
                    if (expandedMode == StrategyMode.OFFICE_DAILY) null else StrategyMode.OFFICE_DAILY
                )
            }
        ) {
            Text(
                "Nilai di bawah masih draft. Engine Intraday menerima nilai ini setelah tombol Simpan & Aktifkan ditekan.",
                color = TvTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(10.dp))
            IntradaySensitivitySettings(
                config = draftIntradayConfig,
                activePreset = draftIntradayPreset,
                onConfigChange = onDraftIntradayChange,
                onPresetChange = onDraftIntradayPresetChange
            )
            Spacer(Modifier.height(12.dp))
            SaveAndActivateButton(onClick = onSaveIntraday)
        }
    }
}

@Composable
private fun SaveAndActivateButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Simpan & Aktifkan",
            color = Color.Black,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black
        )
    }
}

private fun fmtNum(value: Double, decimals: Int): String =
    String.format(java.util.Locale.US, "%.${decimals}f", value)

@Composable
fun ModeOptionCard(
    title: String,
    tag: String,
    tagBg: Color,
    tagFg: Color,
    isSelected: Boolean,
    expanded: Boolean = false,
    expandLabel: String? = null,
    desc: String,
    bullets: List<String>,
    onClick: () -> Unit,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) TvSurfaceVariant else TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) tagFg else TvBorder
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            // Klik hanya di blok header + bullet: area isi berisi slider dan tombol, jadi kartu
            // tidak boleh tertutup ketika pengguna menyentuh kontrol di dalamnya.
            Column(modifier = Modifier.clickable { onClick() }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isSelected) TvTextPrimary else TvTextSecondary)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(tagBg, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(tag, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = tagFg)
                        }
                    }
                    if (isSelected) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Terpilih", tint = tagFg, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(desc, fontSize = 11.sp, color = TvTextSecondary, lineHeight = 15.sp)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    bullets.forEach { bullet ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(4.dp)
                                    .background(if (isSelected) tagFg else TvTextSecondary, RoundedCornerShape(50))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(bullet, fontSize = 10.sp, color = if (isSelected) TvTextPrimary else TvTextSecondary)
                        }
                    }
                }

                if (expandLabel != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = tagFg,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            expandLabel,
                            color = tagFg,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = tagFg,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (content != null) {
                AnimatedVisibility(visible = expanded) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = TvBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        Spacer(Modifier.height(12.dp))
                        content()
                    }
                }
            }
        }
    }
}
