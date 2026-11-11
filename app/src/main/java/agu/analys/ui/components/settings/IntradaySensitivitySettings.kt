package agu.analys.ui.components.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import agu.analys.engine.intraday.IntradayConfig
import agu.analys.engine.intraday.IntradayRuntimeConfig
import agu.analys.engine.intraday.IntradaySensitivityPreset
import agu.analys.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Slider sensitifitas mode **INTRADAY** (`StrategyMode.OFFICE_DAILY`).
 *
 * Dipisahkan dari [ScalpingSensitivitySettings] secara sengaja: `app/AGENTS.md` §79.1 melarang
 * parameter intraday menumpang config/slider scalping, karena geseran satu slider scalping akan
 * diam-diam mengubah sinyal intraday.
 *
 * Yang **tidak** dijanjikan layar ini: profit. Sinyal intraday terukur negatif pada semua
 * konfigurasi yang menghasilkan trade (§79.3 `AUDIT-2026-11-08.md`), jadi teks di sini menyebut
 * "frekuensi sinyal", bukan "setting terbaik".
 *
 * Dua knob sengaja diberi label peringatan karena nilainya sudah diputuskan/diukur owner
 * (`minNetRrTp1` = 1,5 dan `maxTp1Pct` = 5,0%). Slider tetap boleh digeser untuk eksperimen,
 * tetapi UI wajib menyatakan bahwa hasilnya di luar angka yang sudah diukur.
 */
private data class IntradayGuideInfo(
    val title: String,
    val jsonKey: String,
    val summary: String,
    val plusEffect: String,
    val minusEffect: String,
    val defaultVal: String,
    /** Bukan null = knob ini mengubah angka yang sudah diputuskan/diukur owner. */
    val ownerNote: String? = null
)

