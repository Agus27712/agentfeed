package agu.analys.service

import agu.analys.AppContextProvider
import agu.analys.model.AISignalState
import agu.analys.model.MarketTick
import agu.analys.model.TechnicalIndicators
import agu.analys.network.NetworkClientProvider
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** Chart summary Gemini — output wajib Bahasa Indonesia (headline di-translate). */
object GeminiAiService {
    private val client get() = NetworkClientProvider.aiClient

    // PERBAIKAN 2: Gunakan daftar model yang dijamin ada di Google API publik
    private const val MODEL = "gemini-2.5-flash"
    private val CANDIDATE_MODELS = listOf("gemini-2.5-flash", "gemini-3.5-flash", "gemini-flash-latest", "gemini-3.1-pro-preview")

    suspend fun generateChartSummary24h(
        apiKey: String,
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState
    ): String = withContext(Dispatchers.IO) {
        val effectiveKey = if (apiKey.isBlank()) "" else apiKey
        val base = extractBase(tick.symbol)
        val headlines = runCatching { CryptoHeadlineService.snapshotForBase(base) }.getOrNull()
        val headlineBlock = headlines?.promptBlock() ?: "Headline: tidak tersedia."

        if (effectiveKey.isBlank()) return@withContext buildFallback(tick, indicators, signal, headlineBlock, null)

        val pairCtx = PairNarrative.forBase(base)
        val move = describeMove(tick.change24h)
        val rsiFormatted = if (indicators.rsi14.isFinite()) String.format(java.util.Locale.US, "%.1f", indicators.rsi14) else "—"
        val rsiExplanation = when {
            !indicators.rsi14.isFinite() -> "Data RSI belum cukup"
            indicators.rsi14 > 70 -> "**Jenuh Beli (Overbought)** ($rsiFormatted) — Tekanan beli sangat tinggi, waspada potensi koreksi / taking profit"
            indicators.rsi14 < 30 -> "**Jenuh Jual (Oversold)** ($rsiFormatted) — Tekanan jual klimaks, peluang technical rebound / pantulan harga"
            indicators.rsi14 >= 50 -> "**Netral Bullish** ($rsiFormatted) — Momentum beli aktif dalam batas wajar"
            else -> "**Netral Bearish** ($rsiFormatted) — Tekanan jual moderat dalam batas wajar"
        }
        val trendExplanation = when {
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 > indicators.ema50 && tick.price >= indicators.ema20 ->
                "**Struktur Bullish Kuat** (Harga di atas EMA 20 & EMA 50)"
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 < indicators.ema50 && tick.price <= indicators.ema20 ->
                "**Struktur Bearish Kuat** (Harga di bawah EMA 20 & EMA 50)"
            else -> "**Konsolidasi / Sideways** (EMA berhimpit atau fase transisi)"
        }
        val macdExplanation = when {
            !indicators.macdHist.isFinite() -> "Data MACD belum cukup"
            indicators.macdHist > 0 -> "**Histogram Positif (Bullish Momentum)** — Volume dorongan beli sedang menguat"
            indicators.macdHist < 0 -> "**Histogram Negatif (Bearish Momentum)** — Tekanan distribusi jual sedang dominan"
            else -> "**Netral** — Momentum berimbang"
        }
        val atrExplanation = if (indicators.atr.isFinite() && indicators.atr > 0) {
            "Rentang fluktuasi candle rata-rata: ${PriceFormatter.formatPrice(indicators.atr)}"
        } else "Volatilitas normal"

        val combinedPrompt = """
[SYSTEM INSTRUCTION]
Kamu asisten quantitative & technical analyst pasar spot Tokocrypto (Crypto IDR).
Sajikan seluruh analisis dalam Bahasa Indonesia (termasuk kutipan atau intisari berita).
Terjemahkan headline Inggris ke Bahasa Indonesia dulu, lalu bedah dampaknya secara kritis.
Gunakan format Markdown terstruktur dengan poin-poin bullet (-), penomoran (1, 2, 3), teks tebal (**bold**), dan judul bab (###).
Fokus insight tajam, kritis, edukatif, dan praktis. Jelaskan jika ada kontradiksi antara kenaikan harga teknikal vs berita buruk fundamental (misal pump lokal vs delisting/warning).

ATURAN SPOT MURNI TOKOCRYPTO (SANGAT KRUSIAL - WAJIB DIPATUHI):
1. Pasar Tokocrypto adalah pasar SPOT MURNI (hanya transaksi Beli / Long aset fisik, dan Jual aset yang dimiliki).
2. DILARANG KERAS menyarankan posisi SHORT, SHORT SELLING, FUTURES, DERIVATIF, atau MARGIN LEVERAGE. Jangan pernah menggunakan kata "short" atau menyarankan sell jika belum punya aset!
3. ATURAN LEVEL HARGA SPOT:
   - Area Beli (Entry): Level harga beli spot yang realistis.
   - Take Profit (TP): Target jual untung, HARUS SELALU LEBIH TINGGI dari harga Entry (TP > Entry).
   - Stop Loss (SL): Batas proteksi cut loss modal, HARUS SELALU LEBIH RENDAH dari harga Entry (SL < Entry). DILARANG KERAS membuat Stop Loss yang lebih tinggi atau sama dengan harga Entry!
   - Jika tren sedang bearish atau sinyal HOLD/WAIT: Sarankan "Tahan Posisi / Wait & See", simpan saldo IDR (cash), atau tunggu pantulan di level support yang lebih rendah di bawah. JANGAN PERNAH menyarankan posisi short!

[DATA PASAR TOKOCRYPTO REAL-TIME]
- Pair: ${tick.symbol} (base: $base)
- Identitas & Profil: ${pairCtx.label}
- Ekosistem: ${pairCtx.ecosystem}
- Narasi: ${pairCtx.narrative}
- Pergerakan 24J: $move (${PriceFormatter.formatPercentage(tick.change24h)})
- Harga Terakhir: ${PriceFormatter.formatPrice(tick.price)} | Volume 24J: ${PriceFormatter.formatVolume(tick.volume24h)}
- Status Indikator Teknikal:
  * RSI (14): $rsiExplanation
  * Tren EMA 20/50: $trendExplanation
  * MACD Histogram: $macdExplanation
  * ATR / Volatilitas: $atrExplanation
  * Engine Sinyal System: ${signal.action.name} (Confidence: ${signal.confidence}/100)

[FEED BERITA & SENTIMEN TERKINI]
$headlineBlock

Susun jawaban dalam format Markdown berikut:

### 🔎 1. Profil & Ekosistem Aset
- **Aset**: ${pairCtx.label} ($base)
- **Karakteristik & Korelasi**: Ulas peran dalam ${pairCtx.ecosystem} dan narasi yang sedang mempengaruhi pergerakannya.

### 📊 2. Analisis Indikator Teknikal
- **RSI (14)**: Jelaskan kondisi RSI ($rsiFormatted), implikasi overbought/oversold, dan potensi pergerakan selanjutnya.
- **Tren EMA & MACD**: Evaluasi arah tren EMA 20/50 serta dorongan momentum Histogram MACD.
- **Volatilitas**: Kondisi ATR (${if (indicators.atr.isFinite() && indicators.atr > 0) PriceFormatter.formatPrice(indicators.atr) else "Normal"}) dan batas toleransi risiko.

### 📰 3. Sentimen Pasar & Alasan Gerakan
- **Faktor Penggerak**: Bedah headline berita di atas secara detail dan terjemahkan ke Bahasa Indonesia. Evaluasi apakah ada kontradiksi antara aksi harga vs berita:
  - Untuk setiap headline berita yang tersedia di atas, tuliskan terjemahan singkatnya lalu berikan analisis kritis dampaknya terhadap harga. Jika headline tidak tersedia, uraikan sentimen berdasarkan pergerakan volume dan tren harga.

- **Analisis Kritis**: Hubungkan pergerakan harga ${tick.symbol} (${PriceFormatter.formatPercentage(tick.change24h)}) dengan berita di atas. Apakah kenaikan/penurunan didorong oleh sentimen global yang valid, atau sekadar spekulasi lokal / pump & dump di Tokocrypto?

### 💡 4. Panduan Strategi & Action Plan (Spot Tokocrypto)
- **Sinyal Engine**: **${signal.action.name}** (Confidence: ${signal.confidence}/100)
- **Rekomendasi**: [Tindakan spot: Beli bertahap / Tahan posisi / Tunggu support bawah. DILARANG posisi short!]
- **Level Acuan Spot**:
  * Area Beli (Entry): [Level harga beli spot]
  * Take Profit (TP): [Target jual untung, HARUS > Entry]
  * Stop Loss (SL): [Batas proteksi risiko modal, HARUS < Entry]
  * Disiplin Order: Limit order maker 0.21% serta manajemen risiko modal
        """.trimIndent()

        var lastError: String? = null
        for (modelName in CANDIDATE_MODELS) {
            try {
                val payload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", combinedPrompt) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.65)
                        put("topP", 0.95)
                        put("maxOutputTokens", 4096)
                    })
                    put("safetySettings", JSONArray().apply {
                        val blockNone = "BLOCK_NONE"
                        put(JSONObject().apply { put("category", "HARM_CATEGORY_HARASSMENT"); put("threshold", blockNone) })
                        put(JSONObject().apply { put("category", "HARM_CATEGORY_HATE_SPEECH"); put("threshold", blockNone) })
                        put(JSONObject().apply { put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT"); put("threshold", blockNone) })
                        put(JSONObject().apply { put("category", "HARM_CATEGORY_DANGEROUS_CONTENT"); put("threshold", blockNone) })
                    })
                }

                val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$effectiveKey"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val responseBody = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val candidate = JSONObject(responseBody)
                            .optJSONArray("candidates")
                            ?.takeIf { it.length() > 0 }
                            ?.getJSONObject(0)
                        
                        val finishReason = candidate?.optString("finishReason")
                        if (finishReason == "SAFETY") {
                            Timber.w("Gemini: Terblokir oleh safety filter!")
                            return@withContext buildFallback(tick, indicators, signal, headlineBlock, "Terblokir oleh filter keamanan AI")
                        }

                        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                        val text = parts?.let { arr ->
                            (0 until arr.length()).joinToString("\n") { i ->
                                arr.getJSONObject(i).optString("text").orEmpty()
                            }
                        }.orEmpty().trim()

                        if (text.isNotBlank()) return@withContext text
                    } else {
                        val errorMsg = runCatching {
                            JSONObject(responseBody).getJSONObject("error").getString("message")
                        }.getOrNull() ?: "HTTP ${resp.code}"
                        lastError = "$modelName: $errorMsg"
                        Timber.e("Gemini Error $modelName HTTP ${resp.code}: $responseBody")
                    }
                }
            } catch (e: Exception) {
                lastError = "$modelName: ${e.localizedMessage ?: "Koneksi gagal"}"
                Timber.e(e, "Gemini model $modelName call failed")
            }
        }

        buildFallback(tick, indicators, signal, headlineBlock, lastError)
    }

    private fun extractBase(symbol: String): String {
        val s = symbol.lowercase().replace("_", "")
        return when {
            s.endsWith("idr") -> s.removeSuffix("idr")
            s.endsWith("usdt") -> s.removeSuffix("usdt")
            else -> s
        }
    }

    private fun describeMove(change24h: Double): String = when {
        change24h >= 8 -> "naik tajam"
        change24h >= 3 -> "naik"
        change24h <= -8 -> "turun tajam"
        change24h <= -3 -> "turun"
        else -> "relatif flat"
    }

    private fun buildFallback(
        tick: MarketTick,
        indicators: TechnicalIndicators,
        signal: AISignalState,
        headlineBlock: String,
        errorReason: String? = null
    ): String {
        val base = extractBase(tick.symbol)
        val ctx = PairNarrative.forBase(base)
        val move = describeMove(tick.change24h)
        val rsiFormatted = if (indicators.rsi14.isFinite()) String.format(java.util.Locale.US, "%.1f", indicators.rsi14) else "—"
        val rsiStatus = when {
            !indicators.rsi14.isFinite() -> "Netral (Data belum cukup)"
            indicators.rsi14 > 70 -> "**Jenuh Beli (Overbought)** ($rsiFormatted) — Tekanan beli mencapai puncak, waspada aksi ambil untung"
            indicators.rsi14 < 30 -> "**Jenuh Jual (Oversold)** ($rsiFormatted) — Tekanan jual klimaks, peluang pantulan teknikal"
            indicators.rsi14 >= 50 -> "**Netral Bullish** ($rsiFormatted) — Tren beli stabil dalam batas aman"
            else -> "**Netral Bearish** ($rsiFormatted) — Tekanan jual moderat dalam batas aman"
        }
        val emaStatus = when {
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 > indicators.ema50 && tick.price >= indicators.ema20 ->
                "**Bullish Alignment** (Harga di atas EMA 20 & 50)"
            indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.ema20 < indicators.ema50 && tick.price <= indicators.ema20 ->
                "**Bearish Alignment** (Harga di bawah EMA 20 & 50)"
            else -> "**Konsolidasi / Sideways** (EMA berhimpit)"
        }
        val macdStatus = when {
            !indicators.macdHist.isFinite() -> "Netral"
            indicators.macdHist > 0 -> "**Histogram Positif (Bullish Momentum)**"
            indicators.macdHist < 0 -> "**Histogram Negatif (Bearish Momentum)**"
            else -> "Netral"
        }
        val atrStatus = if (indicators.atr.isFinite() && indicators.atr > 0) {
            PriceFormatter.formatPrice(indicators.atr)
        } else "Normal"

        val cleanHeadlineText = headlineBlock.replace(
            "Headline publik terbaru (boleh campuran EN/ID — WAJIB diterjemahkan ke Bahasa Indonesia di jawaban):",
            "Headlines Berita Publik Terkini:"
        )

        val noteText = if (errorReason != null) {
            "(Catatan: Gemini API gagal merespon: $errorReason. Menampilkan analisis lokal terstruktur sebagai cadangan)."
        } else {
            "(Catatan: Masukkan Gemini API Key di Pengaturan untuk mengaktifkan analisis naratif mendalam Gemini)."
        }

        return """
### 🔎 1. Profil & Ekosistem Aset
- **Aset**: ${ctx.label}
- **Karakteristik**: ${ctx.narrative}
- **Korelasi**: ${ctx.ecosystem}

### 📊 2. Analisis Indikator Teknikal
- **RSI (14)**: $rsiStatus
- **Struktur EMA**: $emaStatus
- **Momentum MACD**: $macdStatus
- **Rentang ATR**: $atrStatus (rentang fluktuasi candle untuk toleransi risiko)

### 📰 3. Sentimen Pasar & Alasan Gerakan
- **Pergerakan 24J**: $move (${PriceFormatter.formatPercentage(tick.change24h)}), Volume: ${PriceFormatter.formatVolume(tick.volume24h)}
- **Sentimen & Berita**:
$cleanHeadlineText

### 💡 4. Panduan Strategi & Action Plan
- **Sinyal Engine**: **${signal.action.name}** (Confidence: ${signal.confidence}/100)
- **Tindakan**: Pantau konfirmasi arah BTC terlebih dahulu. Gunakan limit order maker 0.21%, hindari mengejar candle yang sudah bergerak jauh.
$noteText
        """.trimIndent()
    }

    private val safeContextReady: Boolean
        get() = try {
            AppContextProvider.context; true
        } catch (_: UninitializedPropertyAccessException) {
            false
        }

    suspend fun generateSimpleText(
        apiKey: String,
        prompt: String
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "⚠️ API Key Gemini belum di-set di Pengaturan."
        for (modelName in CANDIDATE_MODELS) {
            try {
                val payload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                }
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val text = client.newCall(request).execute().use { resp ->
                    val responseBody = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val candidate = JSONObject(responseBody)
                            .optJSONArray("candidates")
                            ?.takeIf { it.length() > 0 }
                            ?.getJSONObject(0)
                        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                        parts?.let { arr ->
                            (0 until arr.length()).joinToString("\n") { i ->
                                arr.getJSONObject(i).optString("text").orEmpty()
                            }
                        }?.trim()
                    } else null
                }
                if (!text.isNullOrBlank()) return@withContext text
            } catch (_: Exception) {
                // Lanjut coba model kandidat berikutnya
            }
        }
        "Gagal memproses AI: Tidak ada model Gemini yang merespon."
    }
}
