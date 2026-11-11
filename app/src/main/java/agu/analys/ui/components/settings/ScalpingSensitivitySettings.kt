package agu.analys.ui.components.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import agu.analys.config.FeeSchedule
import agu.analys.engine.scalping.PairEdgeGate
import agu.analys.engine.scalping.ScalpingRuntimeConfig
import agu.analys.engine.scalping.SensitivityPreset
import agu.analys.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

data class SliderGuideInfo(
    val title: String,
    val jsonKey: String,
    val summary: String,
    val plusEffect: String,
    val minusEffect: String,
    val defaultVal: String
)

private val SLIDER_GUIDES = mapOf(
    "min_score_long" to SliderGuideInfo(
        title = "Skor Minimal Beli (LONG)",
        jsonKey = "min_score_long",
        summary = "Syarat nilai kelulusan minimal agar suatu koin direkomendasikan untuk dibeli (skala skor 0 sampai 100).",
        plusEffect = "Sinyal beli lebih jarang muncul, namun koin yang lolos memiliki kualitas teknikal dan konfluensi yang lebih prima.",
        minusEffect = "Sinyal beli lebih sering muncul, cocok saat pasar sedang ramai, namun risiko sinyal palsu sedikit meningkat.",
        defaultVal = "60 (Standar)"
    ),
    "min_score_strong" to SliderGuideInfo(
        title = "Skor Kategori Kuat (STRONG)",
        jsonKey = "min_score_strong",
        summary = "Ambang batas skor untuk memberi label 'STRONG ENTRY' dengan warna hijau mencolok.",
        plusEffect = "Memerlukan konfirmasi momentum dan volume yang sangat dominan sebelum sistem memberi label rekomendasi tertinggi.",
        minusEffect = "Lebih mudah melabeli sinyal sebagai rekomendasi kuat.",
        defaultVal = "75 (Standar)"
    ),
    "min_score_breakout_retest" to SliderGuideInfo(
        title = "Skor Khusus Retest Breakout",
        jsonKey = "min_score_breakout_retest",
        summary = "Ambang batas nilai khusus saat harga menembus batas atas (resisten) lalu memantul kembali untuk menguji support.",
        plusEffect = "Melindungi modal dari jebakan breakout palsu (fake breakout) yang sering menjebak trader pada koin bervolatilitas tinggi.",
        minusEffect = "Memperbolehkan masuk posisi pada pola retest yang lebih longgar.",
        defaultVal = "75 (Disarankan tinggi)"
    ),
    "min_net_rr" to SliderGuideInfo(
        title = "Rasio Profit vs Risiko Bersih (Net R:R)",
        jsonKey = "min_net_rr",
        summary = "Perbandingan potensi keuntungan dibanding risiko rugi (Risk-to-Reward) SETELAH dikurangi biaya fee bursa dan pajak all-in.",
        plusEffect = "Hanya menerima trade yang potensi cuannya jauh melampaui risiko cut-loss-nya. Menjaga akun agar tidak rugi tergerus fee.",
        minusEffect = "Menerima trade dengan ruang target tipis (waspada fee transaksi menggerus hasil akhir).",
        defaultVal = "1.15x (Setara 1:1.15)"
    ),
    "target_net_rr" to SliderGuideInfo(
        title = "Target Profit Bersih Ideal",
        jsonKey = "target_net_rr",
        summary = "Target keuntungan ideal yang dibidik untuk level Take Profit (TP2 / trailing baseline) di atas total friksi transaksi.",
        plusEffect = "Membidik target keuntungan lebih tebal per transaksi, membutuhkan pergerakan harga yang lebih panjang. Menurunkan win-rate minimum agar impas (w* = 1/(1+target)).",
        minusEffect = "Target keuntungan lebih cepat tersentuh, cocok untuk kondisi pergerakan harga yang pendek-pendek — tetapi win-rate minimum agar impas naik.",
        defaultVal = "2.0x (Setara 1:2.0)"
    ),
    "min_friction_multiple" to SliderGuideInfo(
        title = "Lantai Risiko vs Friksi Bursa",
        jsonKey = "min_friction_multiple",
        summary = "Jarak stop-loss minimal sebagai kelipatan biaya transaksi pulang-pergi (fee all-in + slippage). " +
            "Ini penjaga paling penting: bila jarak SL lebih kecil dari beberapa kali biaya, satu kali cut-loss " +
            "menelan banyak unit risiko sekaligus dan matematikanya mustahil untung.",
        plusEffect = "Stop-loss dipaksa lebih lebar dari biaya (mis. 6x friksi ≈ 5,8% di Tokocrypto USDT). " +
            "Trade yang lolos jauh lebih sedikit, tapi win-rate naik drastis dan rata-rata R mendekati/menembus nol.",
        minusEffect = "Stop-loss boleh sangat rapat. Sinyal lebih banyak, tetapi tiap kena SL rugi ~2-3R hanya karena biaya — " +
            "inilah penyebab utama hasil historis negatif.",
        defaultVal = "6.0x friksi (Disarankan, plateau 5–10x) · 0 = nonaktif"
    ),
    "rvol_breakout_min" to SliderGuideInfo(
        title = "Lonjakan Volume Breakout (RVOL)",
        jsonKey = "rvol_breakout_min",
        summary = "Kelipatan volume transaksi saat harga menembus level resisten dibandingkan volume rata-rata 20 candle sebelumnya.",
        plusEffect = "Wajib ada dorongan volume transaksi besar (misal 1.8x lipat) agar penembusan dianggap valid dan bukan manipulasi volume tipis.",
        minusEffect = "Penembusan harga dengan volume biasa saja sudah dianggap sah.",
        defaultVal = "1.5x (Volume 50% di atas rata-rata)"
    ),
    "rvol_setup_min" to SliderGuideInfo(
        title = "Volume Pantulan Setup (RVOL)",
        jsonKey = "rvol_setup_min",
        summary = "Lonjakan volume minimal saat harga memantul dari dasar (support sweep atau pullback recovery).",
        plusEffect = "Memastikan adanya minat beli nyata dari pasar saat harga berada di area lantai support.",
        minusEffect = "Pantulan harga dengan volume relatif sedang tetap diizinkan membentuk setup.",
        defaultVal = "1.2x (Volume 20% di atas rata-rata)"
    ),
    "rvol_retest_min" to SliderGuideInfo(
        title = "Volume Konfirmasi Retest (RVOL)",
        jsonKey = "rvol_retest_min",
        summary = "Volume transaksi yang wajib hadir saat harga menguji kembali garis breakout.",
        plusEffect = "Mencegah masuk pada retest yang sepi pembeli (retest sepi rawan ambles kembali ke bawah).",
        minusEffect = "Toleran terhadap pantulan retest bervolume lebih rendah.",
        defaultVal = "1.5x (Ketelitian tinggi)"
    ),
    "chop_hard_ranging" to SliderGuideInfo(
        title = "Batas Pasar Galau (CHOP Hard Ranging)",
        jsonKey = "chop_hard_ranging",
        summary = "Indikator pasar mondar-mandir / sideways tanpa tren (Choppiness Index). Angka tinggi berarti pasar sangat tidak beraturan.",
        plusEffect = "Lebih cepat menahan/memblokir sinyal tren jika pasar terdeteksi mulai bergerak menyamping tanpa arah jelas.",
        minusEffect = "Tetap mengizinkan pencarian sinyal meskipun kondisi pasar sedang agak choppy.",
        defaultVal = "65 (Nilai 60-70 = zona sideways ketat)"
    ),
    "retest_max_dist_pct" to SliderGuideInfo(
        title = "Jarak Toleransi Retest (%)",
        jsonKey = "retest_max_dist_pct",
        summary = "Jarak maksimal (dalam persen) antara harga saat ini dengan garis support penembusan agar masih dianggap 'sedang retest'.",
        plusEffect = "Harga harus sangat dekat/presisi menyentuh garis support (misal selisih di bawah 1.0%).",
        minusEffect = "Memberikan ruang toleransi pantulan yang lebih lebar dari garis support.",
        defaultVal = "1.2% (Toleransi wajar kripto)"
    ),
    "retest_min_structure_strength" to SliderGuideInfo(
        title = "Kekokohan Garis Struktur (Strength)",
        jsonKey = "retest_min_structure_strength",
        summary = "Kualitas garis support/resisten (skor 0-100) berdasarkan seberapa sering garis tersebut berhasil menahan harga di masa lalu.",
        plusEffect = "Hanya mempercayai level-level penting yang sudah berkali-kali teruji kuat oleh pasar.",
        minusEffect = "Level support/resisten yang baru terbentuk sebentar pun dapat dijadikan patokan pantulan.",
        defaultVal = "60 (Struktur solid)"
    ),
    "max_change_24h_pct" to SliderGuideInfo(
        title = "Batas Kenaikan 24 Jam (Extended Move)",
        jsonKey = "max_change_24h_pct",
        summary = "Bila koin sudah naik melebihi persentase ini dalam 24 jam, sistem menahan sinyal beli. " +
            "Membeli di ekor kenaikan ekstrem berarti mengejar sisa gerakan yang sebagian besar sudah terjadi, " +
            "dan biasanya justru tepat di dekat puncak harian.",
        plusEffect = "Koin yang sudah naik banyak tetap boleh dibeli — lebih banyak sinyal, tetapi risiko " +
            "masuk di puncak dan langsung terkoreksi ikut naik.",
        minusEffect = "Lebih banyak koin ditahan. Contoh nyata: AMP/USDT masuk saat sudah +28% dalam 24 jam, " +
            "lalu menahan drawdown −2,76% selama 9 menit.",
        // Default 0: replay GNS menunjukkan ambang 25% memotong justru fase tren yang sehat
        // (4/4 menang, avg R +0,178) — jadi gerbang diserahkan ke pengguna.
        defaultVal = "0 = nonaktif (default)"
    )
)