private val INTRADAY_GUIDES = mapOf(
    "min_sl_pct" to IntradayGuideInfo(
        title = "Jarak Stop Loss Minimum",
        jsonKey = "min_sl_pct",
        summary = "Batas paling rapat untuk jarak stop loss dari harga beli, dalam persen. " +
            "Stop loss tidak pernah lebih dekat dari angka ini.",
        plusEffect = "Stop loss makin jauh dari harga beli — lebih tahan terhadap gerakan wajar, " +
            "tetapi kerugian per kejadian kalah makin besar.",
        minusEffect = "Stop loss makin rapat — rugi per kalah lebih kecil, tetapi lebih mudah " +
            "tersentuh oleh gerakan normal. Ingat: jarak SL tidak boleh lebih kecil dari beberapa " +
            "kali biaya transaksi, kalau tidak matematikanya mustahil untung.",
        defaultVal = "1,5% (Standar)"
    ),
    "max_sl_pct" to IntradayGuideInfo(
        title = "Jarak Stop Loss Maksimum",
        jsonKey = "max_sl_pct",
        summary = "Batas paling lebar jarak stop loss dari harga beli, dalam persen.",
        plusEffect = "Posisi boleh diberi ruang lebih lebar sebelum dipotong; risikonya rugi per " +
            "kejadian kalah bisa lebih besar.",
        minusEffect = "Stop loss dipaksa lebih rapat; posisi lebih cepat dipotong saat pasar " +
            "berfluktuasi.",
        defaultVal = "2,8% (Standar)"
    ),
    "atr_floor_pct" to IntradayGuideInfo(
        title = "Lantai Volatilitas (ATR)",
        jsonKey = "atr_floor_pct",
        summary = "ATR adalah ukuran seberapa besar harga biasanya bergerak. Nilai ini adalah " +
            "batas bawah yang dipakai sistem: kalau pasar sedang sangat tenang, sistem tetap " +
            "menganggap pergerakan minimal sebesar ini agar target tidak mustahil.",
        plusEffect = "Sistem menganggap pasar lebih bergejolak → stop loss dan target ikut melebar.",
        minusEffect = "Sistem menganggap pasar lebih tenang → stop loss dan target lebih rapat.",
        defaultVal = "1,5% (Standar)"
    ),
    "atr_cap_pct" to IntradayGuideInfo(
        title = "Batas Atas Volatilitas (ATR)",
        jsonKey = "atr_cap_pct",
        summary = "Batas atas ATR: kalau pasar sedang sangat bergejolak, sistem tidak memakai " +
            "angka mentahnya agar jarak stop loss tidak meledak.",
        plusEffect = "Boleh mengikuti gejolak lebih besar — stop loss bisa jauh, target lebih jauh.",
        minusEffect = "Gejolak dipotong lebih awal — jarak stop loss lebih terkendali.",
        defaultVal = "4,0% (Standar)"
    ),
    "sl_atr_mult" to IntradayGuideInfo(
        title = "Stop Loss di Bawah Support (x ATR)",
        jsonKey = "sl_atr_mult",
        summary = "Stop loss diletakkan sedikit di bawah garis support (area harga yang biasanya " +
            "menahan penurunan). Angka ini menentukan seberapa jauh di bawah support, diukur " +
            "dalam kelipatan ATR.",
        plusEffect = "Stop lebih dalam di bawah support — lebih aman dari sentuhan palsu, rugi " +
            "per kalah lebih besar.",
        minusEffect = "Stop menempel di bawah support — rugi lebih kecil, lebih mudah tersapu " +
            "gerakan sesaat.",
        defaultVal = "0,3x ATR (Standar)"
    ),
    "no_support_atr_mult" to IntradayGuideInfo(
        title = "Stop Loss Saat Tidak Ada Support (x ATR)",
        jsonKey = "no_support_atr_mult",
        summary = "Dipakai hanya ketika sistem tidak menemukan garis support di bawah harga. " +
            "Stop loss lalu dihitung dari ATR sebesar angka ini.",
        plusEffect = "Ruang lebih lebar saat tidak ada support — lebih tahan, rugi lebih besar.",
        minusEffect = "Stop lebih rapat saat tidak ada support — lebih cepat keluar.",
        defaultVal = "1,2x ATR (Standar)"
    ),
    "min_sl_friction_multiple" to IntradayGuideInfo(
        title = "Lantai Risiko vs Biaya Transaksi",
        jsonKey = "min_sl_friction_multiple",
        summary = "Jarak stop loss minimal sebagai kelipatan biaya transaksi pulang-pergi " +
            "(fee beli + fee jual + slippage dua sisi). Ini penjaga terpenting: kalau stop lebih " +
            "rapat dari beberapa kali biaya, satu kali kalah menelan banyak unit risiko sekaligus.",
        plusEffect = "Stop dipaksa lebih lebar dari biaya — lebih aman secara matematis, tetapi " +
            "rugi per kalah lebih besar.",
        minusEffect = "Stop boleh lebih rapat; di bawah 1x biaya, stop-out terjadi di dalam band " +
            "biaya dan perdagangan nyaris mustahil untung. 0 = lantai dimatikan.",
        defaultVal = "2,0x (Standar)"
    ),
    "tp1_atr_mult" to IntradayGuideInfo(
        title = "Target Profit 1 (x ATR)",
        jsonKey = "tp1_atr_mult",
        summary = "Target profit pertama dihitung dari harga beli ditambah kelipatan ATR. Sistem " +
            "juga punya target minimum dari rasio risiko, jadi target yang dipakai adalah yang " +
            "lebih tinggi di antara keduanya.",
        plusEffect = "Target pertama lebih jauh — cuan per trade lebih besar, tetapi lebih jarang " +
            "tersentuh.",
        minusEffect = "Target pertama lebih dekat — lebih sering tercapai, tetapi nilainya kecil " +
            "dan bisa tergerus biaya.",
        defaultVal = "1,25x ATR (Standar)"
    ),
    "max_tp1_pct" to IntradayGuideInfo(
        title = "Batas Atas Target Profit 1",
        jsonKey = "max_tp1_pct",
        summary = "Target pertama tidak boleh lebih jauh dari persen ini, juga tidak boleh " +
            "melewati garis resistance (area harga yang biasanya menahan kenaikan). Batas ini " +
            "penting supaya saringan rasio untung/risiko masih bisa GAGAL dengan jujur: tanpa " +
            "batas, target selalu bisa dibuat pas dan saringan jadi lolos otomatis.",
        plusEffect = "Target bisa lebih jauh → sinyal BUY bertambah. Terukur pada 26 pair H4: " +
            "3–5% = 0 BUY, 6% = 17 BUY, 7–10% = 20 BUY (identik, karena yang memotong sudah " +
            "resistance). Semua konfigurasi itu rata-rata netnya masih NEGATIF.",
        minusEffect = "Target lebih dekat → sinyal lebih sedikit. Di bawah 5% sinyal intraday " +
            "menjadi nol pada data yang diukur.",
        defaultVal = "5,0% (bawaan; 8,0% belum disetujui owner)",
        ownerNote = "Nilai ini diputuskan owner. Mengubahnya membuat hasil di luar angka §83 " +
            "AUDIT-2026-11-08.md — laporan wajib menyebut config yang dipakai."
    ),
    "tp2_atr_mult" to IntradayGuideInfo(
        title = "Jarak Target Profit 2 dari Target 1 (x ATR)",
        jsonKey = "tp2_atr_mult",
        summary = "Jarak target kedua di atas target pertama, dalam kelipatan ATR.",
        plusEffect = "Target kedua lebih jauh — potensi cuan lebih besar, lebih jarang tercapai.",
        minusEffect = "Target kedua lebih dekat — lebih sering tercapai.",
        defaultVal = "1,4x ATR (Standar)"
    ),
    "min_tp2_pct" to IntradayGuideInfo(
        title = "Target Profit 2 Minimum",
        jsonKey = "min_tp2_pct",
        summary = "Batas bawah jarak target kedua dari harga beli, dalam persen.",
        plusEffect = "Target kedua dipaksa lebih jauh.",
        minusEffect = "Target kedua boleh lebih dekat.",
        defaultVal = "5,5% (Standar)"
    ),
    "max_tp2_pct" to IntradayGuideInfo(
        title = "Target Profit 2 Maksimum",
        jsonKey = "max_tp2_pct",
        summary = "Batas atas jarak target kedua dari harga beli, dalam persen. Menjaga target " +
            "tidak menjadi angka yang tidak realistis.",
        plusEffect = "Target kedua boleh lebih jauh.",
        minusEffect = "Target kedua dipotong lebih dekat.",
        defaultVal = "12,0% (Standar)"
    ),
    "flash_dump_scan_candles" to IntradayGuideInfo(
        title = "Jumlah Candle yang Dipindai untuk Flash Dump",
        jsonKey = "flash_dump_scan_candles",
        summary = "Berapa candle terakhir yang diperiksa sistem untuk mencari jejak penurunan " +
            "mendadak (flash dump).",
        plusEffect = "Riwayat yang diperiksa lebih panjang → lebih banyak koin diblokir karena " +
            "pernah dump.",
        minusEffect = "Hanya riwayat pendek yang diperiksa → lebih banyak koin lolos.",
        defaultVal = "18 candle (Standar)"
    ),
    "flash_dump_trauma_candles" to IntradayGuideInfo(
        title = "Umur Trauma Flash Dump",
        jsonKey = "flash_dump_trauma_candles",
        summary = "Berapa candle setelah kejadian dump koin masih diblokir. Dihitung dalam jumlah " +
            "candle, bukan jam: pada candle H4, 12 candle sama dengan sekitar 48 jam; pada H1 " +
            "sekitar 12 jam.",
        plusEffect = "Blokir bertahan lebih lama — lebih hati-hati terhadap dump susulan.",
        minusEffect = "Blokir lebih cepat berakhir — lebih banyak koin boleh masuk lagi.",
        defaultVal = "12 candle (Standar)"
    ),
    "macd_hist_min_pct" to IntradayGuideInfo(
        title = "Ambang Momentum MACD",
        jsonKey = "macd_hist_min_pct",
        summary = "MACD histogram adalah ukuran momentum. Nilainya dinormalisasi ke persen harga " +
            "supaya bisa dibandingkan antar koin dengan harga sangat berbeda. Momentum harus " +
            "minimal sebesar angka ini.",
        plusEffect = "Syarat momentum lebih ketat → sinyal lebih sedikit, momentum harus jelas positif.",
        minusEffect = "Sinyal boleh muncul saat momentum masih datar atau sedikit negatif → lebih " +
            "banyak sinyal, lebih banyak yang lemah. 0 = setara perilaku lama.",
        defaultVal = "0,0% (Standar)"
    ),
    "min_net_rr_tp1" to IntradayGuideInfo(
        title = "Rasio Untung/Risiko Bersih Minimum",
        jsonKey = "min_net_rr_tp1",
        summary = "Perbandingan potensi untung dibanding risiko setelah dikurangi seluruh biaya " +
            "(fee beli + fee jual + slippage dua sisi). Dihitung dari target profit pertama yang " +
            "benar-benar dipakai, bukan dari target teoretis.",
        plusEffect = "Hanya menerima trade yang untung bersihnya jauh melampaui risikonya → " +
            "sinyal lebih sedikit.",
        minusEffect = "Menerima trade dengan ruang untung tipis → sinyal bertambah; di bawah 1,0 " +
            "artinya potensi untung lebih kecil daripada risiko.",
        defaultVal = "1,5 (keputusan owner, tidak diturunkan)",
        ownerNote = "Owner memutuskan ambang ini tetap 1,5 dan tidak diturunkan agar sinyal " +
            "bertambah. Menurunkannya membuat geometri mengikuti angka yang belum diukur."
    ),
    "slippage_pct" to IntradayGuideInfo(
        title = "Asumsi Slippage per Sisi",
        jsonKey = "slippage_pct",
        summary = "Perkiraan selisih harga saat order benar-benar tereksekusi, per sisi (beli " +
            "dan jual masing-masing). Dipakai menghitung untung bersih dan jarak stop minimum.",
        plusEffect = "Perhitungan lebih pesimistis → target harus lebih jauh untuk lolos saringan.",
        minusEffect = "Perhitungan lebih optimistis → lebih banyak sinyal lolos, tetapi angka " +
            "untung di layar bisa lebih bagus daripada kenyataan.",
        defaultVal = "Bawaan FeeSchedule (0,08%)",
        ownerNote = "Ini hanya slippage untuk perhitungan sinyal intraday. Basis laba/rugi " +
            "simulasi aplikasi tetap memakai satu nilai global dan TIDAK ikut berubah."
    )
)

