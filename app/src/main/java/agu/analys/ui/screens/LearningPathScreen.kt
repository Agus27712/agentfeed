package agu.analys.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.CandleBar
import agu.analys.model.TradingPair
import agu.analys.ui.theme.*
import agu.analys.util.AppPreferences
import agu.analys.viewmodel.TradingViewModel

data class LearningLesson(val title: String, val summary: String, val detail: String)

private val lessons = listOf(
    LearningLesson("Candlestick", "Kenali Open, High, Low, Close dan bentuk candle.", "Candle menunjukkan perjalanan harga dalam satu periode. Open adalah harga awal periode, High harga tertinggi, Low harga terendah, dan Close harga akhir. Warna candle hanya membantu membaca posisi Close terhadap Open. Untuk belajar, biasakan melihat beberapa candle sekaligus dan timeframe yang sedang dipakai."),
    LearningLesson("Support & Resistance", "Temukan area tempat harga sering bereaksi.", "Support adalah area tempat tekanan beli pernah menahan penurunan. Resistance adalah area tempat tekanan jual pernah menahan kenaikan. Keduanya adalah zona, bukan garis sakti. Reaksi yang berulang dan konteks timeframe yang lebih besar membuat area lebih bermakna. Jika harga sedang berada di tengah-tengah rentang (mid-range), jangan terburu-buru masuk posisi sebelum ada kepastian reaksi di batas support atau resistance."),
    LearningLesson("Trend & Market Structure", "Bedakan bullish, bearish, dan sideways.", "Trend naik biasanya membentuk Higher High (HH) dan Higher Low (HL). Trend turun membentuk Lower High (LH) dan Lower Low (LL). Sideways berarti harga bergerak dalam rentang tanpa struktur arah yang jelas. Jangan memaksa mencari trend ketika struktur memang belum terbentuk."),
    LearningLesson("Volume & Likuiditas", "Lihat apakah pergerakan harga didukung aktivitas.", "Volume menunjukkan jumlah aset yang berpindah tangan pada periode tersebut. Kenaikan harga yang disertai aktivitas lebih besar dapat menjadi konfirmasi tambahan, tetapi volume tidak menjamin arah berikutnya. Bandingkan volume dengan candle dan konteks timeframe, bukan angka tunggal."),
    LearningLesson("RSI & Momentum", "Pelajari momentum tanpa menganggapnya tombol beli/jual.", "RSI berada pada skala 0 sampai 100 dan mengukur momentum relatif. Area di atas 70 (Overbought/Jenuh Beli) dan di bawah 30 (Oversold/Jenuh Jual) adalah zona ekstrem, tetapi kondisi ekstrem bisa bertahan lama saat trend kuat. Di area tengah (40-60), momentum bersifat netral. Gunakan RSI untuk membaca momentum dan konfirmasi, bukan sebagai sinyal otomatis jual atau beli."),
    LearningLesson("EMA Crossover & Trend Filter", "Gunakan EMA untuk membaca arah dan posisi harga.", "EMA memberi bobot lebih besar pada harga terbaru. EMA fast (seperti EMA20) lebih responsif daripada EMA slow (seperti EMA50). Ketika EMA fast berada di atas EMA slow, momentum jangka lebih pendek cenderung lebih kuat. Namun jika garis EMA saling berhimpitan, pasar sedang dalam fase konsolidasi/sideways. EMA adalah alat bantu pembaca tren, bukan ramalan masa depan."),
    LearningLesson("MACD & Histogram", "Kenali momentum dan perubahan akselerasinya.", "MACD berasal dari perbedaan EMA cepat dan EMA lambat. Signal line membantu membaca perubahan momentum, sedangkan histogram menunjukkan selisih keduanya. Histogram positif yang meninggi menandakan akselerasi beli yang menguat, sedangkan histogram yang mengecil menandakan momentum mulai melambat."),
    LearningLesson("Bollinger Bands & Volatilitas", "Membaca kompresi rentang harga.", "Bollinger Bands terdiri dari garis tengah (SMA) dan dua pita standar deviasi (Upper & Lower Band). Ketika harga menyentuh Upper Band, volatilitas sedang tinggi di sisi atas. Ketika pita menyempit (squeeze), bersiaplah menghadapi lonjakan volatilitas berikutnya."),
    LearningLesson("ATR & Manajemen Risiko", "Pahami besar gerakan harga tanpa mencari arah.", "ATR mengukur besar pergerakan harga rata-rata, bukan arah bullish atau bearish. ATR tinggi berarti market sedang lebih volatil. Gunakan ATR sebagai acuan jarak aman Stop Loss dan ekspektasi pergerakan target realistis, bukan sekadar angka tebakan."),
    LearningLesson("Entry, TP & Stop Loss", "Pahami rencana sebelum masuk posisi.", "Entry adalah harga rencana masuk. Take Profit adalah target keluar dengan profit. Stop Loss adalah batas invalidasi atau kerugian yang direncanakan. Level ini harus punya alasan yang jelas dari struktur dan volatilitas, bukan sekadar angka yang terlihat bagus."),
    LearningLesson("Psikologi Menunggu & Observasi", "Menunggu konfirmasi adalah bagian dari strategi.", "Belum adanya sinyal entry (Hold / Wait) bukan berarti Anda dilarang trading, melainkan pasar sedang belum memberikan risk-to-reward yang menguntungkan. Menunggu setup terbentuk sempurna jauh lebih menguntungkan daripada masuk posisi secara impulsif tanpa konfirmasi."),
    LearningLesson("Risk Management & Ukuran Posisi", "Belajar bertahan sebelum mengejar profit.", "Risiko per transaksi sebaiknya ditentukan sebelum entry. Jangan memperbesar posisi hanya karena yakin. Kerugian kecil yang terencana lebih mudah dikelola daripada satu posisi besar yang tidak terkendali. Tujuan pertama trader adalah menjaga modal dan membangun kebiasaan yang konsisten."),
    LearningLesson("Trading Plan & Jurnal", "Satukan analisis menjadi aturan yang konsisten.", "Trading plan berisi kondisi entry, alasan entry, invalidasi, target, batas risiko, dan kondisi kapan tidak trading. Setelah trade selesai, catat apa yang benar dan salah. Dengan begitu aplikasi menjadi alat belajar dan jurnal keputusan, bukan mesin pengejar sinyal."),
)

