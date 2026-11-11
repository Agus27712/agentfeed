package agu.analys.trading

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import agu.analys.util.PriceFormatter
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility exporter & format generator untuk audit log trading.
 * Menghasilkan format JSON dan Markdown terstruktur yang siap diverifikasi oleh
 * LLM (Gemini, Claude, Grok, ChatGPT, DeepSeek).
 */
object TradeLogExporter {

    /**
     * P1-8 — Format angka indikator dengan 4–6 digit signifikan.
     *
     * Sebelumnya nilai mentah `Double.toString()` ikut tercetak (mis. `+9.010876114987423E-4`),
     * yang membuat laporan sulit dibaca dan sulit dibandingkan. `%g` menjaga presisi relatif
     * untuk nilai kecil maupun besar.
     */
    private fun fmtMetric(value: Double?, sigDigits: Int = 4): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "-"
        if (value == 0.0) return "0"
        return String.format(Locale.US, "%.${sigDigits}g", value)
    }

    /** Format timestamp bar menjadi jam:menit:detik WIB agar basis indikator mudah diverifikasi. */
    private fun fmtBarTime(ms: Long?): String {
        if (ms == null || ms <= 0L) return "-"
        return SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID")).format(Date(ms))
    }

    fun formatDuration(durationMs: Long?): String {
        if (durationMs == null || durationMs <= 0L) return "Baru Entry (0m)"
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = (durationMs / (1000 * 60 * 60)) % 24
        val days = durationMs / (1000 * 60 * 60 * 24)

        return when {
            days > 0 -> "${days}h ${hours}j ${minutes}m"
            hours > 0 -> "${hours}j ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun buildSingleTradeAuditMarkdown(
        tradeId: String,
        symbol: String,
        baseAsset: String,
        quoteAsset: String,
        side: String,
        orderType: String,
        executionPrice: Double,
        quantity: Double,
        totalIdr: Double,
        feeIdr: Double,
        timestamp: Long,
        isReal: Boolean,
        strategyMode: String,
        holdingDurationMs: Long?,
        entryPrice: Double?,
        entryTimestamp: Long?,
        pnlIdr: Double?,
        pnlPercent: Double?,
        isTrailingUsed: Boolean,
        trailingPercent: Double?,
        trailingPeakPrice: Double?,
        trailingLockPrice: Double?,
        snapshot: TradeSignalSnapshot?,
        exchange: String? = null
    ): String {
        val dateFormat = SimpleDateFormat("dd MMMM yyyy, HH:mm:ss 'WIB'", Locale("id", "ID"))
        val execTimeStr = dateFormat.format(Date(timestamp))
        val entryTimeStr = entryTimestamp?.let { dateFormat.format(Date(it)) } ?: "-"
        val quote = quoteAsset.ifBlank { "IDR" }

        val outcomeStr = when {
            side.equals("BUY", true) -> "ENTRY POSISI (HOLDING)"
            pnlIdr != null && pnlIdr > 0 -> "PROFIT / UNTUNG (+${PriceFormatter.formatPrice(pnlIdr, quoteAsset = quote)} | +${String.format(Locale.US, "%.2f", pnlPercent ?: 0.0)}%)"
            pnlIdr != null && pnlIdr < 0 -> "LOSS / RUGI (${PriceFormatter.formatPrice(pnlIdr, quoteAsset = quote)} | ${String.format(Locale.US, "%.2f", pnlPercent ?: 0.0)}%)"
            pnlIdr != null -> "BREAK EVEN (0.0%)"
            else -> "SELESAI"
        }

        val trailingInfoStr = if (isTrailingUsed) {
            "AKTIF (Peak: ${trailingPeakPrice?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"} | Lock Stop: ${trailingLockPrice?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"} | Trailing: ${trailingPercent ?: 0.0}%)"
        } else {
            "NONAKTIF"
        }

        val holdStr = formatDuration(holdingDurationMs)
        val exchangeName = exchange?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
            ?: "TOKOCRYPTO"

        val sb = StringBuilder()
        sb.append("### $exchangeName TRADING LOG & SIGNAL AUDIT REPORT\n")
        sb.append("Generated for LLM Verification (Gemini, Claude, Grok, ChatGPT)\n\n")

        sb.append("#### 📌 1. INFORMASI EKSEKUSI & STRATEGI\n")
        sb.append("- **ID Transaksi**: $tradeId\n")
        sb.append("- **Pasangan Aset**: $baseAsset/$quote ($symbol)\n")
        sb.append("- **Mode Trading**: ${strategyMode.uppercase()}\n")
        sb.append("- **Tipe Akun**: ${if (isReal) "REAL $exchangeName" else "SIMULASI VIRTUAL"}\n")
        sb.append("- **Tindakan**: ${side.uppercase()} (${orderType.uppercase()})\n")
        sb.append("- **Waktu Eksekusi**: $execTimeStr\n")
        sb.append("- **Harga Eksekusi**: ${PriceFormatter.formatPrice(executionPrice, quoteAsset = quote)}\n")
        sb.append("- **Jumlah Koin**: ${PriceFormatter.formatRawDecimal(quantity)} $baseAsset\n")
        sb.append("- **Total Nominal**: ${PriceFormatter.formatPrice(totalIdr, quoteAsset = quote)}\n")
        sb.append("- **Estimasi Fee**: ${PriceFormatter.formatPrice(feeIdr, quoteAsset = quote)}\n\n")

        sb.append("#### ⏱ 2. DURASI HOLD & PNL (PROFIT/LOSS)\n")
        sb.append("- **Status Hasil**: $outcomeStr\n")
        if (entryPrice != null && entryPrice > 0.0) {
            sb.append("- **Harga Entry Buy**: ${PriceFormatter.formatPrice(entryPrice, quoteAsset = quote)}\n")
            sb.append("- **Waktu Entry Buy**: $entryTimeStr\n")
        }
        sb.append("- **Durasi Hold**: $holdStr\n")
        if (pnlIdr != null) {
            sb.append("- **PnL Bersih (Net)**: ${PriceFormatter.formatPrice(pnlIdr, quoteAsset = quote)} (${String.format(Locale.US, "%.2f", pnlPercent ?: 0.0)}%)\n")
        }
        sb.append("- **Trailing Profit Lock**: $trailingInfoStr\n\n")

        sb.append("#### 📊 3. METRIK SINYAL TEKNIKAL LENGKAP (PER-KATEGORI)\n")
        if (snapshot != null) {
            // Category A: Order Book & Liquidity
            sb.append("**[A. Order Book & Likuiditas Pasar]**\n")
            sb.append("- Bid vs Ask Ratio: Bid ${snapshot.bidRatioPct?.let { PriceFormatter.formatPercentage(it, includePlusSign = false, decimals = 1) } ?: "-"} vs Ask ${snapshot.askRatioPct?.let { PriceFormatter.formatPercentage(it, includePlusSign = false, decimals = 1) } ?: "-"}\n")
            sb.append("- Order Book Pressure: ${snapshot.orderBookPressure?.let { if (it > 0) "+$it% (Buyer)" else "$it% (Seller)" } ?: "Netral"}\n")
            sb.append("- Spread Bid/Ask: ${snapshot.spreadPct?.let { PriceFormatter.formatPercentage(it, includePlusSign = false, decimals = 3) } ?: "-"}\n")
            // S6 — `volume24h` berasal dari `quoteVolume` bursa (denominasi QUOTE, bukan base).
            // Sebelumnya dilabeli `$baseAsset` sehingga likuiditas terbaca salah ~1.467x
            // (kasus AMP: 8,32 juta "AMP" padahal 12,2 miliar AMP ≈ $9,74 juta USDT).
            sb.append("- Volume 24 Jam (quote): ${snapshot.volume24h?.let { PriceFormatter.formatRawDecimal(it) } ?: "-"} $quote\n")
            sb.append("- Perubahan Harga 24 Jam: ${snapshot.priceChange24h?.let { PriceFormatter.formatPercentage(it, includePlusSign = true, decimals = 2) } ?: "-"}\n\n")

            // Category B: Momentum & Oscillators
            sb.append("**[B. Momentum & Osilator]**\n")
            sb.append("- RSI (14): ${snapshot.rsi14?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}\n")
            sb.append("- MACD Line: ${fmtMetric(snapshot.macd, 5)}\n")
            sb.append("- MACD Signal: ${fmtMetric(snapshot.macdSignal, 5)}\n")
            sb.append("- MACD Histogram: ${fmtMetric(snapshot.macdHist, 5)}\n")
            sb.append("- Momentum Score: ${snapshot.momentum?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}\n")
            sb.append("- Relative Volume (RVOL): ${snapshot.rvol?.let { String.format(Locale.US, "%.3f", it) + "x" } ?: "-"}\n")
            sb.append("- VWAP 1M: ${snapshot.vwap?.let { fmtMetric(it, 6) } ?: "-"}\n")
            // P0-2 — basis bar indikator supaya pihak ketiga bisa mereproduksi angka di atas.
            sb.append("- Basis Bar Indikator: ${fmtBarTime(snapshot.indicatorBarTime)} (${if (snapshot.indicatorBarClosed == true) "bar sudah tutup" else if (snapshot.indicatorBarClosed == false) "bar masih berjalan" else "tidak diketahui"})\n\n")

            // Category C: Trend & Moving Averages
            sb.append("**[C. Tren & Exponential Moving Averages]**\n")
            sb.append("- EMA 20: ${snapshot.ema20?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- EMA 50: ${snapshot.ema50?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- EMA 200: ${snapshot.ema200?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- Status Tren: ${snapshot.trendStatus ?: "Konsolidasi"}\n\n")

            // Category D: Volatility & Bollinger Bands
            sb.append("**[D. Volatilitas & Bollinger Bands]**\n")
            sb.append("- Bollinger Upper: ${snapshot.bbUpper?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- Bollinger Middle: ${snapshot.bbMiddle?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- Bollinger Lower: ${snapshot.bbLower?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n")
            sb.append("- Bandwidth %: ${snapshot.bbWidthPct?.let { PriceFormatter.formatPercentage(it, includePlusSign = false, decimals = 2) } ?: "-"}\n")
            sb.append("- Average True Range (ATR): ${snapshot.atr?.let { PriceFormatter.formatPrice(it, quoteAsset = quote) } ?: "-"}\n\n")

            // Category E: AI Evaluator Decision & Reasons
            sb.append("**[E. Evaluator Signal & Reasoning]**\n")
            // A10 — "Skor Konfidensi" ambigu: angka ini BUKAN skor engine murni, melainkan
            // min(skor engine, cap per tahap). Tanpa label pembeda, pembaca tak bisa membedakan
            // skor 63 dari cap 59 (berarti "cukup bagus") vs skor 63 asli (berarti "sangat bagus").
            // Simbol irisan (∩) sengaja tidak dipakai lagi: di sebagian font ia tampil seperti
            // aksara CJK, sehingga berkas .md hasil ekspor terbaca aneh.
            sb.append("- Skor Konfidensi (skor engine, dibatasi tahap): ${snapshot.confidenceScore?.let { "$it / 100" } ?: "-"}\n")
            sb.append("- Market Regime: ${snapshot.marketRegime ?: "-"}\n")
            if (snapshot.patternDetected != null) {
                sb.append("- Pola Terdeteksi: ${snapshot.patternDetected}\n")
            }
            if (snapshot.reasons.isNotEmpty()) {
                sb.append("- Alasan Trigger Sinyal:\n")
                snapshot.reasons.forEach { r ->
                    sb.append("  * $r\n")
                }
            }

            // A9 — Kategori 6 (Pipeline Scalping) sebelumnya hilang dari export markdown.
            // Field ini paling relevan untuk mode SCALPING dan dibutuhkan audit eksternal.
            sb.append("\n**[F. Pipeline Scalping KriptoYoi]**\n")
            sb.append("- Setup Scalping: ${snapshot.scalpingSetup ?: "-"}\n")
            sb.append("- Skor Engine: ${snapshot.scalpingScore?.let { "$it / 100" } ?: "-"}\n")
            sb.append("- Kategori Skor: ${snapshot.scalpingScoreCategory ?: "-"}\n")
            sb.append("- Rincian Skor: ${snapshot.scalpingScoreDetail ?: "-"}\n")
            sb.append("- Regime Scalping: ${snapshot.scalpingRegime ?: "-"}\n")
            sb.append("- Zona Entry: ${if (snapshot.entryZoneLow != null && snapshot.entryZoneHigh != null) "${PriceFormatter.formatPrice(snapshot.entryZoneLow, quoteAsset = quote)} – ${PriceFormatter.formatPrice(snapshot.entryZoneHigh, quoteAsset = quote)}" else "-"}\n")
            sb.append("- Thresholds: ${snapshot.scalpingThresholds ?: "-"}\n")
        } else {
            sb.append("*Snapshot sinyal teknikal tidak tersedia untuk eksekusi manual ini.*\n")
        }

        sb.append("\n---\n")
        sb.append("#### 🤖 PETUNJUK VERIFIKASI UNTUK LLM (GEMINI / CLAUDE / GROK / CHATGPT):\n")
        sb.append("Mohon audit eksekusi trade di atas berdasarkan logika kuantitatif:\n")
        sb.append("1. Apakah entry dan exit selaras dengan kondisi momentum (RSI/MACD) dan likuiditas order book?\n")
        sb.append("2. Apakah durasi hold dan penggunaan trailing profit lock sudah efektif untuk membatasi risiko (Risk/Reward)?\n")
        sb.append("3. Berikan saran perbaikan rule filter strategi jika ada anomali atau peluang profit yang terlewatkan.\n")

        return sb.toString()
    }

    fun buildSingleTradeAuditJson(
        tradeId: String,
        symbol: String,
        baseAsset: String,
        quoteAsset: String,
        side: String,
        orderType: String,
        executionPrice: Double,
        quantity: Double,
        totalIdr: Double,
        feeIdr: Double,
        timestamp: Long,
        isReal: Boolean,
        strategyMode: String,
        holdingDurationMs: Long?,
        entryPrice: Double?,
        entryTimestamp: Long?,
        pnlIdr: Double?,
        pnlPercent: Double?,
        isTrailingUsed: Boolean,
        trailingPercent: Double?,
        trailingPeakPrice: Double?,
        trailingLockPrice: Double?,
        snapshot: TradeSignalSnapshot?
    ): JSONObject {
        val root = JSONObject()
        root.put("tradeId", tradeId)
        root.put("symbol", symbol)
        root.put("baseAsset", baseAsset)
        root.put("quoteAsset", quoteAsset)
        root.put("side", side)
        root.put("orderType", orderType)
        root.put("executionPrice", executionPrice)
        root.put("quantity", quantity)
        root.put("totalIdr", totalIdr)
        root.put("feeIdr", feeIdr)
        root.put("timestamp", timestamp)
        root.put("isReal", isReal)
        root.put("strategyMode", strategyMode)
        root.put("holdingDurationMs", holdingDurationMs ?: 0L)
        root.put("holdingDurationFormatted", formatDuration(holdingDurationMs))
        entryPrice?.let { root.put("entryPrice", it) }
        entryTimestamp?.let { root.put("entryTimestamp", it) }
        pnlIdr?.let { root.put("pnlIdr", it) }
        pnlPercent?.let { root.put("pnlPercent", it) }
        root.put("isProfit", (pnlIdr ?: 0.0) >= 0.0)
        root.put("isTrailingUsed", isTrailingUsed)
        trailingPercent?.let { root.put("trailingPercent", it) }
        trailingPeakPrice?.let { root.put("trailingPeakPrice", it) }
        trailingLockPrice?.let { root.put("trailingLockPrice", it) }
        if (snapshot != null) {
            root.put("signalSnapshot", snapshot.toJson())
        }
        return root
    }

    fun copyToClipboard(context: Context, text: String, label: String = "Trade Log Audit") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Log berhasil disalin! Siap ditempel ke LLM.", Toast.LENGTH_SHORT).show()
    }

    fun shareText(context: Context, text: String, title: String = "Bagikan Log Audit Trading") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun copyTradeMarkdownToClipboard(
        context: Context,
        tradeId: String,
        symbol: String,
        baseAsset: String,
        quoteAsset: String,
        side: String,
        orderType: String,
        executionPrice: Double,
        quantity: Double,
        totalIdr: Double,
        feeIdr: Double,
        timestamp: Long,
        isReal: Boolean,
        strategyMode: String,
        holdingDurationMs: Long?,
        entryPrice: Double?,
        entryTimestamp: Long?,
        pnlIdr: Double?,
        pnlPercent: Double?,
        isTrailingUsed: Boolean,
        trailingPercent: Double?,
        trailingPeakPrice: Double?,
        trailingLockPrice: Double?,
        snapshot: TradeSignalSnapshot?,
        exchange: String? = null
    ) {
        val markdown = buildSingleTradeAuditMarkdown(
            tradeId = tradeId,
            symbol = symbol,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            side = side,
            orderType = orderType,
            executionPrice = executionPrice,
            quantity = quantity,
            totalIdr = totalIdr,
            feeIdr = feeIdr,
            timestamp = timestamp,
            isReal = isReal,
            strategyMode = strategyMode,
            holdingDurationMs = holdingDurationMs,
            entryPrice = entryPrice,
            entryTimestamp = entryTimestamp,
            pnlIdr = pnlIdr,
            pnlPercent = pnlPercent,
            isTrailingUsed = isTrailingUsed,
            trailingPercent = trailingPercent,
            trailingPeakPrice = trailingPeakPrice,
            trailingLockPrice = trailingLockPrice,
            snapshot = snapshot,
            exchange = exchange
        )
        copyToClipboard(context, markdown, "Trade Audit ($symbol)")
    }

    fun shareTradeLog(
        context: Context,
        tradeId: String,
        symbol: String,
        baseAsset: String,
        quoteAsset: String,
        side: String,
        orderType: String,
        executionPrice: Double,
        quantity: Double,
        totalIdr: Double,
        feeIdr: Double,
        timestamp: Long,
        isReal: Boolean,
        strategyMode: String,
        holdingDurationMs: Long?,
        entryPrice: Double?,
        entryTimestamp: Long?,
        pnlIdr: Double?,
        pnlPercent: Double?,
        isTrailingUsed: Boolean,
        trailingPercent: Double?,
        trailingPeakPrice: Double?,
        trailingLockPrice: Double?,
        snapshot: TradeSignalSnapshot?,
        exchange: String? = null
    ) {
        val markdown = buildSingleTradeAuditMarkdown(
            tradeId = tradeId,
            symbol = symbol,
            baseAsset = baseAsset,
            quoteAsset = quoteAsset,
            side = side,
            orderType = orderType,
            executionPrice = executionPrice,
            quantity = quantity,
            totalIdr = totalIdr,
            feeIdr = feeIdr,
            timestamp = timestamp,
            isReal = isReal,
            strategyMode = strategyMode,
            holdingDurationMs = holdingDurationMs,
            entryPrice = entryPrice,
            entryTimestamp = entryTimestamp,
            pnlIdr = pnlIdr,
            pnlPercent = pnlPercent,
            isTrailingUsed = isTrailingUsed,
            trailingPercent = trailingPercent,
            trailingPeakPrice = trailingPeakPrice,
            trailingLockPrice = trailingLockPrice,
            snapshot = snapshot,
            exchange = exchange
        )
        shareText(context, markdown, "Log Audit Trading $symbol")
    }
}