@Composable
fun IntradaySensitivitySettings(
    config: IntradayConfig,
    activePreset: String,
    onConfigChange: (IntradayConfig, String) -> Unit,
    onPresetChange: (IntradaySensitivityPreset) -> Unit
) {
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var activeGuideKey by remember { mutableStateOf<String?>(null) }

    fun update(next: IntradayConfig) = onConfigChange(next, "CUSTOM")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = TvGreen, modifier = Modifier.size(18.dp))
            Text(
                "SENSITIFITAS ENGINE INTRADAY",
                color = TvTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Sesuaikan ambang mode Intraday. Tombol (+) dan (-) memudahkan penyetelan halus. " +
                "Ketuk ikon (i) di tiap pengaturan untuk penjelasan bahasa awam.",
            color = TvTextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        Spacer(Modifier.height(8.dp))

        // Peringatan jujur — bukan janji profit.
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = TvAmber.copy(alpha = 0.10f),
            border = androidx.compose.foundation.BorderStroke(1.dp, TvAmber.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    "Slider mengubah FREKUENSI sinyal, bukan menjamin profit",
                    color = TvAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Pada data 26 pair H4, sinyal intraday masih rata-rata negatif setelah biaya " +
                        "di semua konfigurasi yang menghasilkan trade. Jangan jadikan angka di " +
                        "layar ini dasar keputusan uang riil.",
                    color = TvTextPrimary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text("PILIHAN PRESET", color = TvTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IntradaySensitivityPreset.entries.forEach { preset ->
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
            IntradaySensitivityPreset.entries
                .firstOrNull { it.name.equals(activePreset, ignoreCase = true) }
                ?.description ?: IntradaySensitivityPreset.CUSTOM.description,
            color = TvTextSecondary,
            fontSize = 10.sp,
            lineHeight = 13.sp
        )

        Spacer(Modifier.height(14.dp))

        GroupCard("GEOMETRI STOP LOSS") {
            IntradaySliderItem(
                label = "Jarak SL minimum",
                value = config.minSlPct,
                range = 0.5..5.0,
                step = 0.1,
                suffix = "%",
                guideKey = "min_sl_pct",
                onShowInfo = { activeGuideKey = "min_sl_pct" },
                onValueChange = { update(config.copy(minSlPct = it)) }
            )
            IntradaySliderItem(
                label = "Jarak SL maksimum",
                value = config.maxSlPct,
                range = 1.0..8.0,
                step = 0.1,
                suffix = "%",
                guideKey = "max_sl_pct",
                onShowInfo = { activeGuideKey = "max_sl_pct" },
                onValueChange = { update(config.copy(maxSlPct = it)) }
            )
            IntradaySliderItem(
                label = "Lantai volatilitas (ATR)",
                value = config.atrFloorPct,
                range = 0.5..4.0,
                step = 0.1,
                suffix = "%",
                guideKey = "atr_floor_pct",
                onShowInfo = { activeGuideKey = "atr_floor_pct" },
                onValueChange = { update(config.copy(atrFloorPct = it)) }
            )
            IntradaySliderItem(
                label = "Batas atas volatilitas (ATR)",
                value = config.atrCapPct,
                range = 1.0..8.0,
                step = 0.1,
                suffix = "%",
                guideKey = "atr_cap_pct",
                onShowInfo = { activeGuideKey = "atr_cap_pct" },
                onValueChange = { update(config.copy(atrCapPct = it)) }
            )
            IntradaySliderItem(
                label = "SL di bawah support",
                value = config.slAtrMult,
                range = 0.0..1.5,
                step = 0.05,
                suffix = "x ATR",
                guideKey = "sl_atr_mult",
                onShowInfo = { activeGuideKey = "sl_atr_mult" },
                onValueChange = { update(config.copy(slAtrMult = it)) }
            )
            IntradaySliderItem(
                label = "SL saat tanpa support",
                value = config.noSupportAtrMult,
                range = 0.0..3.0,
                step = 0.1,
                suffix = "x ATR",
                guideKey = "no_support_atr_mult",
                onShowInfo = { activeGuideKey = "no_support_atr_mult" },
                onValueChange = { update(config.copy(noSupportAtrMult = it)) }
            )
            IntradaySliderItem(
                label = "Lantai risiko vs biaya",
                value = config.minSlFrictionMultiple,
                range = 0.0..6.0,
                step = 0.5,
                suffix = "x biaya",
                guideKey = "min_sl_friction_multiple",
                onShowInfo = { activeGuideKey = "min_sl_friction_multiple" },
                onValueChange = { update(config.copy(minSlFrictionMultiple = it)) }
            )
        }

        Spacer(Modifier.height(10.dp))

        GroupCard("TARGET PROFIT") {
            IntradaySliderItem(
                label = "Target 1 (x ATR)",
                value = config.tp1AtrMult,
                range = 0.5..3.0,
                step = 0.05,
                suffix = "x ATR",
                guideKey = "tp1_atr_mult",
                onShowInfo = { activeGuideKey = "tp1_atr_mult" },
                onValueChange = { update(config.copy(tp1AtrMult = it)) }
            )
            IntradaySliderItem(
                label = "Batas atas Target 1",
                value = config.maxTp1Pct,
                range = 3.0..10.0,
                step = 0.5,
                suffix = "%",
                guideKey = "max_tp1_pct",
                warning = true,
                onShowInfo = { activeGuideKey = "max_tp1_pct" },
                onValueChange = { update(config.copy(maxTp1Pct = it)) }
            )
            IntradaySliderItem(
                label = "Target 2 dari Target 1",
                value = config.tp2AtrMult,
                range = 0.5..3.0,
                step = 0.05,
                suffix = "x ATR",
                guideKey = "tp2_atr_mult",
                onShowInfo = { activeGuideKey = "tp2_atr_mult" },
                onValueChange = { update(config.copy(tp2AtrMult = it)) }
            )
            IntradaySliderItem(
                label = "Target 2 minimum",
                value = config.minTp2Pct,
                range = 2.0..10.0,
                step = 0.5,
                suffix = "%",
                guideKey = "min_tp2_pct",
                onShowInfo = { activeGuideKey = "min_tp2_pct" },
                onValueChange = { update(config.copy(minTp2Pct = it)) }
            )
            IntradaySliderItem(
                label = "Target 2 maksimum",
                value = config.maxTp2Pct,
                range = 6.0..20.0,
                step = 0.5,
                suffix = "%",
                guideKey = "max_tp2_pct",
                onShowInfo = { activeGuideKey = "max_tp2_pct" },
                onValueChange = { update(config.copy(maxTp2Pct = it)) }
            )
        }

        Spacer(Modifier.height(10.dp))

        GroupCard("FILTER KEAMANAN") {
            IntradaySliderItem(
                label = "Pindai flash dump (candle)",
                value = config.flashDumpScanCandles.toDouble(),
                range = 5.0..50.0,
                step = 1.0,
                suffix = " candle",
                decimals = 0,
                guideKey = "flash_dump_scan_candles",
                onShowInfo = { activeGuideKey = "flash_dump_scan_candles" },
                onValueChange = { update(config.copy(flashDumpScanCandles = it.roundToInt())) }
            )
            IntradaySliderItem(
                label = "Umur trauma flash dump",
                value = config.flashDumpTraumaCandles.toDouble(),
                range = 0.0..30.0,
                step = 1.0,
                suffix = " candle",
                decimals = 0,
                guideKey = "flash_dump_trauma_candles",
                onShowInfo = { activeGuideKey = "flash_dump_trauma_candles" },
                onValueChange = { update(config.copy(flashDumpTraumaCandles = it.roundToInt())) }
            )
            IntradaySliderItem(
                label = "Ambang momentum MACD",
                value = config.macdHistMinPct,
                range = -0.20..0.50,
                step = 0.01,
                suffix = "%",
                decimals = 2,
                guideKey = "macd_hist_min_pct",
                onShowInfo = { activeGuideKey = "macd_hist_min_pct" },
                onValueChange = { update(config.copy(macdHistMinPct = it)) }
            )
        }

        Spacer(Modifier.height(10.dp))

        GroupCard("SARINGAN R:R & BIAYA") {
            IntradaySliderItem(
                label = "Rasio untung/risiko bersih minimum",
                value = config.minNetRrTp1,
                range = 1.0..3.0,
                step = 0.05,
                suffix = "x",
                decimals = 2,
                guideKey = "min_net_rr_tp1",
                warning = true,
                onShowInfo = { activeGuideKey = "min_net_rr_tp1" },
                onValueChange = { update(config.copy(minNetRrTp1 = it)) }
            )
            IntradaySliderItem(
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

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Biaya maker (eksperimen)",
                        color = TvTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Mati = biaya taker (perilaku normal: order pasar). Nyalakan hanya " +
                            "untuk eksperimen eksplisit — eksekusi aplikasi memakai order pasar.",
                        color = TvTextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
                Switch(
                    checked = config.useMakerFee,
                    onCheckedChange = { update(config.copy(useMakerFee = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = TvGreen)
                )
            }
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
                        "intraday_config.json",
                        IntradayRuntimeConfig.toJsonString(config)
                    )
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        context,
                        "JSON intraday_config berhasil disalin ke clipboard!",
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
                    onPresetChange(IntradaySensitivityPreset.STANDARD)
                    Toast.makeText(
                        context,
                        "Threshold direset ke default IntradayConfig",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier
                    .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
                    .size(42.dp)
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = "Reset Default", tint = TvTextSecondary)
            }
        }
    }

    activeGuideKey?.let { key ->
        INTRADAY_GUIDES[key]?.let { info ->
            IntradayGuideDialog(info = info, onDismiss = { activeGuideKey = null })
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
                        "Import intraday_config.json",
                        color = TvTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tempel teks JSON konfigurasi intraday di bawah ini:",
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
                                "{ \"min_net_rr_tp1\": 1.5, ... }",
                                color = TvTextSecondary.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        },
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
                                val parsed = IntradayRuntimeConfig.fromJsonString(importText.trim())
                                if (parsed != null) {
                                    onConfigChange(parsed, "CUSTOM")
                                    showImportDialog = false
                                    Toast.makeText(
                                        context,
                                        "Konfigurasi intraday berhasil diimpor!",
                                        Toast.LENGTH_SHORT
                                    ).show()
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
private fun IntradaySliderItem(
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
                "Nilai ini diputuskan/diukur owner — lihat (i)",
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
private fun IntradayGuideDialog(info: IntradayGuideInfo, onDismiss: () -> Unit) {
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
                            Text(info.jsonKey, color = TvTextSecondary, fontSize = 10.sp)
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TvTextSecondary)
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    "PENJELASAN AWAM",
                    color = TvCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(info.summary, color = TvTextPrimary, fontSize = 12.sp, lineHeight = 17.sp)

                Spacer(Modifier.height(12.dp))

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
                        color = TvRed.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TvRed.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "Catatan keputusan owner",
                                color = TvRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                info.ownerNote,
                                color = TvTextPrimary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
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
                        onClick = onDismiss,
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