@Composable
fun ScalpingSensitivitySettings(
    config: ScalpingRuntimeConfig,
    activePreset: String,
    onConfigChange: (ScalpingRuntimeConfig, String) -> Unit,
    onPresetChange: (SensitivityPreset) -> Unit
) {
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var activeGuideKey by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = TvGreen, modifier = Modifier.size(18.dp))
            Text(
                "SENSITIFITAS ENGINE SCALPING",
                color = TvTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Sesuaikan ambang batas keputusan engine. Tombol (+) dan (-) memudahkan fine-tuning tanpa perlu menyeret slider. Ketuk ikon (i) di tiap pengaturan untuk melihat penjelasan bahasa awam.",
            color = TvTextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        Spacer(Modifier.height(12.dp))

        // Preset Selector Row
        Text("PILIHAN PRESET", color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SensitivityPreset.entries.forEach { preset ->
                val isSelected = activePreset.equals(preset.name, ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) TvGreen.copy(alpha = 0.2f) else TvSurfaceVariant,
                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, TvGreen) else null,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onPresetChange(preset) }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            preset.label,
                            color = if (isSelected) TvGreen else TvTextPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // P2 — Mode exit paper trade (paritas dengan AUDIT-2026-11-08.md)
        ExitModeSection(
            params = config.exit,
            onChange = { onConfigChange(config.withExit(it), "CUSTOM") }
        )

        Spacer(Modifier.height(14.dp))

        // P4.3 — Gerbang kelayakan pair berbasis edge (MFE horizon vs friksi)
        PairEdgeGateCard(
            quote = "USDT",
            frictionPct = FeeSchedule.roundTripRate(
                exchange = "TOKOCRYPTO",
                quoteAsset = "USDT",
                useMaker = false,
                slippagePct = config.slippagePct
            ) * 100.0
        )

        Spacer(Modifier.height(14.dp))

        // Sliders List Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // 1. Min Score Long
                SensitivitySliderItem(
                    label = "Skor Min LONG",
                    jsonKey = "min_score_long",
                    value = config.minScoreLong.toFloat(),
                    range = 45f..80f,
                    step = 1f,
                    displayValue = "${config.minScoreLong}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(minScoreLong = newVal.roundToInt()), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "min_score_long" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 2. Min Score Strong
                SensitivitySliderItem(
                    label = "Skor STRONG",
                    jsonKey = "min_score_strong",
                    value = config.minScoreStrong.toFloat(),
                    range = 60f..90f,
                    step = 1f,
                    displayValue = "${config.minScoreStrong}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(minScoreStrong = newVal.roundToInt()), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "min_score_strong" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 3. Min Score Retest
                SensitivitySliderItem(
                    label = "Skor Retest",
                    jsonKey = "min_score_breakout_retest",
                    value = config.minScoreBreakoutRetest.toFloat(),
                    range = 60f..90f,
                    step = 1f,
                    displayValue = "${config.minScoreBreakoutRetest}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(minScoreBreakoutRetest = newVal.roundToInt()), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "min_score_breakout_retest" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 4. Min Net R:R
                SensitivitySliderItem(
                    label = "Min Net R:R",
                    jsonKey = "min_net_rr",
                    value = config.minNetRr.toFloat(),
                    range = 0.8f..2.0f,
                    step = 0.05f,
                    displayValue = String.format(Locale.US, "%.2fR", config.minNetRr),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 100.0).roundToInt() / 100.0
                        onConfigChange(config.copy(minNetRr = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "min_net_rr" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 5. Target Net R:R
                SensitivitySliderItem(
                    label = "Target Net R:R",
                    jsonKey = "target_net_rr",
                    value = config.targetNetRr.toFloat(),
                    range = 1.0f..2.5f,
                    step = 0.05f,
                    displayValue = String.format(Locale.US, "%.2fR", config.targetNetRr),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 100.0).roundToInt() / 100.0
                        onConfigChange(config.copy(targetNetRr = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "target_net_rr" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 5b. Lantai risiko vs friksi (2026-10-12)
                SensitivitySliderItem(
                    label = "Lantai Risiko vs Friksi",
                    jsonKey = "min_friction_multiple",
                    value = config.minFrictionMultiple.toFloat(),
                    range = 0f..10f,
                    step = 0.5f,
                    displayValue = if (config.minFrictionMultiple <= 0.0) "Nonaktif"
                    else String.format(Locale.US, "%.1fx friksi", config.minFrictionMultiple),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 10.0).roundToInt() / 10.0
                        onConfigChange(config.copy(minFrictionMultiple = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "min_friction_multiple" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 6. RVOL Breakout
                SensitivitySliderItem(
                    label = "RVOL Breakout",
                    jsonKey = "rvol_breakout_min",
                    value = config.rvolBreakoutMin.toFloat(),
                    range = 1.0f..2.5f,
                    step = 0.1f,
                    displayValue = String.format(Locale.US, "%.1fx", config.rvolBreakoutMin),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 10.0).roundToInt() / 10.0
                        onConfigChange(config.copy(rvolBreakoutMin = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "rvol_breakout_min" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 7. RVOL Setup
                SensitivitySliderItem(
                    label = "RVOL Setup",
                    jsonKey = "rvol_setup_min",
                    value = config.rvolSetupMin.toFloat(),
                    range = 0.8f..2.0f,
                    step = 0.1f,
                    displayValue = String.format(Locale.US, "%.1fx", config.rvolSetupMin),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 10.0).roundToInt() / 10.0
                        onConfigChange(config.copy(rvolSetupMin = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "rvol_setup_min" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 8. RVOL Retest
                SensitivitySliderItem(
                    label = "RVOL Retest",
                    jsonKey = "rvol_retest_min",
                    value = config.rvolRetestMin.toFloat(),
                    range = 1.0f..2.5f,
                    step = 0.1f,
                    displayValue = String.format(Locale.US, "%.1fx", config.rvolRetestMin),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 10.0).roundToInt() / 10.0
                        onConfigChange(config.copy(rvolRetestMin = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "rvol_retest_min" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 9. CHOP Hard Ranging
                SensitivitySliderItem(
                    label = "CHOP Hard Ranging",
                    jsonKey = "chop_hard_ranging",
                    value = config.chopHardRanging.toFloat(),
                    range = 55f..75f,
                    step = 1f,
                    displayValue = String.format(Locale.US, "%.0f", config.chopHardRanging),
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(chopHardRanging = newVal.toDouble()), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "chop_hard_ranging" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 10. Jarak Max Retest %
                SensitivitySliderItem(
                    label = "Jarak Max Retest %",
                    jsonKey = "retest_max_dist_pct",
                    value = config.retestMaxDistPct.toFloat(),
                    range = 0.5f..2.5f,
                    step = 0.1f,
                    displayValue = String.format(Locale.US, "%.1f%%", config.retestMaxDistPct),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 10.0).roundToInt() / 10.0
                        onConfigChange(config.copy(retestMaxDistPct = rounded), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "retest_max_dist_pct" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 11. Strength Struktur Retest
                SensitivitySliderItem(
                    label = "Strength Struktur Retest",
                    jsonKey = "retest_min_structure_strength",
                    value = config.retestMinStructureStrength.toFloat(),
                    range = 40f..80f,
                    step = 5f,
                    displayValue = "${config.retestMinStructureStrength}",
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(retestMinStructureStrength = newVal.roundToInt()), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "retest_min_structure_strength" }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))

                // 12. S9 — Batas kenaikan 24 jam (extended move)
                SensitivitySliderItem(
                    label = "Batas Kenaikan 24 Jam",
                    jsonKey = "max_change_24h_pct",
                    value = config.maxChange24hPct.toFloat(),
                    range = 0f..60f,
                    step = 5f,
                    displayValue = if (config.maxChange24hPct <= 0.0) "Nonaktif"
                    else String.format(Locale.US, "%.0f%%", config.maxChange24hPct),
                    onValueChange = { newVal ->
                        onConfigChange(config.copy(maxChange24hPct = newVal.toDouble()), "CUSTOM")
                    },
                    onShowInfo = { activeGuideKey = "max_change_24h_pct" }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Action Buttons: Export JSON & Import JSON & Reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val jsonStr = config.toJsonString(2)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("scalp_config.json", jsonStr)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "JSON scalp_config berhasil disalin ke clipboard!", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export JSON", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = {
                    importText = ""
                    importError = null
                    showImportDialog = true
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import JSON", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            IconButton(
                onClick = {
                    onPresetChange(SensitivityPreset.DEFAULT)
                    Toast.makeText(context, "Threshold direset ke default ScalpingConfig", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                    .size(42.dp)
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = "Reset Default", tint = TvTextSecondary)
            }
        }
    }

    // Floating Info Popup (Dialog)
    activeGuideKey?.let { key ->
        val info = SLIDER_GUIDES[key]
        if (info != null) {
            Dialog(onDismissRequest = { activeGuideKey = null }) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = TvSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = TvCyan.copy(alpha = 0.15f),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Info,
                                            contentDescription = null,
                                            tint = TvCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Column {
                                    Text(
                                        info.title,
                                        color = TvTextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        info.jsonKey,
                                        color = TvTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            IconButton(
                                onClick = { activeGuideKey = null },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Penjelasan Sederhana
                        Text(
                            "PENJELASAN AWAM",
                            color = TvCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            info.summary,
                            color = TvTextPrimary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        // Efek Jika Dinaikkan (+)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = TvGreen.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TvGreen.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🟢 Jika Dinaikkan (+)", color = TvGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(info.plusEffect, color = TvTextPrimary, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Efek Jika Diturunkan (-)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = TvAmber.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TvAmber.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🟡 Jika Diturunkan (-)", color = TvAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(info.minusEffect, color = TvTextPrimary, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Nilai Disarankan: ${info.defaultVal}",
                                color = TvTextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            )

                            Button(
                                onClick = { activeGuideKey = null },
                                colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Mengerti", color = TvTextPrimary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        Dialog(onDismissRequest = { showImportDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = TvSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "Import scalp_config.json",
                        color = TvTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tempel teks JSON konfigurasi scalping di bawah ini:",
                        color = TvTextSecondary,
                        fontSize = 11.sp
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importError = null
                        },
                        placeholder = { Text("{ \"min_score_long\": 60, ... }", color = TvTextSecondary.copy(alpha = 0.5f), fontSize = 11.sp) },
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvGreen,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = TvTextPrimary,
                            unfocusedTextColor = TvTextPrimary
                        )
                    )

                    if (importError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(importError ?: "", color = TvRed, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showImportDialog = false }) {
                            Text("Batal", color = TvTextSecondary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val parsed = ScalpingRuntimeConfig.fromJsonString(importText.trim())
                                if (parsed != null) {
                                    onConfigChange(parsed, "CUSTOM")
                                    showImportDialog = false
                                    Toast.makeText(context, "Konfigurasi scalping berhasil diimpor!", Toast.LENGTH_SHORT).show()
                                } else {
                                    importError = "Format JSON tidak valid atau parsing gagal."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                        ) {
                            Text("Terapkan", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SensitivitySliderItem(
    label: String,
    jsonKey: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    onShowInfo: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(label, color = TvTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Surface(
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onShowInfo() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Penjelasan $label",
                            tint = TvCyan.copy(alpha = 0.8f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = TvGreen.copy(alpha = 0.15f),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    displayValue,
                    color = TvGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        // Row containing Minus [-], Slider, and Plus [+]
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Minus Button
            Surface(
                shape = CircleShape,
                color = TvSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable {
                        val nextVal = (value - step).coerceIn(range.start, range.endInclusive)
                        onValueChange(nextVal)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Kurangi $label",
                        tint = if (value > range.start) TvTextPrimary else TvTextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Slider in center
            val stepsCount = ((range.endInclusive - range.start) / step).roundToInt() - 1
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onValueChange,
                valueRange = range,
                steps = if (stepsCount > 0) stepsCount else 0,
                colors = SliderDefaults.colors(
                    thumbColor = TvGreen,
                    activeTrackColor = TvGreen,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
            )

            // Plus Button
            Surface(
                shape = CircleShape,
                color = TvSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable {
                        val nextVal = (value + step).coerceIn(range.start, range.endInclusive)
                        onValueChange(nextVal)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Tambah $label",
                        tint = if (value < range.endInclusive) TvTextPrimary else TvTextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// P2 — Pemilih mode exit paper trade
// ---------------------------------------------------------------------------

/**
 * Pemilih mode exit paper trade (P2).
 *
 * Mode di sini memakai kunci yang sama dengan `cfg["exit"]["mode"]` di
 * `AUDIT-2026-11-08.md`, sehingga hasil paper di app bisa dibandingkan langsung
 * dengan replay offline. Lihat `AGENT_BRIDGE.md`.
 */
@Composable
private fun ExitModeSection(
    params: agu.analys.engine.scalping.ScalpExitParams,
    onChange: (agu.analys.engine.scalping.ScalpExitParams) -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "MODE EXIT PAPER TRADE",
                    color = TvTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Penjelasan mode exit",
                    tint = TvTextSecondary,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { showInfo = !showInfo }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Menentukan bagaimana posisi simulasi ditutup. Mode ini hanya berlaku untuk paper trade.",
                color = TvTextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            AnimatedVisibility(visible = showInfo) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TvSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "Catatan penting: replay historis dengan fee all-in belum menunjukkan edge stabil pada semua mode. " +
                                    "Mode exit hanya memperbaiki besarnya kerugian, belum terbukti menghasilkan expectancy positif. " +
                                    "Jangan aktifkan eksekusi uang riil dari sinyal scalp.",
                                color = TvOrange,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            agu.analys.engine.scalping.ScalpExitMode.entries.forEach { mode ->
                val isSelected = params.mode == mode
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) TvGreen.copy(alpha = 0.2f) else TvSurface,
                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, TvGreen) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clickable { onChange(params.copy(mode = mode)) }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                            mode.label,
                            color = if (isSelected) TvGreen else TvTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                        Text(
                            mode.description,
                            color = TvTextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 13.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Parameter yang hanya relevan untuk mode terkait
            if (params.mode.usesTp1Partial || params.mode.usesEarlyProfit) {
                ExitParamRow(
                    label = "Profit net minimum early exit",
                    value = params.minEarlyProfitNetPct,
                    step = 0.05,
                    range = 0.10..2.00,
                    suffix = "%",
                    enabled = params.mode.usesEarlyProfit,
                    hint = "Angka ini adalah laba bersih minimal yang memicu jual awal (EARLY_PROFIT). " +
                        "\"Net\" = sudah dipotong seluruh biaya: fee beli & jual plus slippage dua sisi. " +
                        "Jadi 0,20% berarti hasil akhirnya ± +0,20% dari modal. " +
                        "\"Awal\" = dijual segera setelah itu, tanpa menunggu SL, target, atau time-stop.",
                    onValueChange = { onChange(params.copy(minEarlyProfitNetPct = it)) }
                )
            }
            if (params.mode.usesBreakeven) {
                ExitParamRow(
                    label = "Trigger breakeven (net)",
                    value = params.breakevenTriggerNetPct,
                    step = 0.05,
                    range = 0.10..2.00,
                    suffix = "%",
                    enabled = true,
                    hint = "Setelah laba bersih menembus angka ini, stop loss dinaikkan ke HARGA IMPAS " +
                        "— harga jual yang menghasilkan ±0 setelah semua biaya. Mulai detik itu, " +
                        "posisi tidak bisa lagi kembali rugi penuh dari harga belinya.",
                    onValueChange = { onChange(params.copy(breakevenTriggerNetPct = it)) }
                )
            }
            if (params.mode.usesTrailing) {
                ExitParamRow(
                    label = "Jarak trailing dari peak",
                    value = params.trailPct,
                    step = 0.05,
                    range = 0.10..2.00,
                    suffix = "%",
                    enabled = true,
                    hint = "Setelah trailing aktif, stop loss mengikuti puncak harga (peak) posisi. " +
                        "Posisi dijual bila harga turun sejauh angka ini dari puncaknya — " +
                        "keuntungan kenaikan yang sudah terjadi ikut terkunci. " +
                        "Trailing sendiri baru berjalan setelah laba bersih menyentuh trigger breakeven.",
                    onValueChange = { onChange(params.copy(trailPct = it)) }
                )
            }

            // S2 — lantai profit: exit profit sukarela minimal sekian kali friksi round-trip.
            ExitParamRow(
                label = "Lantai profit (x friksi)",
                value = params.minProfitFrictionMultiple,
                step = 0.5,
                range = 0.0..5.0,
                suffix = "x",
                enabled = params.mode != agu.analys.engine.scalping.ScalpExitMode.CLASSIC,
                hint = "Filter keluasan untuk jual-profit. Friksi per paket: fee beli + fee jual + " +
                    "slippage 2 sisi (contoh Tokocrypto USDT tarif bawaan ≈ 0,97%). " +
                    "Dengan 2×, keluasan bruto posisi harus ≥ 2 × friksi (≈ 1,94%) baru dianggap layak " +
                    "untuk memicu jual profit; di bawah itu posisi tetap ditunggu untuk exit " +
                    "breakeven/trailing/time-stop. 0 = tanpa lantai.",
                onValueChange = { onChange(params.copy(minProfitFrictionMultiple = it)) }
            )
            // S8 — trigger BE proporsional ATR (menang atas nilai persen bila ATR tersedia).
            if (params.mode.usesBreakeven) {
                ExitParamRow(
                    label = "Trigger BE (x ATR)",
                    value = params.beTriggerAtrFraction,
                    step = 0.1,
                    range = 0.0..2.0,
                    suffix = "x ATR",
                    enabled = true,
                    hint = "Alternatif trigger breakeven dalam satuan ATR (Average True Range — " +
                        "ukuran rata-rata naik-turun harga per candle). Engine memakai yang LEBIH BESAR " +
                        "antara persen trigger di atas dan (ATR × angka ini). " +
                        "Contoh: ATR 3% × 0,5 = 1,5% → trigger BE jadi 1,5%. " +
                        "Koin yang bergerak deras otomatis menuntut kenaiakan lebih jauh sebelum BE berjalan, " +
                        "sehingga SL impas tidak cepat tergeser ke atas tanda-tanda normal pasar.",
                    onValueChange = { onChange(params.copy(beTriggerAtrFraction = it)) }
                )
            }
            // S8 — jarak trailing proporsional ATR.
            if (params.mode.usesTrailing) {
                ExitParamRow(
                    label = "Trailing (x ATR)",
                    value = params.trailAtrFraction,
                    step = 0.1,
                    range = 0.0..2.0,
                    suffix = "x ATR",
                    enabled = true,
                    hint = "Sama seperti jarak trailing di atas, tetapi dalam ATR: jarak efektif = " +
                        "ATR × angka ini. Dipakai bila ATR tersedia; kalau tidak, kembali ke persen di atas. " +
                        "Contoh: ATR 2% × 0,6 = 1,2% — SL berjalan 1,2% di bawah puncak harga. " +
                        "Koin deras = trailing lebih lebar, koin tenang = lebih rapat.",
                    onValueChange = { onChange(params.copy(trailAtrFraction = it)) }
                )
            }
            ExitParamRow(
                label = "Time-stop (bar M1, 0 = nonaktif)",
                value = params.maxHoldBarsM1.toDouble(),
                step = 5.0,
                range = 0.0..240.0,
                suffix = " bar",
                enabled = true,
                hint = "Usia maksimal posisi, dihitung dalam candle 1 menit. Bila selama itu tidak " +
                    "menyentuh SL/target/exit lain, posisi dijual apapun hasilnya (label TIME_STOP). " +
                    "1 bar = 1 menit, jadi 90 bar = 1,5 jam. 0 menonaktifkannya — posisi bisa " +
                    "menggantung tanpa exit.",
                onValueChange = { onChange(params.copy(maxHoldBarsM1 = it.roundToInt())) }
            )
        }
    }
}

@Composable
private fun ExitParamRow(
    label: String,
    value: Double,
    step: Double,
    range: ClosedFloatingPointRange<Double>,
    suffix: String,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
    hint: String? = null
) {
    val alpha = if (enabled) 1f else 0.4f
    // Penjelasan dalam bahasa awam tergantung tersedia/hidupnya parameter (2026-10-26).
    // Trader pemula tidak bisa menebak arti "net", "breakeven", atau "x ATR" tanpa itu.
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = TvTextSecondary.copy(alpha = alpha),
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = CircleShape,
                color = TvSurface,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(enabled = enabled) {
                        onValueChange((value - step).coerceIn(range.start, range.endInclusive))
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Kurangi $label",
                        tint = TvTextPrimary.copy(alpha = alpha),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(
                if (step >= 1.0) "${value.roundToInt()}$suffix"
                else String.format(Locale.US, "%.2f", value) + suffix,
                color = TvTextPrimary.copy(alpha = alpha),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .widthIn(min = 52.dp)
            )
            Surface(
                shape = CircleShape,
                color = TvSurface,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(enabled = enabled) {
                        onValueChange((value + step).coerceIn(range.start, range.endInclusive))
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Tambah $label",
                        tint = TvTextPrimary.copy(alpha = alpha),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        if (hint != null) {
            Text(
                hint,
                color = TvTextSecondary.copy(alpha = alpha),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
