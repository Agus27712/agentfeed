package agu.analys.ui.components.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import agu.analys.engine.swing.SwingConfig
import agu.analys.engine.swing.SwingRuntimeConfig
import agu.analys.engine.swing.SwingSensitivityPreset
import agu.analys.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Slider sensitifitas mode **SWING** (`StrategyMode.SWING`).
 *
 * Dipisahkan dari [ScalpingSensitivitySettings] dan [IntradaySensitivitySettings] secara sengaja:
 * parameter satu mode tidak boleh menumpang config mode lain (aturan §79.1 `app/AGENTS.md`) —
 * geseran satu slider di sini hanya boleh mengubah sinyal SWING.
 *
 * Semua slider di sini **wajib dibaca** `SwingEvaluator` — tidak ada knob dekorasi. Penjaganya
 * `SwingConfigEnforcementTest`: setiap field `SwingConfig` muncul sebagai `config.<field>` di
 * evaluator dan sebagai `config.copy(<field> = …)` di berkas ini.
 *
 * Yang **tidak** dijanjikan layar ini: profit. Slider hanya membuka/mencegat frekuensi sinyal;
 * tidak ada konfigurasi yang bisa dijadikan jaminan untung.
 */
private data class SwingGuideInfo(
    val title: String,
    val jsonKey: String,
    val summary: String,
    val plusEffect: String,
    val minusEffect: String,
    val defaultVal: String,
    /** Bukan null = knob ini menyentuh perilaku yang perlu catatan kepemilikan angka. */
    val ownerNote: String? = null
)