@Composable
fun LearningPathScreen(
    onBack: () -> Unit,
    viewModel: TradingViewModel? = null,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var completed by remember { mutableStateOf(prefs.getCompletedLearningLessons()) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    val completedCount = completed.size.coerceAtMost(lessons.size)
    val progress = completedCount.toFloat() / lessons.size.toFloat()

    val candles: List<CandleBar> = if (viewModel != null) {
        val candlesState by viewModel.recentCandles.collectAsState()
        candlesState
    } else {
        emptyList<CandleBar>()
    }

    val pair: TradingPair? = if (viewModel != null) {
        val pairState by viewModel.selectedPair.collectAsState()
        pairState
    } else {
        null
    }

    val marketStructure = remember(candles) { agu.analys.engine.MarketStructureAnalyzer.analyze(candles) }

    Column(modifier = modifier.fillMaxSize().background(TvBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = TvTextPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("MODE BELAJAR & EDUKASI", fontSize = 16.sp, fontWeight = FontWeight.Black, color = TvTextPrimary)
                Text("Pahami konsep & struktur sebelum eksekusi.", fontSize = 10.sp, color = TvTextSecondary)
            }
            Icon(Icons.Default.MenuBook, contentDescription = null, tint = TvGreen)
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Progress belajar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary, modifier = Modifier.weight(1f))
                Text("$completedCount/${lessons.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvGreen)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = TvGreen,
                trackColor = TvSurfaceVariant
            )
            Spacer(Modifier.height(5.dp))
            Text(
                if (completedCount == lessons.size) "Semua materi dasar selesai. Saatnya latihan membaca market nyata." else "Selesaikan materi satu per satu. Tidak perlu terburu-buru.",
                fontSize = 10.sp,
                color = if (completedCount == lessons.size) TvGreen else TvTextSecondary
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // LIVE STRUCTURE LEARNING CARD (Dipindah dari DetailScreen ke Modul Belajar)
            if (candles.isNotEmpty()) {
                item {
                    agu.analys.ui.components.MarketStructureLearningCard(
                        snapshot = marketStructure,
                        quoteAsset = pair?.quoteAsset ?: "IDR"
                    )
                }
            }

            itemsIndexed(lessons) { index, lesson ->
                val isExpanded = expanded == index
                val isCompleted = completed.contains(index)
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = if (isExpanded) null else index },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = TvCardBackground)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${(index + 1).toString().padStart(2, '0')}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isCompleted) TvGreen else TvTextSecondary)
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(lesson.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                                Text(lesson.summary, fontSize = 10.sp, color = TvTextSecondary, lineHeight = 14.sp)
                            }
                            Icon(
                                if (isCompleted) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                                contentDescription = if (isCompleted) "Selesai" else "Buka pelajaran",
                                tint = if (isCompleted) TvGreen else TvTextSecondary
                            )
                        }
                        if (isExpanded) {
                            Spacer(Modifier.height(10.dp))
                            Text(lesson.detail, fontSize = 11.sp, color = TvTextPrimary, lineHeight = 17.sp)
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    val next = !isCompleted
                                    prefs.setLearningLessonCompleted(index, next)
                                    completed = prefs.getCompletedLearningLessons()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isCompleted) TvSurfaceVariant else TvGreen
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isCompleted) {
                                    Text("Tandai belum selesai", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                                } else {
                                    Text("Saya sudah memahami materi ini", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Text("Materi ini untuk belajar konsep. Bukan sinyal trading.", fontSize = 9.sp, color = TvRed.copy(alpha = 0.85f))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/** Compatibility wrapper for the existing Settings screen. */
@Composable
fun LearningScreen(viewModel: TradingViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    LearningPathScreen(onBack = onBack, modifier = modifier)
}