private val SWING_GUIDES = mapOf(
    "min_net_rr" to SwingGuideInfo(
        title = "Net R:R Minimum",
        jsonKey = "min_net_rr",
        summary = "Perbandingan potensi untung dan risiko setelah dikurangi seluruh biaya " +
            "(fee beli + fee jual + slippage dua sisi). Di engine SWING, angka ini dihitung dari " +
            "target kedua (TP2) — itu perilaku engine lama, bukan aturan baru.",
        plusEffect = "Sinyal lebih sedikit; hanya trade dengan ruang untung jauh melampaui " +
            "risikonya yang lolos.",
        minusEffect = "Sinyal lebih banyak; di bawah 1,0 potensi untung lebih kecil daripada " +
            "risikonya. Slider ini tetap berlaku di bawah 1,4 karena gerbang memakai angka mentah.",
        defaultVal = "2,0 (bawaan)",
        ownerNote = "Gerbang R:R SWING menghitung dari TP2 (perilaku engine lama). Menggeser " +
            "slider ini mengubah ambang keputusan, bukan cara angkanya dihitung."
    ),
    "min_entry_score" to SwingGuideInfo(
        title = "Skor Minimum Sinyal Beli",
        jsonKey = "min_entry_score",
        summary = "Skor beli akhir (gabungan setup, RSI, EMA, MACD, volume, struktur, dan pola) " +
            "yang harus dilampaui agar rekomendasi BUY keluar.",
        plusEffect = "Sinyal lebih jarang, tetapi hanya yang berlapis konfirmasi yang lolos.",
        minusEffect = "Sinyal lebih sering muncul; lebih banyak sinyal lemah ikut lolos.",
        defaultVal = "42 (bawaan)"
    ),
    "min_confluence_checkpoints" to SwingGuideInfo(
        title = "Jumlah Konfluensi Minimum",
        jsonKey = "min_confluence_checkpoints",
        summary = "Dari 6 penyaring konfluensi (MTF, Area of Value, Volume, Price Action, " +
            "Momentum, R:R), entry swing hanya boleh bila minimal sebanyak ini lolos.",
        plusEffect = "Entry lebih selektif; pada 6 semua penyaring wajib lolos.",
        minusEffect = "Entry lebih cepat terbuka, termasuk saat sebagian penyaring belum lolos.",
        defaultVal = "4 dari 6 (bawaan)"
    ),
    "min_buy_sell_ratio" to SwingGuideInfo(
        title = "Ketentuan Skor Beli > Skor Jual",
        jsonKey = "min_buy_sell_ratio",
        summary = "Skor beli harus melebihi skor jual dengan rasio minimal ini. Dipakai di dua " +
            "tempat: pencet sinyal saat jual dominan, dan gerbang akhir sebelum BUY keluar.",
        plusEffect = "Sinyal lebih ketat menghadapi tekanan jual; lebih banyak koin tertahan.",
        minusEffect = "Sinyal lebih mudah lolos walau tekanan jual masih terasa.",
        defaultVal = "1,15× (bawaan)"
    ),
    "overbought_rsi" to SwingGuideInfo(
        title = "Ambang RSI Jenuh Beli",
        jsonKey = "overbought_rsi",
        summary = "Bila RSI melewati angka ini, koin dianggap jenuh beli: sinyal beli dicegat " +
            "dan skor tekanan jual dinaikkan.",
        plusEffect = "Jenuh beli lebih jarang tercapai — lebih banyak sinyal lolos, termasuk " +
            "saat harga sudah memanas.",
        minusEffect = "Sinyal dicegat lebih awal — lebih banyak koin tertahan padahal tren " +
            "masih sehat.",
        defaultVal = "72 (bawaan)"
    ),
    "max_atr_extension" to SwingGuideInfo(
        title = "Ekstensi Harga dari Rata-rata (ATR)",
        jsonKey = "max_atr_extension",
        summary = "Jarak harga saat ini terhadap rata-rata harganya, diukur dalam kelipatan " +
            "ATR. Lebih dari angka ini dianggap harga sudah lari terlalu jauh (overextended) " +
            "dan entry dicegat.",
        plusEffect = "Harga boleh lari lebih dulu sebelum dicegat — lebih banyak entry pada " +
            "harga yang sudah bertengger.",
        minusEffect = "Entry dicegat lebih awal — lebih banyak sinyal tertahan karena harga " +
            "dianggap sudah terlalu jauh.",
        defaultVal = "1,8× ATR (bawaan)"
    ),
    "strong_volume_ratio" to SwingGuideInfo(
        title = "Ambang Volume Kuat",
        jsonKey = "strong_volume_ratio",
        summary = "Volume candle terakhir dibagi rata-rata volume beberapa candle sebelumnya. " +
            "Melewati angka ini dianggap volume kuat dan menambah skor setup.",
        plusEffect = "Konfirmasi volume lebih sulit tercapai — setup pantulan/tembus " +
            "membutuhkan lonjakan volume yang jelas.",
        minusEffect = "Volume yang biasa saja sudah dianggap kuat — lebih banyak setup lolos " +
            "konfirmasi volume.",
        defaultVal = "1,25× (bawaan)"
    ),
    "near_level_pct" to SwingGuideInfo(
        title = "Jarak yang Dianggap 'Di Level'",
        jsonKey = "near_level_pct",
        summary = "Jarak harga ke garis support/resistance (persen harga) yang masih dianggap " +
            "berada di level penting. Setup klasik hanya diakui di level.",
        plusEffect = "Area 'di level' lebih lebar — lebih banyak setup pantulan/tembus dianggap " +
            "sah.",
        minusEffect = "Hanya harga yang benar-benar menempel di level yang diakui — lebih banyak " +
            "setup dianggap tidak di level.",
        defaultVal = "1,2% (bawaan)"
    ),
    "rejection_body_ratio_min" to SwingGuideInfo(
        title = "Badan Candle Pantulan Minimum",
        jsonKey = "rejection_body_ratio_min",
        summary = "Perbandingan badan candle dengan tinggi candle penuhnya. Candle pantulan " +
            "harus menutup dengan badan yang cukup padat (minimal rasio ini) agar disebut " +
            "REJECTION.",
        plusEffect = "Candle pantulan harus lebih padat — lebih sedikit pantulan yang dihitung.",
        minusEffect = "Candle yang badannya lemah juga dihitung pantulan — lebih banyak sinyal.",
        defaultVal = "0,45 (bawaan)"
    ),
    "breakout_body_ratio_min" to SwingGuideInfo(
        title = "Badan Candle Tembus Minimum",
        jsonKey = "breakout_body_ratio_min",
        summary = "Candle yang menembus level harus menutup dengan badan padat minimal rasio " +
            "ini, agar tembusnya dianggap kuat, bukan tembus setengah hati.",
        plusEffect = "Tembusan harus lebih kuat — candle yang nyaris tembus tidak dihitung.",
        minusEffect = "Tembusan kecil juga dihitung sah — lebih sering muncul, risiko tembus " +
            "palsu naik.",
        defaultVal = "0,40 (bawaan)"
    ),
    "max_dist_to_high_pct" to SwingGuideInfo(
        title = "Jarak Maksimum ke Harga Tertinggi ('Di Pucuk')",
        jsonKey = "max_dist_to_high_pct",
        summary = "Bila harga hanya lebih rendah persen ini dari harga tertinggi beberapa candle " +
            "terakhir, entry diblokir supaya tidak membeli tepat di pucuk.",
        plusEffect = "Zona 'pucuk' lebih lebar — lebih banyak sinyal diblokir saat harga " +
            "menempel puncak.",
        minusEffect = "Zona 'pucuk' lebih sempit — lebih banyak sinyal lolos walau harga memang " +
            "sudah tinggi.",
        defaultVal = "1,2% (bawaan)"
    ),
    "sl_buffer_atr_rejection" to SwingGuideInfo(
        title = "Stop Loss di Bawah Level (× ATR) — cabang Pantulan/Retest",
        jsonKey = "sl_buffer_atr_rejection",
        summary = "Stop loss diletakkan sedikit di bawah low candle pantulan/support, diukur " +
            "dalam kelipatan ATR. Berlaku untuk cabang sinyal REJECTION, RETEST, dan RECLAIM.",
        plusEffect = "Stop lebih dalam — lebih tahan sentuhan palsu, kerugian per kalah lebih " +
            "besar.",
        minusEffect = "Stop menempel di level — lebih cepat tersapu, kerugian per kalah lebih " +
            "tipis.",
        defaultVal = "0,20× ATR (bawaan)",
        ownerNote = "Cabang BREAKOUT dan cabang tanpa setup memakai nilai internalnya sendiri " +
            "(0,15× dan 0,25× ATR) supaya bawaan tetap persis engine lama."
    ),
    "sl_atr_max_rejection" to SwingGuideInfo(
        title = "Stop Loss Maksimum dari Harga (× ATR) — cabang Pantulan/Retest",
        jsonKey = "sl_atr_max_rejection",
        summary = "Batas atas jarak stop loss diukur dalam ATR supaya stop tidak meledak ketika " +
            "pasar bergejolak. Berlaku untuk cabang REJECTION, RETEST, dan RECLAIM.",
        plusEffect = "Stop bisa lebih lebar mengikuti gejolak — posisi bertahan lebih lama.",
        minusEffect = "Stop dipaksa lebih rapat — lebih sering terpotong oleh gejolak asli.",
        defaultVal = "1,4× ATR (bawaan)"
    ),
    "sl_max_distance_pct" to SwingGuideInfo(
        title = "Jarak Stop Loss Maksimum (%)",
        jsonKey = "sl_max_distance_pct",
        summary = "Stop loss tidak boleh lebih jauh dari persen harga ini, apa pun hasilnya.",
        plusEffect = "Kerugian per kalah bisa lebih besar, tetapi posisi lebih tahan terhadap " +
            "gerakan wajar.",
        minusEffect = "Kerugian per kalah lebih terkendali, tetapi lebih mudah tersentuh.",
        defaultVal = "7,0% (bawaan)"
    ),
    "sl_min_distance_pct" to SwingGuideInfo(
        title = "Jarak Stop Loss Minimum (%)",
        jsonKey = "sl_min_distance_pct",
        summary = "Stop loss tidak boleh lebih rapat dari persen harga ini. Saringan terpenting " +
            "di sini: stop yang lebih rapat dari beberapa kali biaya transaksi membuat " +
            "matematikanya praktis mustahil untung.",
        plusEffect = "Stop dipaksa lebih lebar dari biaya — lebih aman secara matematis, " +
            "tetapi kerugian per kalah lebih besar.",
        minusEffect = "Stop boleh lebih rapat; di bawah satu kali biaya pulang-pergi, trade " +
            "nyaris mustahil untung.",
        defaultVal = "1,5% (bawaan)"
    ),
    "tp1_atr_mult" to SwingGuideInfo(
        title = "Target Profit 1 (× ATR)",
        jsonKey = "tp1_atr_mult",
        summary = "Target pertama dihitung dari harga beli ditambah kelipatan ATR ini. Aturan " +
            "lainnya tetap berlaku: target tidak boleh di bawah resistance atau 7% (nilai " +
            "internal), jadi kadang target ikut naik melewati hasil perhitungan ini.",
        plusEffect = "Target pertama lebih jauh — potensi satu ketuk lebih besar, lebih jarang " +
            "tercapai.",
        minusEffect = "Target pertama lebih dekat — lebih sering tercapai. Catatan: lantai " +
            "internal 7% tetap menahannya agar tidak menurun ke bawah itu.",
        defaultVal = "2,0× ATR (bawaan)"
    ),
    "tp2_atr_mult" to SwingGuideInfo(
        title = "Target Profit 2 (× ATR)",
        jsonKey = "tp2_atr_mult",
        summary = "Target kedua dihitung dari harga beli ditambah kelipatan ATR ini. Target " +
            "kedua itulah yang dipakai menghitung gerbang R:R masuk (perilaku engine lama).",
        plusEffect = "Target kedua lebih jauh — gerbang R:R lebih mudah lolos pada koin yang " +
            "mengijinkan ruang.",
        minusEffect = "Target kedua dipotong — lebih sedikit trade yang lolos gerbang R:R.",
        defaultVal = "3,4× ATR (bawaan)"
    ),
    "slippage_pct" to SwingGuideInfo(
        title = "Asumsi Slippage per Sisi",
        jsonKey = "slippage_pct",
        summary = "Perkiraan selisih harga saat order benar-benar tereksekusi, per sisi (beli " +
            "dan jual). Dipakai menghitung net R:R pada sinyal SWING.",
        plusEffect = "Perhitungan lebih pesimistis — target harus lebih jauh untuk lolos.",
        minusEffect = "Perhitungan lebih optimistis — lebih banyak sinyal lolos, tetapi angka " +
            "harapan di layar bisa lebih indah daripada kenyataan.",
        defaultVal = "0,08% (bawaan)",
        ownerNote = "Ini hanya slippage perhitungan sinyal SWING. Basis laba/rugi simulasi " +
            "aplikasi tetap satu nilai global dan TIDAK ikut berubah."
    )
)

@Composable
fun SwingSensitivitySettings(
    config: SwingConfig,
    activePreset: String,
    onConfigChange: (SwingConfig, String) -> Unit,
    onPresetChange: (SwingSensitivityPreset) -> Unit
) {
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var activeGuideKey by remember { mutableStateOf<String?>(null) }

    fun update(next: SwingConfig) = onConfigChange(next, "CUSTOM")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = TvGreen, modifier = Modifier.size(18.dp))
            Text(
                "SENSITIFITAS ENGINE SWING",
                color = TvTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Sesuaikan ambang mode Swing. Tombol (+) dan (-) memudahkan penyetelan halus. " +
                "Ketuk ikon (i) di tiap pengaturan untuk penjelasan bahasa awam.",
            color = TvTextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(12.dp))
        Text(
            "PILIHAN PRESET",
            color = TvTextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SwingSensitivityPreset.entries.forEach { preset ->
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

        Spacer(Modifier.height(6.dp))
        Text(
            SwingSensitivityPreset.entries
                .firstOrNull { it.name.equals(activePreset, ignoreCase = true) }
                ?.description ?: SwingSensitivityPreset.CUSTOM.description,
            color = TvTextSecondary,
            fontSize = 10.sp,
            lineHeight = 13.sp
        )

        Spacer(Modifier.height(14.dp))

        GroupCard("GERBANG MASUK") {
            SwingSliderItem(
                label = "Net R:R minimum",
                value = config.minNetRr,
                range = 1.0..4.0,
                step = 0.05,
                suffix = "",
                decimals = 2,
                guideKey = "min_net_rr",
                warning = true,
                onShowInfo = { activeGuideKey = "min_net_rr" },
                onValueChange = { update(config.copy(minNetRr = it)) }
            )
            SwingSliderItem(
                label = "Skor minimum sinyal beli",
                value = config.minEntryScore,
                range = 20.0..80.0,
                step = 1.0,
                suffix = "",
                decimals = 0,
                guideKey = "min_entry_score",
                onShowInfo = { activeGuideKey = "min_entry_score" },
                onValueChange = { update(config.copy(minEntryScore = it)) }
            )
            SwingSliderItem(
                label = "Jumlah konfluensi minimum",
                value = config.minConfluenceCheckpoints.toDouble(),
                range = 0.0..6.0,
                step = 1.0,
                suffix = "/6",
                decimals = 0,
                guideKey = "min_confluence_checkpoints",
                onShowInfo = { activeGuideKey = "min_confluence_checkpoints" },
                onValueChange = { update(config.copy(minConfluenceCheckpoints = it.roundToInt())) }
            )
            SwingSliderItem(
                label = "Tekanan beli vs jual",
                value = config.minBuySellRatio,
                range = 1.0..3.0,
                step = 0.05,
                suffix = "×",
                decimals = 2,
                guideKey = "min_buy_sell_ratio",
                onShowInfo = { activeGuideKey = "min_buy_sell_ratio" },
                onValueChange = { update(config.copy(minBuySellRatio = it)) }
            )
            SwingSliderItem(
                label = "Ambang RSI jenuh beli",
                value = config.overboughtRsi,
                range = 55.0..90.0,
                step = 1.0,
                suffix = "",
                decimals = 0,
                guideKey = "overbought_rsi",
                onShowInfo = { activeGuideKey = "overbought_rsi" },
                onValueChange = { update(config.copy(overboughtRsi = it)) }
            )
        }

        Spacer(Modifier.height(10.dp))

        GroupCard("DETEKSI SETUP & FILTER PUCUK") {
            SwingSliderItem(
                label = "Ambang volume kuat",
                value = config.strongVolumeRatio,
                range = 1.0..3.0,
                step = 0.05,
                suffix = "×",
                decimals = 2,
                guideKey = "strong_volume_ratio",
                onShowInfo = { activeGuideKey = "strong_volume_ratio" },
                onValueChange = { update(config.copy(strongVolumeRatio = it)) }
            )
            SwingSliderItem(
                label = "Jarak 'di level' (persen)",
                value = config.nearLevelPct,
                range = 0.2..5.0,
                step = 0.1,
                suffix = "%",
                decimals = 1,
                guideKey = "near_level_pct",
                onShowInfo = { activeGuideKey = "near_level_pct" },
                onValueChange = { update(config.copy(nearLevelPct = it)) }
            )
            SwingSliderItem(
                label = "Badan candle pantulan minimum",
                value = config.rejectionBodyRatioMin,
                range = 0.2..0.9,
                step = 0.05,
                suffix = "",
                decimals = 2,
                guideKey = "rejection_body_ratio_min",
                onShowInfo = { activeGuideKey = "rejection_body_ratio_min" },
                onValueChange = { update(config.copy(rejectionBodyRatioMin = it)) }
            )
            SwingSliderItem(
                label = "Badan candle tembus minimum",
                value = config.breakoutBodyRatioMin,
                range = 0.2..0.9,
                step = 0.05,
                suffix = "",
                decimals = 2,
                guideKey = "breakout_body_ratio_min",
                onShowInfo = { activeGuideKey = "breakout_body_ratio_min" },
                onValueChange = { update(config.copy(breakoutBodyRatioMin = it)) }
            )
            SwingSliderItem(
                label = "Jarak maksimum ke harga tertinggi",
                value = config.maxDistToHighPct,
                range = 0.0..10.0,
                step = 0.1,
                suffix = "%",
                decimals = 1,
                guideKey = "max_dist_to_high_pct",
                onShowInfo = { activeGuideKey = "max_dist_to_high_pct" },
                onValueChange = { update(config.copy(maxDistToHighPct = it)) }
            )
            SwingSliderItem(
                label = "Ekstensi maksimum (× ATR)",
                value = config.maxAtrExtension,
                range = 0.5..5.0,
                step = 0.1,
                suffix = "× ATR",
                decimals = 1,
                guideKey = "max_atr_extension",
                onShowInfo = { activeGuideKey = "max_atr_extension" },
                onValueChange = { update(config.copy(maxAtrExtension = it)) }
            )
        }

        Spacer(Modifier.height(10.dp))

        GroupCard("RISIKO — STOP LOSS (CABANG PANTULAN/RETEST)") {
            SwingSliderItem(
                label = "Buffer SL di bawah level",
                value = config.slBufferAtrRejection,
                range = 0.0..1.5,
                step = 0.05,
                suffix = "× ATR",
                decimals = 2,
                guideKey = "sl_buffer_atr_rejection",
                warning = true,
                onShowInfo = { activeGuideKey = "sl_buffer_atr_rejection" },
                onValueChange = { update(config.copy(slBufferAtrRejection = it)) }
            )
            SwingSliderItem(
                label = "SL maksimum (× ATR)",
                value = config.slAtrMaxRejection,
                range = 0.5..4.0,
                step = 0.1,
                suffix = "× ATR",
                decimals = 1,
                guideKey = "sl_atr_max_rejection",
                onShowInfo = { activeGuideKey = "sl_atr_max_rejection" },
                onValueChange = { update(config.copy(slAtrMaxRejection = it)) }
            )
            SwingSliderItem(
                label = "Jarak SL maksimum",
                value = config.slMaxDistancePct,
                range = 1.0..15.0,
                step = 0.5,
                suffix = "%",
                decimals = 1,
                guideKey = "sl_max_distance_pct",
                onShowInfo = { activeGuideKey = "sl_max_distance_pct" },
                onValueChange = { update(config.copy(slMaxDistancePct = it)) }
            )
            SwingSliderItem(
                label = "Jarak SL minimum",
                value = config.slMinDistancePct,
                range = 0.0..6.0,
                step = 0.1,
                suffix = "%",
                decimals = 1,
                guideKey = "sl_min_distance_pct",
                onShowInfo = { activeGuideKey = "sl_min_distance_pct" },
                onValueChange = { update(config.copy(slMinDistancePct = it)) }
            )
        }

        Spacer(Modifier.height(10.dp))

        GroupCard("TARGET PROFIT") {
            SwingSliderItem(
                label = "Target 1 (× ATR)",
                value = config.tp1AtrMult,
                range = 0.5..6.0,
                step = 0.1,
                suffix = "× ATR",
                decimals = 1,
                guideKey = "tp1_atr_mult",
                onShowInfo = { activeGuideKey = "tp1_atr_mult" },
                onValueChange = { update(config.copy(tp1AtrMult = it)) }
            )
            SwingSliderItem(
                label = "Target 2 (× ATR)",
                value = config.tp2AtrMult,
                range = 0.5..8.0,
                step = 0.1,
                suffix = "× ATR",
                decimals = 1,
                guideKey = "tp2_atr_mult",
                onShowInfo = { activeGuideKey = "tp2_atr_mult" },
                onValueChange = { update(config.copy(tp2AtrMult = it)) }
            )
        }

        Spacer(Modifier.height(10.dp))

        GroupCard("SARINGAN R:R & BIAYA") {
            SwingSliderItem(
                label = "Asumsi slippage per sisi",
                value = config.slippagePct,
                range = 0.0..0.50,
                step = 0.01,
                suffix = "%",
                decimals = 2,
                guideKey = "slippage_pct",
                warning = true,
                onShowInfo = { activeGuideKey = "slippage_pct" },
                onValueChange = { update(config.copy(slippagePct = it)) }
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(
                        "swing_config.json",
                        SwingRuntimeConfig.toJsonString(config)
                    )
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        context,
                        "JSON konfigurasi swing disalin ke papan klip",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = TvBlue),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Salin JSON", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                Text("Tempel JSON", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            IconButton(
                onClick = {
                    onPresetChange(SwingSensitivityPreset.STANDARD)
                    Toast.makeText(
                        context,
                        "Ambang direset ke bawaan SwingConfig",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier
                    .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                    .size(42.dp)
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = "Reset ke bawaan", tint = TvTextSecondary)
            }
        }
    }

    activeGuideKey?.let { key ->
        SWING_GUIDES[key]?.let { info ->
            SwingGuideDialog(info = info, onDismiss = { activeGuideKey = null })
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
                        "Tempel JSON Konfigurasi Swing",
                        color = TvTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Salin JSON konfigurasi swing ke bawah ini untuk mengganti seluruh ambang:",
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
                        placeholder = {
                            Text(
                                "{ \"min_net_rr\": 2.0, \"min_entry_score\": 42.0, ... }",
                                color = TvTextSecondary.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        },
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        isError = importError != null
                    )

                    importError?.let { pesan ->
                        Spacer(Modifier.height(6.dp))
                        Text(pesan, color = TvRed, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                SwingRuntimeConfig.fromJsonString(importText.trim())?.let { parsed ->
                                    update(parsed)
                                    showImportDialog = false
                                } ?: run {
                                    importError = "JSON tidak bisa dibaca sebagai konfigurasi Swing."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TvGreen),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Terapkan", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { showImportDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Tutup", color = TvTextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                color = TvCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SwingSliderItem(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    suffix: String,
    guideKey: String,
    onShowInfo: () -> Unit,
    onValueChange: (Double) -> Unit,
    decimals: Int = 1,
    warning: Boolean = false
) {
    // Nilai bisa berada di luar band (mis. config lama/ekspor tangan). Ditampilkan apa adanya
    // tetapi slider-nya dijepit ke band: geseran pertama akan membawa nilai masuk band.
    val clamped = value.coerceIn(range.start, range.endInclusive)
    val valueText = formatValue(value, decimals) + suffix
    val rangeStart = range.start.toFloat()
    val rangeEnd = range.endInclusive.toFloat()
    val stepF = step.toFloat()
    val stepsCount = ((rangeEnd - rangeStart) / stepF).roundToInt() - 1

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
                Text(
                    label,
                    color = if (warning) TvAmber else TvTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
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
                color = if (warning) TvAmber.copy(alpha = 0.15f) else TvGreen.copy(alpha = 0.15f),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    valueText,
                    color = if (warning) TvAmber else TvGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        if (warning) {
            Text(
                "Nilai ini menyentuh keputusan yang sudah diukur/diputuskan owner — lihat (i)",
                color = TvAmber.copy(alpha = 0.75f),
                fontSize = 9.5.sp
            )
        }

        Spacer(Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = TvSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable {
                        val next = (clamped - step).coerceIn(range.start, range.endInclusive)
                        onValueChange(next)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Kurangi $label",
                        tint = if (clamped > range.start) TvTextPrimary else TvTextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Slider(
                value = clamped.toFloat(),
                onValueChange = { onValueChange(it.toDouble()) },
                valueRange = rangeStart..rangeEnd,
                steps = if (stepsCount > 0) stepsCount else 0,
                colors = SliderDefaults.colors(
                    thumbColor = if (warning) TvAmber else TvGreen,
                    activeTrackColor = if (warning) TvAmber else TvGreen,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
            )

            Surface(
                shape = CircleShape,
                color = TvSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable {
                        val next = (clamped + step).coerceIn(range.start, range.endInclusive)
                        onValueChange(next)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Tambah $label",
                        tint = if (clamped < range.endInclusive) TvTextPrimary else TvTextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
    }
}

private fun formatValue(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

@Composable
private fun SwingGuideDialog(info: SwingGuideInfo, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = TvSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, TvBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
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
                                "Kunci JSON: ${info.jsonKey} • Bawaan: ${info.defaultVal}",
                                color = TvTextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = TvTextSecondary
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(info.summary, color = TvTextPrimary, fontSize = 11.sp, lineHeight = 15.sp)

                Spacer(Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TvGreen.copy(alpha = 0.08f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TvGreen.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Jika Dinaikkan (+)",
                            color = TvGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(info.plusEffect, color = TvTextPrimary, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TvAmber.copy(alpha = 0.08f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TvAmber.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Jika Diturunkan (-)",
                            color = TvAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(info.minusEffect, color = TvTextPrimary, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }

                if (info.ownerNote != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = TvCyan.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvCyan.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "Catatan Kepemilikan Angka",
                                color = TvCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(info.ownerNote, color = TvTextPrimary, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }
        }
    }
}
